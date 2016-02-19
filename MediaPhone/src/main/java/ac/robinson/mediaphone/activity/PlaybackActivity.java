/*
 *  This file is part of Com-Me.
 * 
 *  Com-Me is free software; you can redistribute it and/or modify it 
 *  under the terms of the GNU Lesser General Public License as 
 *  published by the Free Software Foundation; either version 3 of the 
 *  License, or (at your option) any later version.
 *
 *  Com-Me is distributed in the hope that it will be useful, but WITHOUT 
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY 
 *  or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General 
 *  Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with Com-Me.
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package ac.robinson.mediaphone.activity;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediaphone.MediaPhoneActivity;
import ac.robinson.mediaphone.R;
import ac.robinson.mediaphone.provider.FrameItem;
import ac.robinson.mediaphone.provider.FramesManager;
import ac.robinson.mediaphone.provider.MediaPhoneProvider;
import ac.robinson.mediaphone.provider.NarrativeItem;
import ac.robinson.mediaphone.provider.NarrativesManager;
import ac.robinson.mediaphone.provider.PlaybackMediaHolder;
import ac.robinson.mediaphone.provider.PlaybackNarrativeDescriptor;
import ac.robinson.mediaphone.util.SystemUiHider;
import ac.robinson.mediaphone.view.SendToBackRelativeLayout;
import ac.robinson.util.BitmapUtilities;
import ac.robinson.util.DebugUtilities;
import ac.robinson.util.IOUtilities;
import ac.robinson.util.UIUtilities;
import ac.robinson.view.AutoResizeTextView;
import ac.robinson.view.PlaybackController;
import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;

import com.larvalabs.svgandroid.SVGParser;

public class PlaybackActivity extends MediaPhoneActivity {

	// time (ms) to begin caching media before it should play - needs to be larger than PLAYBACK_UPDATE_INTERVAL_MILLIS
	// but should be smaller than most frame durations (default frame duration: 2500 ms) for best performance
	private static final int PRELOAD_SIZE = 1000;
	private static final int PLAYBACK_UPDATE_INTERVAL_MILLIS = 100; // how often to update the playback state (in ms)

	private static final int AUTO_HIDE_INITIAL_DELAY_MILLIS = 250; // ms after startup before hide if mAutoHide set
	private static final int AUTO_HIDE_DELAY_MILLIS = 3000; // ms after interaction before hiding if mAutoHide is set
	private static final boolean TOGGLE_HIDE_ON_CLICK = true; // whether to toggle system UI on interaction or just show
	private static final int UI_HIDER_FLAGS = SystemUiHider.FLAG_HIDE_NAVIGATION; // SystemUiHider.getInstance() flags

	private boolean mAutoHide = true; // whether to hide system UI after AUTO_HIDE_DELAY_MILLIS ms

	// the maximum number of audio items that will be played (or cached) at once - note that for preloading to work
	// effectively we need to specify more than the ui allows (i.e., UI number (3) x 2 = 6)
	private static final int MAX_AUDIO_ITEMS = 6;
	private static final int MAX_AUDIO_LOADING_ERRORS = 2; // times an audio item can fail to load before we give up

	private SystemUiHider mSystemUiHider; // for handling system UI hiding
	private Point mScreenSize; // for loading images at the correct size
	private int mFadeOutAnimationDuration; // for loading images so the fade completes when they should start

	private ArrayList<PlaybackMediaHolder> mNarrativeContent = null; // the list of media items to play, start time asc
	private ArrayList<PlaybackMediaHolder> mCurrentPlaybackItems = new ArrayList<PlaybackMediaHolder>();
	private ArrayList<PlaybackMediaHolder> mOldPlaybackItems = new ArrayList<PlaybackMediaHolder>();

	// this map holds the start times of every frame (ignoring content that spans multiple frames)
	private LinkedHashMap<Integer, String> mTimeToFrameMap = new LinkedHashMap<Integer, String>();

	private String mNarrativeInternalId = null; // the narrative we're playing

	private int mNarrativeContentIndex = 0; // the next mNarrativeContent item to be processed

	private int mPlaybackPositionMilliseconds = 0; // the current playback time, in milliseconds
	private int mPlaybackDurationMilliseconds = 0; // the duration of the narrative, in milliseconds

	private boolean mFinishedLoadingImages = false; // for tracking image loads, particularly during very short frames
	private String mCurrentPlaybackImagePath = null; // cached path for avoiding reloads where possible
	private String mBackgroundPlaybackImagePath = null; // cached next image path for avoiding reloads where possible
	private Bitmap mAudioPictureBitmap = null; // cached audio icon for avoiding reloads where possible

	private ArrayList<CustomMediaPlayer> mMediaPlayers = new ArrayList<CustomMediaPlayer>(MAX_AUDIO_ITEMS);

	private boolean mPlaying = true; // whether we're currently playing or paused
	private boolean mWasPlaying = false; // for saving the playback state while performing other actions
	private boolean mStateChanged = false; // whether we must reload/resize as the screen has rotated or state changed

	// UI elements for displaying, caching and animating media
	private SendToBackRelativeLayout mPlaybackRoot;
	private ImageView mCurrentPlaybackImage;
	private ImageView mBackgroundPlaybackImage;
	private AutoResizeTextView mPlaybackText;
	private AutoResizeTextView mPlaybackTextWithImage;
	private Animation mFadeOutAnimation;
	private PlaybackController mPlaybackController;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		UIUtilities.configureActionBar(this, true, true, R.string.title_playback, 0);
		setContentView(R.layout.playback_view);

		setupUI(); // initialise the interface and fullscreen controls/timeouts
		refreshPlayback(); // initialise (and start) playback
	}

	@Override
	protected void onPostCreate(Bundle savedInstanceState) {
		super.onPostCreate(savedInstanceState);
		delayedHide(AUTO_HIDE_INITIAL_DELAY_MILLIS); // the initial hide is shorter, for better presentation
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);

		// our screen size has most likely changed - must reload cached images
		resetImagePaths();
		mAudioPictureBitmap = null;

		// update the cached screen size
		mScreenSize = UIUtilities.getScreenSize(getWindowManager());

		// reload media - no playback delay so we can load immediately
		mStateChanged = true;
		mMediaAdvanceHandler.removeCallbacks(mMediaAdvanceRunnable);
		mMediaAdvanceHandler.post(mMediaAdvanceRunnable);
	}

	@Override
	protected void onDestroy() {
		mHideHandler.removeCallbacks(mHideRunnable);
		mMediaAdvanceHandler.removeCallbacks(mMediaAdvanceRunnable);
		mImageLoadHandler.removeCallbacks(mImageLoadRunnable);
		releasePlayers();
		super.onDestroy();
	}

	@Override
	public void onBackPressed() {
		// when playing from the frame editor we need to track narrative deletion so we can exit the parent activity
		boolean narrativeDeleted = false;
		if (mNarrativeInternalId != null) {
			NarrativeItem deletedNarrative = NarrativesManager.findNarrativeByInternalId(getContentResolver(),
					mNarrativeInternalId);
			if (deletedNarrative != null && deletedNarrative.getDeleted()) {
				narrativeDeleted = true;
				setResult(R.id.result_narrative_deleted_exit);
			}
		}

		// otherwise, we save the last viewed frame so we can jump to that one in the narrative browser
		if (!narrativeDeleted) {
			saveLastEditedFrame(getCurrentFrameId());
		}

		super.onBackPressed();
	}

	private String getCurrentFrameId() {
		String currentFrame = null;
		for (LinkedHashMap.Entry<Integer, String> entry : mTimeToFrameMap.entrySet()) {
			if (mPlaybackPositionMilliseconds >= entry.getKey()) {
				currentFrame = entry.getValue();
			} else {
				break;
			}
		}
		return currentFrame;
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.export_narrative, menu);
		inflater.inflate(R.menu.edit_frame, menu);
		inflater.inflate(R.menu.make_template, menu);
		inflater.inflate(R.menu.delete_narrative, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		handleNonPlaybackButtonClick(true);
		if (mNarrativeInternalId != null) {
			switch (item.getItemId()) {
				case R.id.menu_make_template:
					runQueuedBackgroundTask(getNarrativeTemplateRunnable(mNarrativeInternalId, true));
					return true;

				case R.id.menu_edit_frame:
					final String currentFrameId = getCurrentFrameId();
					mSystemUiHider.show(); // need to show before the calculation for image size in frame editor

					final Intent frameEditorIntent = new Intent(PlaybackActivity.this, FrameEditorActivity.class);
					frameEditorIntent.putExtra(getString(R.string.extra_internal_id), currentFrameId);
					startActivityForResult(frameEditorIntent, MediaPhone.R_id_intent_frame_editor);

					// make sure we reload playback when returning
					mNarrativeContent = null;

					// make sure not using any out of date images - done here so the ui will have updated before return
					mCurrentPlaybackImage.setImageDrawable(null);
					mBackgroundPlaybackImage.setImageDrawable(null);
					resetImagePaths();

					// make sure we return to the current frame
					final Intent selfIntent = getIntent();
					if (selfIntent != null) {
						selfIntent.putExtra(getString(R.string.extra_internal_id), currentFrameId);
						setIntent(selfIntent);
					}
					return true;

				case R.id.menu_delete_narrative:
					deleteNarrativeDialog(mNarrativeInternalId);
					return true;

				case R.id.menu_export_narrative:
					exportContent(mNarrativeInternalId, false);
					return true;
			}
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void loadPreferences(SharedPreferences mediaPhoneSettings) {
		// no normal preferences apply to this activity
	}

	@Override
	protected void configureInterfacePreferences(SharedPreferences mediaPhoneSettings) {
		// note: the soft back button preference is ignored during playback - we always show the button
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		if (hasFocus) {
			if (!mPlaying) {
				handleNonPlaybackButtonClick(false); // resume normal auto-hiding
				refreshPlayback(); // update to remove deleted items from frame editing
			}
		}
	}

	/**
	 * When clicking a button that is not part of the playback interface, we need to pause playback (in case a new
	 * activity is launched, for example). We also temporarily stop hiding the playback bar. On the second call, we
	 * resume hiding (and playback).
	 * 
	 * @param pause true if playback should be paused, false to resume after a non-playback action
	 */
	private void handleNonPlaybackButtonClick(boolean pause) {
		if (pause) {
			mAutoHide = false;
			mWasPlaying = mPlaying;
			if (mPlaying) {
				mMediaController.pause();
				mPlaybackController.refreshController();
			}
		} else {
			if (mWasPlaying) {
				mMediaController.play();
				mPlaybackController.refreshController();
			}
			mWasPlaying = false;
			mAutoHide = true;
			delayedHide(AUTO_HIDE_DELAY_MILLIS);
		}
	}

	private void setupUI() {

		// keep hold of key UI elements
		mPlaybackRoot = (SendToBackRelativeLayout) findViewById(R.id.playback_root);
		mCurrentPlaybackImage = (ImageView) findViewById(R.id.playback_image);
		mBackgroundPlaybackImage = (ImageView) findViewById(R.id.playback_image_background);
		mPlaybackText = (AutoResizeTextView) findViewById(R.id.playback_text);
		mPlaybackTextWithImage = (AutoResizeTextView) findViewById(R.id.playback_text_with_image);

		// set up a SystemUiHider instance to control the system UI for this activity
		final View controlsView = findViewById(R.id.playback_controls_wrapper);
		final View contentView = mPlaybackRoot;
		mSystemUiHider = SystemUiHider.getInstance(PlaybackActivity.this, contentView, UI_HIDER_FLAGS);
		mSystemUiHider.setup();
		mSystemUiHider.setOnVisibilityChangeListener(new SystemUiHider.OnVisibilityChangeListener() {

			int mControlsHeight;
			int mShortAnimTime;

			@Override
			@TargetApi(Build.VERSION_CODES.HONEYCOMB_MR2)
			public void onVisibilityChange(boolean visible) {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2) {
					// if the ViewPropertyAnimator API is available (Honeycomb MR2 and later), use it to animate the
					// playback controls at the bottom of the screen (sliding up or down)
					if (mControlsHeight == 0) {
						mControlsHeight = controlsView.getHeight();
					}
					if (mShortAnimTime == 0) {
						mShortAnimTime = getResources().getInteger(android.R.integer.config_shortAnimTime);
					}
					controlsView.animate().translationY(visible ? 0 : mControlsHeight).setDuration(mShortAnimTime);
				} else {
					// if the animation isn't available, simply show or hide (fading out) the playback controls
					controlsView.clearAnimation();
					if (!visible && controlsView.getVisibility() == View.VISIBLE) {
						controlsView.startAnimation(AnimationUtils.loadAnimation(PlaybackActivity.this,
								android.R.anim.fade_out));
					}
					controlsView.setVisibility(visible ? View.VISIBLE : View.GONE);
				}

				// schedule the next hide
				if (visible) {
					delayedHide(AUTO_HIDE_DELAY_MILLIS);
				}
			}
		});

		// set up non-playback button clicks
		mPlaybackController = ((PlaybackController) findViewById(R.id.playback_controller));
		mPlaybackController.setButtonListeners(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				onBackPressed();
			}
		}, new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				handleNonPlaybackButtonClick(true);
				if (mNarrativeInternalId != null) {
					exportContent(mNarrativeInternalId, false);
				}
			}
		});

		// this animation is used between image frames - the duration value is subtracted from image items' durations so
		// that they appear in the right place when fully loaded; use / 2 for exactly in the middle of the animation, or
		// / 3 for nearer to the end of the animation
		mFadeOutAnimation = AnimationUtils.loadAnimation(PlaybackActivity.this, android.R.anim.fade_out);
		mFadeOutAnimationDuration = (int) mFadeOutAnimation.getDuration() / 3;

		// make sure that the volume controls always control media volume (rather than ringtone etc.)
		setVolumeControlStream(AudioManager.STREAM_MUSIC);

		// cache screen size
		mScreenSize = UIUtilities.getScreenSize(getWindowManager());

		// make sure any user interaction will trigger manually showing or hiding the system UI
		View.OnClickListener systemUIClickHandler = new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				// TODO: there's still an issue with having to double press to show this on some devices (for example
				// HTC Sensation API v15), and that multiple toggles don't hide the system UI properly every other time
				// on others, resulting in text being invisible for a short while (for example Nexus 7 API v17)
				if (TOGGLE_HIDE_ON_CLICK) {
					mSystemUiHider.toggle();
				} else {
					mSystemUiHider.show();
				}
			}
		};
		mCurrentPlaybackImage.setOnClickListener(systemUIClickHandler);
		mBackgroundPlaybackImage.setOnClickListener(systemUIClickHandler);
		mPlaybackText.setOnClickListener(systemUIClickHandler);
		mPlaybackTextWithImage.setOnClickListener(systemUIClickHandler);
	}

	// handler and runnable for system UI hiding
	Handler mHideHandler = new Handler();
	Runnable mHideRunnable = new Runnable() {
		@Override
		public void run() {
			if (mAutoHide) {
				mSystemUiHider.hide();
			}
		}
	};

	/**
	 * Schedules a call to mSystemUiHider.hide(), cancelling any previously scheduled calls
	 * 
	 * @param delayMillis how long to wait before hiding, in milliseconds
	 */
	private void delayedHide(int delayMillis) {
		mHideHandler.removeCallbacks(mHideRunnable);
		mHideHandler.postDelayed(mHideRunnable, delayMillis);
	}

	/**
	 * Piggyback on touch events to stop scheduled hide() operations and prevent jarring hide during interaction
	 */
	@Override
	public boolean dispatchTouchEvent(MotionEvent event) {
		delayedHide(AUTO_HIDE_DELAY_MILLIS);
		return super.dispatchTouchEvent(event);
	}

	/**
	 * Piggyback on trackball events to stop scheduled hide() operations and prevent jarring hide during interaction
	 */
	@Override
	public boolean dispatchTrackballEvent(MotionEvent event) {
		delayedHide(AUTO_HIDE_DELAY_MILLIS);
		return super.dispatchTrackballEvent(event);
	}

	// handler and runnable for scheduling playback advances
	Handler mMediaAdvanceHandler = new Handler();
	Runnable mMediaAdvanceRunnable = new Runnable() {
		@Override
		public void run() {
			if (!mPlaybackController.isDragging()) {
				if (mPlaying || mStateChanged) {
					if (mPlaying) {
						mPlaybackPositionMilliseconds += Math.min(PLAYBACK_UPDATE_INTERVAL_MILLIS,
								mPlaybackDurationMilliseconds - mPlaybackPositionMilliseconds);
					}
					refreshPlayback();
				}
			} else {
				delayedPlaybackAdvance(); // if dragging we still want to keep playing, just not advancing the timer
			}
		}
	};

	/**
	 * Schedules a call to refreshPlayback() (via mMediaAdvanceRunnable), cancelling any previously scheduled calls
	 */
	private void delayedPlaybackAdvance() {
		int delayMillis = Math.min(PLAYBACK_UPDATE_INTERVAL_MILLIS, mPlaybackDurationMilliseconds
				- mPlaybackPositionMilliseconds);

		// we limit the lower bound to 50ms because otherwise we'd overload the message queue and nothing would happen
		mMediaAdvanceHandler.removeCallbacks(mMediaAdvanceRunnable);
		mMediaAdvanceHandler.postDelayed(mMediaAdvanceRunnable, delayMillis < 50 ? 50 : delayMillis);
	}

	// handler and runnable for scheduling loading the full quality image when seeking
	Handler mImageLoadHandler = new Handler();
	Runnable mImageLoadRunnable = new Runnable() {
		@Override
		public void run() {
			if (mCurrentPlaybackImagePath != null) {
				// don't fade in the image here - if we do, we can possibly get a race condition with loading and
				// fading, resulting in both being shown, one on top of the other; best to just load in place
				loadScreenSizedImageInBackground(mCurrentPlaybackImage, mCurrentPlaybackImagePath, true,
						MediaPhoneActivity.FadeType.NONE);
			}
		}
	};

	/**
	 * Schedules a load of the image in mCurrentPlaybackImagePath, cancelling any previously scheduled load requests
	 */
	private void delayedImageLoad(int delayMs) {
		mImageLoadHandler.removeCallbacks(mImageLoadRunnable);
		mImageLoadHandler.postDelayed(mImageLoadRunnable, delayMs);
	}

	/**
	 * Initialise everything required for playback
	 * 
	 * @return true if initialisation succeeded, false otherwise
	 */
	private boolean initialisePlayback() {
		// we need the parent id of the narrative we want to load
		final Intent intent = getIntent();
		String startFrameId = null;
		if (intent != null) {
			startFrameId = intent.getStringExtra(getString(R.string.extra_internal_id));
		}
		if (startFrameId == null) {
			return false;
		}

		// load narrative content TODO: lazily load - AsyncTask? (but remember single-threading on newer SDK versions)
		ContentResolver contentResolver = getContentResolver();
		FrameItem currentFrame = FramesManager.findFrameByInternalId(contentResolver, startFrameId);
		if (currentFrame == null) {
			return false;
		}
		mNarrativeInternalId = currentFrame.getParentId();
		NarrativeItem currentNarrative = NarrativesManager.findNarrativeByInternalId(contentResolver,
				mNarrativeInternalId);
		PlaybackNarrativeDescriptor narrativeProperties = new PlaybackNarrativeDescriptor(mFadeOutAnimationDuration);
		mNarrativeContent = currentNarrative.getPlaybackContent(contentResolver, startFrameId, narrativeProperties);
		mCurrentPlaybackItems.clear();

		// initialise the start time (of the requested frame) and the narrative's duration
		mNarrativeContentIndex = 0;
		mPlaybackPositionMilliseconds = narrativeProperties.mNarrativeStartTime;
		mPlaybackDurationMilliseconds = narrativeProperties.mNarrativeDuration;
		mTimeToFrameMap = narrativeProperties.mTimeToFrameMap;

		// reset and release audio players; a new set will be built up when needed
		releasePlayers();

		// initialise the media controller and set up a listener for when manual seek ends
		mPlaybackController.setMediaPlayerControl(mMediaController);
		mPlaybackController.setUseCustomSeekButtons(true); // we handle rewind/ffwd ourselves
		mPlaybackController.setSeekEndedListener(new PlaybackController.SeekEndedListener() {
			@Override
			public void seekEnded() {
				// when a drag/seek ends we need to make sure we don't continue reloading any cached images
				cancelLoadingScreenSizedImageInBackground(mCurrentPlaybackImage);
				mImageLoadHandler.removeCallbacks(mImageLoadRunnable);

				// the current and previous cached images are highly likely to be wrong - reload
				resetImagePaths();

				// like in seekTo, this is inefficient, but probably not worth working around
				mNarrativeContentIndex = 0;

				// schedule playback to continue
				if (mPlaying) {
					playPreparedAudio(true);
				} else {
					pauseAudio();
				}
				mStateChanged = true; // we've changed state - forces a reload when playback is paused
				delayedPlaybackAdvance();
			}
		});

		return true;
	}

	/**
	 * Refresh the current playback state, loading media where appropriate. Will call initialisePlayback() first if
	 * mNarrativeContent is null
	 * 
	 * <b>Note:</b> this should never be called directly, except for in onCreate when first starting and in seekTo (a
	 * special case) - use delayedPlaybackAdvance() at all other times
	 */
	private void refreshPlayback() {
		// first load - initialise narrative content and audio players
		if (mNarrativeContent == null) {
			if (!initialisePlayback()) {
				UIUtilities.showToast(PlaybackActivity.this, R.string.error_loading_narrative_player);
				onBackPressed();
				return;
			}
		}

		// we must have narrative content to be able to play
		final int narrativeSize = mNarrativeContent.size();
		if (narrativeSize <= 0) {
			UIUtilities.showToast(PlaybackActivity.this, R.string.error_loading_narrative_player);
			onBackPressed();
			return;
		}

		// we preload content to speed up transitions and more accurately keep playback time
		final int preCachedPlaybackTime = mPlaybackPositionMilliseconds + PRELOAD_SIZE;

		// remove any media that is now outdated (also stopping outdated audio in the process)
		boolean itemsRemoved = false;
		if (mNarrativeContentIndex == 0) { // if we're resetting, remove all items to preserve their order
			mCurrentPlaybackItems.clear();
			itemsRemoved = true;
		} else {
			mOldPlaybackItems.clear();
			for (PlaybackMediaHolder holder : mCurrentPlaybackItems) {
				if (holder.getStartTime(true) > preCachedPlaybackTime
						|| holder.getEndTime(true) <= mPlaybackPositionMilliseconds) {
					mOldPlaybackItems.add(holder);
					if (holder.mMediaType == MediaPhoneProvider.TYPE_AUDIO) {
						CustomMediaPlayer p = getExistingAudio(holder.mMediaPath);
						if (p != null) {
							try {
								p.stop();
							} catch (IllegalStateException e) {
							}
							p.resetCustomAttributes();
						}
						break;
					}
				}
			}
			itemsRemoved = mCurrentPlaybackItems.removeAll(mOldPlaybackItems);
		}

		// now get any media that covers the current timestamp plus our preload period
		boolean itemsAdded = false;
		for (int i = mNarrativeContentIndex; i < narrativeSize; i++) {
			final PlaybackMediaHolder holder = mNarrativeContent.get(i);
			if (holder.getStartTime(true) <= preCachedPlaybackTime
					&& holder.getEndTime(true) > mPlaybackPositionMilliseconds
					&& new File(holder.mMediaPath).length() > 0) {
				if (!mCurrentPlaybackItems.contains(holder)) {
					mCurrentPlaybackItems.add(holder);
				}
				mNarrativeContentIndex = i + 1;
				itemsAdded = true;
			} else if (holder.getStartTime(true) > preCachedPlaybackTime) {
				break;
			}
		}

		// check if we're at the end of playback - pause if so; if dragging, then this is ok (will check after)
		if (!mPlaybackController.isDragging() && mPlaybackPositionMilliseconds >= mPlaybackDurationMilliseconds) {
			mPlaying = false;
			mPlaybackController.refreshController();
			if (mAutoHide && !mSystemUiHider.isVisible()) {
				mSystemUiHider.show();
			}
		}

		// check whether we need to reload any existing content (due to screen rotation); if not, exit
		if (!itemsRemoved && !itemsAdded) {
			boolean mustReload = mStateChanged;
			if (!mustReload) {
				// media items that we might have missed (started within the time period) need to be loaded
				for (PlaybackMediaHolder holder : mCurrentPlaybackItems) {
					final int timeDifference = mPlaybackPositionMilliseconds - holder.getStartTime(true);
					if (timeDifference > 0 && timeDifference <= PLAYBACK_UPDATE_INTERVAL_MILLIS) {
						mustReload = true;
						break;
					}
				}
			}
			if (!mustReload) {
				delayedPlaybackAdvance();
				return; // nothing else to do
			}
		}

		mStateChanged = false; // if we get here we're reloading, so reset rotation tracking

		// load images and audio before text so we can set up their display/playback at the right times
		// TODO: there are potential memory issues here - setting an ImageView's drawable doesn't reliably clear the
		// memory allocated to the existing drawable - do we need to get the drawables and recycle the bitmaps?
		// (if so, need to beware of recycling the audio image bitmap, or just check for isRecycled() on load)
		PlaybackMediaHolder textItem = null;
		boolean hasImage = false;
		boolean hasAudio = false;
		for (PlaybackMediaHolder holder : mCurrentPlaybackItems) {
			// no need to check end time - we've removed invalid items already
			boolean itemAppliesNow = holder.getStartTime(true) <= mPlaybackPositionMilliseconds;

			switch (holder.mMediaType) {
				case MediaPhoneProvider.TYPE_IMAGE_FRONT:
				case MediaPhoneProvider.TYPE_IMAGE_BACK:
				case MediaPhoneProvider.TYPE_VIDEO:

					hasImage |= itemAppliesNow;
					if (mPlaybackController.isDragging()) {

						// while dragging we need to trade off good UI performance against memory usage (could
						// overflow limit if we just background loaded everything) - instead, load a downscaled
						// version on the UI thread then update to show the full resolution version after a timeout
						if (itemAppliesNow && !holder.mMediaPath.equals(mCurrentPlaybackImagePath)) {
							cancelLoadingScreenSizedImageInBackground(mCurrentPlaybackImage);
							Bitmap scaledBitmap = null;
							try {
								scaledBitmap = BitmapUtilities.loadAndCreateScaledBitmap(holder.mMediaPath,
										mScreenSize.x, mScreenSize.y, BitmapUtilities.ScalingLogic.DOWNSCALE, true);
							} catch (Throwable t) {
								// out of memory...
							}
							mCurrentPlaybackImage.setImageBitmap(scaledBitmap);

							mCurrentPlaybackImagePath = holder.mMediaPath;
							mBackgroundPlaybackImagePath = null; // any previously cached image will now be wrong

							delayedImageLoad(PLAYBACK_UPDATE_INTERVAL_MILLIS);
						}

					} else if (itemAppliesNow && mCurrentPlaybackImagePath == null) {

						// if an item applies now and there's nothing stored in the current path it's the first image
						// - for the first image, it's a better UI experience if loading happens in situ (~250ms)
						if (holder.mMediaPath.equals(mBackgroundPlaybackImagePath)) {
							// if the first frame wasn't an image, then we'll have already loaded it in the background
							swapBackgroundImage();
						} else {
							Bitmap scaledBitmap = null;
							try {
								scaledBitmap = BitmapUtilities.loadAndCreateScaledBitmap(holder.mMediaPath,
										mScreenSize.x, mScreenSize.y, BitmapUtilities.ScalingLogic.FIT, true);
							} catch (Throwable t) { // out of memory...
							}
							mCurrentPlaybackImage.setImageBitmap(scaledBitmap);
						}
						mCurrentPlaybackImagePath = holder.mMediaPath;

					} else if (!holder.mMediaPath.equals(mCurrentPlaybackImagePath)) {

						// preload the next image (making sure not to reload either the current or multiple nexts)
						// did try preloading a downscaled version here while the full version was loading, but that
						// led to out of memory errors on some devices - just load the normal version instead
						if (!mFinishedLoadingImages && !holder.mMediaPath.equals(mBackgroundPlaybackImagePath)) {
							mImageLoadHandler.removeCallbacks(mImageLoadRunnable); // no need to load prevs any more
							loadScreenSizedImageInBackground(mBackgroundPlaybackImage, holder.mMediaPath, true,
									MediaPhoneActivity.FadeType.NONE);
							mBackgroundPlaybackImagePath = holder.mMediaPath;
							mFinishedLoadingImages = true;

						} else if (itemAppliesNow) {
							// if necessary, swap the preloaded image to replace the current image
							swapBackgroundImage();
							mCurrentPlaybackImagePath = holder.mMediaPath;
						}
					}
					break;

				case MediaPhoneProvider.TYPE_AUDIO:
					if (getExistingAudio(holder.mMediaPath) == null) {
						CustomMediaPlayer currentMediaPlayer = getEmptyPlayer();
						if (currentMediaPlayer == null) {
							// no available audio players - most likely trying to cache too far in advance; ignore
							break;
						} else {
							currentMediaPlayer.mMediaPath = holder.mMediaPath;
							currentMediaPlayer.mMediaStartTime = holder.getStartTime(true);
							currentMediaPlayer.mPlaybackPrepared = false;
						}

						FileInputStream playerInputStream = null;
						boolean dataLoaded = false;
						int dataLoadingErrorCount = 0;
						while (!dataLoaded && dataLoadingErrorCount <= MAX_AUDIO_LOADING_ERRORS) {
							try {
								// can't play from data dir (private; permissions don't work), must use input stream
								currentMediaPlayer.reset();

								playerInputStream = new FileInputStream(new File(holder.mMediaPath));
								currentMediaPlayer.setDataSource(playerInputStream.getFD());
								currentMediaPlayer.setLooping(false);
								currentMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
								currentMediaPlayer.setOnPreparedListener(mMediaPlayerPreparedListener);
								currentMediaPlayer.setOnCompletionListener(mMediaPlayerCompletionListener);
								currentMediaPlayer.setOnErrorListener(mMediaPlayerErrorListener);
								currentMediaPlayer.prepareAsync();
								dataLoaded = true;
							} catch (Throwable t) {
								// sometimes setDataSource fails mysteriously - loop to open, rather than failing
								dataLoaded = false;
								dataLoadingErrorCount += 1;
							} finally {
								IOUtilities.closeStream(playerInputStream);
								playerInputStream = null;
							}
						}

						if (!dataLoaded) {
							// we couldn't load anything - reset this player so we can reuse it
							currentMediaPlayer.resetCustomAttributes();
						} else {
							hasAudio |= itemAppliesNow;
						}
					} else {
						hasAudio |= itemAppliesNow;
					}
					break;

				case MediaPhoneProvider.TYPE_TEXT:
					if (itemAppliesNow) {
						textItem = holder; // text is loaded after all other content
					}
					break;
			}
		}

		// no image content on this frame - remove
		if (!hasImage && !hasAudio) {
			mCurrentPlaybackImage.setImageDrawable(null);
			mImageLoadHandler.removeCallbacks(mImageLoadRunnable);
			mCurrentPlaybackImagePath = null; // the current image is highly likely to be wrong - reload
		}

		// load text last so we know whether we've loaded image/audio or not
		if (textItem != null) {
			// TODO: currently we load text every time - could check, but this would require loading the file anyway...
			String textContents = IOUtilities.getFileContents(textItem.mMediaPath).trim();
			if (!TextUtils.isEmpty(textContents)) {
				if (hasImage) {
					mPlaybackText.setVisibility(View.GONE);
					mPlaybackTextWithImage.setText(textContents);
					mPlaybackTextWithImage.setVisibility(View.VISIBLE);
				} else {
					mPlaybackTextWithImage.setVisibility(View.GONE);
					mPlaybackText.setText(textContents);
					mPlaybackText.setVisibility(View.VISIBLE);
				}
			} else {
				mPlaybackText.setVisibility(View.GONE);
				mPlaybackTextWithImage.setVisibility(View.GONE);
			}
		} else {
			mPlaybackText.setVisibility(View.GONE);
			mPlaybackTextWithImage.setVisibility(View.GONE);

			// an audio-only frame - show the audio icon
			if (!hasImage && hasAudio) {
				if (mAudioPictureBitmap == null) {
					try {
						mAudioPictureBitmap = SVGParser.getSVGFromResource(getResources(), R.raw.ic_audio_playback)
								.getBitmap(mScreenSize.x, mScreenSize.y);
					} catch (Throwable t) { // out of memory, or parse error...
					}
				}
				mCurrentPlaybackImagePath = String.valueOf(R.raw.ic_audio_playback); // now the current image
				mCurrentPlaybackImage.setImageBitmap(mAudioPictureBitmap);
			}
		}

		// start/seek any pre-cached audio players that might now be ready
		if (hasAudio) {
			playPreparedAudio(false);
		}

		// queue advancing the playback handler
		delayedPlaybackAdvance();
	}

	private void swapBackgroundImage() {
		// if the image hasn't yet loaded, it's likely we're playing a narrative with really short frames - to try to
		// keep up with playback, cancel loading and just show a downscaled version; queuing the full-sized version
		if (mBackgroundPlaybackImage.getTag() != null) {
			// TODO: check that this doesn't cause problems on low-capability v14+ devices (e.g., HTC Sensation)
			cancelLoadingScreenSizedImageInBackground(mBackgroundPlaybackImage);
			Bitmap scaledBitmap = null;
			try {
				scaledBitmap = BitmapUtilities.loadAndCreateScaledBitmap(mBackgroundPlaybackImagePath, mScreenSize.x,
						mScreenSize.y, BitmapUtilities.ScalingLogic.DOWNSCALE, true);
			} catch (Throwable t) { // out of memory...
			}
			mBackgroundPlaybackImage.setImageBitmap(scaledBitmap);

			// load the full-resolution image, but wait longer here just in case we're loading lots of short frames
			delayedImageLoad(PLAYBACK_UPDATE_INTERVAL_MILLIS * 5);
		}

		ImageView temp = mCurrentPlaybackImage;
		mCurrentPlaybackImage = mBackgroundPlaybackImage;
		mBackgroundPlaybackImage = temp;

		// the new image should be at the back to fade between frames - use a custom layout for this
		mPlaybackRoot.sendChildToBack(mCurrentPlaybackImage);
		mCurrentPlaybackImage.setVisibility(View.VISIBLE);

		// now fade out the old image
		// TODO: if we've seeked, and swap from a null image before loading, this looks bad; probably a non-problem...
		mBackgroundPlaybackImage.startAnimation(mFadeOutAnimation);
		mBackgroundPlaybackImage.setVisibility(View.GONE);

		// mBackgroundPlaybackImage.setImageDrawable(null); // don't do this - transitions look bad
		mBackgroundPlaybackImagePath = null;
		mFinishedLoadingImages = false;
	}

	/**
	 * Reset our cached image paths when reloading from, for example, a screen rotation, or an edit operation
	 */
	private void resetImagePaths() {
		mCurrentPlaybackImagePath = null;
		mBackgroundPlaybackImagePath = null;
		mFinishedLoadingImages = false;
	}

	/**
	 * Check whether the given file is currently being played by one of our CustomMediaPlayer instances. If so, return
	 * the player; if not, return null.
	 * 
	 * @param audioPath
	 * @return the CustomMediaPlayer that is playing this file, or null if the file is not being played
	 */
	private CustomMediaPlayer getExistingAudio(String audioPath) {
		if (audioPath == null) {
			return null;
		}
		for (CustomMediaPlayer p : mMediaPlayers) {
			if (audioPath.equals(p.mMediaPath)) {
				return p;
			}
		}
		return null;
	}

	/**
	 * Get a free CustomMediaPlayer from the global list
	 * 
	 * @return an unused CustomMediaPlayer
	 */
	private CustomMediaPlayer getEmptyPlayer() {
		for (CustomMediaPlayer p : mMediaPlayers) {
			if (p.mMediaPath == null) {
				p.resetCustomAttributes();
				return p;
			}
		}
		if (mMediaPlayers.size() < MAX_AUDIO_ITEMS) {
			final CustomMediaPlayer p = new CustomMediaPlayer();
			mMediaPlayers.add(p);
			return p;
		}
		return null;
	}

	/**
	 * If the narrative is playing, start/resume/seek any audio items that are prepared, but only when <b>all</b> items
	 * that apply to the current playback position have been prepared. If any applicable items are not prepared, nothing
	 * will be done.
	 */
	private void playPreparedAudio(boolean force) {
		if (mPlaying) {
			boolean allPrepared = true;
			boolean hasAudio = false; // if there's no audio we'd still be allPrepared mode otherwise
			for (CustomMediaPlayer player : mMediaPlayers) {
				if (player.mMediaPath != null) {
					hasAudio = true;
					if (player.mMediaStartTime <= mPlaybackPositionMilliseconds && !player.mPlaybackPrepared) {
						allPrepared = false;
						break;
					}
				}
			}
			if (hasAudio && allPrepared) {
				for (CustomMediaPlayer player : mMediaPlayers) {
					if (player.mPlaybackPrepared) {
						try {
							if (player.mMediaStartTime <= mPlaybackPositionMilliseconds
									&& player.mMediaEndTime > mPlaybackPositionMilliseconds) {
								if (!player.isPlaying() && (force || !player.mHasPlayed)) {
									player.seekTo(mPlaybackPositionMilliseconds - player.mMediaStartTime);
									player.start();
								}
							} else {
								if (player.isPlaying()) {
									player.pause();
								}
							}
						} catch (IllegalStateException e) {
							player.resetCustomAttributes();
						}
					}
				}
			}
		}
	}

	private void seekPlayingAudio() {
		for (CustomMediaPlayer player : mMediaPlayers) {
			if (player.mMediaStartTime <= mPlaybackPositionMilliseconds
					&& player.mMediaEndTime > mPlaybackPositionMilliseconds) {
				if (player.mPlaybackPrepared) {
					try {
						player.seekTo(mPlaybackPositionMilliseconds - player.mMediaStartTime);
						if (!player.isPlaying()) {
							player.start();
						}
					} catch (IllegalStateException e) {
						player.resetCustomAttributes();
					}
				}
			} else {
				if (player.mPlaybackPrepared) {
					try {
						if (player.isPlaying()) {
							player.pause();
						}
					} catch (IllegalStateException e) {
						player.resetCustomAttributes();
					}
				}

				// need to track whether audio has played to deal with timing errors between visuals and audio;
				// but when seeking need to re-enable audio that has already been played so it can be replayed
				player.mHasPlayed = false;
			}
		}
	}

	private void pauseAudio() {
		for (CustomMediaPlayer player : mMediaPlayers) {
			if (player.mPlaybackPrepared) {
				try {
					if (player.isPlaying()) {
						player.pause();
					}
				} catch (IllegalStateException e) {
					player.resetCustomAttributes();
				}
			}
		}
	}

	private OnPreparedListener mMediaPlayerPreparedListener = new OnPreparedListener() {
		@Override
		public void onPrepared(MediaPlayer mp) {
			if (mp instanceof CustomMediaPlayer) {
				CustomMediaPlayer player = (CustomMediaPlayer) mp;
				player.mPlaybackPrepared = true;
				player.mMediaEndTime = player.mMediaStartTime + player.getDuration();
			}
			playPreparedAudio(false); // play audio if appropriate - will wait for all applicable items to be prepared
		}
	};

	private OnCompletionListener mMediaPlayerCompletionListener = new OnCompletionListener() {
		@Override
		public void onCompletion(MediaPlayer mp) {
			// at the moment we don't need to do anything here
		}
	};

	private OnErrorListener mMediaPlayerErrorListener = new OnErrorListener() {
		@Override
		public boolean onError(MediaPlayer mp, int what, int extra) {
			if (MediaPhone.DEBUG)
				Log.d(DebugUtilities.getLogTag(this), "Playback error - what: " + what + ", extra: " + extra);
			return false; // not handled -> onCompletionListener will be called
		}
	};

	/**
	 * Releases all CustomMediaPlayer instances
	 */
	private void releasePlayers() {
		for (CustomMediaPlayer p : mMediaPlayers) {
			p.release();
		}
		mMediaPlayers.clear();
	}

	/**
	 * A custom MediaPlayer that can store the file path of the item being played, its start time in the narrative, and
	 * whether the player has been prepared
	 */
	private class CustomMediaPlayer extends MediaPlayer {
		public boolean mPlaybackPrepared = false;
		public boolean mHasPlayed = false;
		public String mMediaPath = null;
		public int mMediaStartTime = 0;
		public int mMediaEndTime = 0;

		public void resetCustomAttributes() {
			mPlaybackPrepared = false;
			mHasPlayed = false;
			mMediaPath = null;
			mMediaStartTime = 0;
			mMediaEndTime = 0;
			reset();
		}

		@Override
		public void start() {
			mHasPlayed = true;
			super.start();
		}
	}

	/**
	 * Handler for user interaction with the narrative player. Most operations are self explanatory, but seeking is
	 * slightly more complex as we need to ensure that media items are loaded at the correct time and in the right order
	 */
	private PlaybackController.MediaPlayerControl mMediaController = new PlaybackController.MediaPlayerControl() {
		@Override
		public void play() {
			if (mPlaybackPositionMilliseconds >= mPlaybackDurationMilliseconds) {
				// if we've reached the end of playback, start again from the beginning
				mNarrativeContentIndex = 0;
				mPlaybackPositionMilliseconds = 0;
				resetImagePaths();
			}
			mPlaying = true;
			playPreparedAudio(true);
			delayedPlaybackAdvance();
		}

		@Override
		public void pause() {
			// TODO: stop sending handler messages? (rather than just not updating) - need to consider seeking if so
			mPlaying = false;
			pauseAudio();
		}

		@Override
		public int getDuration() {
			return mPlaybackDurationMilliseconds;
		}

		@Override
		public int getCurrentPosition() {
			return mPlaybackPositionMilliseconds;
		}

		@Override
		public void seekTo(int pos) {
			if (pos < mPlaybackPositionMilliseconds) {
				// seeking backwards is far less efficient than forwards - we can't just loop back until we find a
				// limit, because the list is ordered by start time and items can last a long time (we would need a list
				// of items ordered by end time to be able to seek backwards more efficiently)
				mNarrativeContentIndex = 0;
			}

			// update the playback position and seek audio to the correct place
			mPlaybackPositionMilliseconds = pos;
			seekPlayingAudio();

			// we call refreshPlayback directly, so must stop any queued playback advances
			mMediaAdvanceHandler.removeCallbacks(mMediaAdvanceRunnable);
			refreshPlayback();
		}

		@Override
		public void seekButton(int direction) {
			// find the previous and next frames in the frame map
			int previousFrameTime = 0;
			int tempFrameTime = 0;
			int nextFrameTime = mPlaybackDurationMilliseconds;
			int comparisonTime = mPlaybackPositionMilliseconds + 1; // to allow repeated pressing of next key
			for (int time : mTimeToFrameMap.keySet()) {
				if (time < comparisonTime) {
					previousFrameTime = tempFrameTime;
					tempFrameTime = time;
				} else {
					nextFrameTime = time;
					break;
				}
			}

			// cancel pending images and reset cached paths
			cancelLoadingScreenSizedImageInBackground(mCurrentPlaybackImage);
			resetImagePaths(); // any previously cached image will now be wrong

			if (direction < 0) {
				mNarrativeContentIndex = 0; // as in seekTo(), seeking backwards is far less efficient than forwards
				mPlaybackPositionMilliseconds = previousFrameTime;
			} else {
				mPlaybackPositionMilliseconds = nextFrameTime;
			}
			playPreparedAudio(true);

			// we call refreshPlayback directly, so must stop any queued playback advances
			mMediaAdvanceHandler.removeCallbacks(mMediaAdvanceRunnable);
			refreshPlayback();
		}

		@Override
		public boolean isPlaying() {
			return mPlaying;
		}
	};
}
