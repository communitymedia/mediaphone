/*
 *  Copyright (C) 2020 Simon Robinson
 *
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

import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.larvalabs.svgandroid.SVGParser;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import ac.robinson.mediaphone.BuildConfig;
import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediaphone.MediaPhoneActivity;
import ac.robinson.mediaphone.R;
import ac.robinson.mediaphone.provider.FrameItem;
import ac.robinson.mediaphone.provider.FramesManager;
import ac.robinson.mediaphone.provider.MediaItem;
import ac.robinson.mediaphone.provider.MediaManager;
import ac.robinson.mediaphone.provider.MediaPhoneProvider;
import ac.robinson.mediaphone.provider.NarrativeItem;
import ac.robinson.mediaphone.provider.NarrativesManager;
import ac.robinson.mediaphone.provider.PlaybackMediaHolder;
import ac.robinson.mediaphone.provider.PlaybackNarrativeDescriptor;
import ac.robinson.mediaphone.view.SendToBackRelativeLayout;
import ac.robinson.mediaphone.view.SystemUiHider;
import ac.robinson.util.BitmapUtilities;
import ac.robinson.util.DebugUtilities;
import ac.robinson.util.IOUtilities;
import ac.robinson.util.UIUtilities;
import ac.robinson.view.AutoResizeTextView;
import ac.robinson.view.PlaybackController;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.BlendModeColorFilterCompat;
import androidx.core.graphics.BlendModeCompat;

public class PlaybackActivity extends MediaPhoneActivity {

	// time (ms) to begin caching media before it should play - needs to be larger than PLAYBACK_UPDATE_INTERVAL_MILLIS
	// but should be smaller than most frame durations (default frame duration: 2500 ms) for best performance
	private static final int PRELOAD_SIZE = 1000;
	private static final int PLAYBACK_UPDATE_INTERVAL_MILLIS = 100; // how often to update the playback state (in ms)

	private static final int AUTO_HIDE_DELAY_MILLIS = 3000; // ms after interaction before hiding if mAutoHide is set
	private static final int AUTO_HIDE_INITIAL_DELAY_MILLIS = AUTO_HIDE_DELAY_MILLIS / 2; // ms after startup before hide
	private static final boolean TOGGLE_HIDE_ON_CLICK = true; // whether to toggle system UI on interaction or just show

	// the maximum number of audio items that will be played (or cached) at once - note that for preloading to work
	// effectively we need to specify more than the ui allows (i.e., UI number (3) x 2 = 6)
	private static final int MAX_AUDIO_ITEMS = 6;
	private static final int MAX_AUDIO_LOADING_ERRORS = 2; // times an audio item can fail to load before we give up

	private SystemUiHider mSystemUiHider; // for handling system UI hiding
	private Point mScreenSize; // for loading images at the correct size
	private int mFadeOutAnimationDurationAdjustment; // for loading images so the fade completes when they should start
	private long mLastFadeOutAnimationTime; // for avoiding fading between images that don't last longer than the fade duration
	private boolean mShouldAutoHide = true; // current hiding state for system UI after AUTO_HIDE_DELAY_MILLIS ms

	private ArrayList<PlaybackMediaHolder> mNarrativeContent = null; // the list of media items to play, start time asc
	private ArrayList<PlaybackMediaHolder> mCurrentPlaybackItems = new ArrayList<>();
	private ArrayList<PlaybackMediaHolder> mOldPlaybackItems = new ArrayList<>();

	// this map holds the start times of every frame (ignoring content that spans multiple frames)
	private LinkedHashMap<Integer, String> mTimeToFrameMap = new LinkedHashMap<>();

	private String mNarrativeInternalId = null; // the narrative we're playing

	private int mNarrativeContentIndex = 0; // the next mNarrativeContent item to be processed

	private int mPlaybackPositionMilliseconds = 0; // the current playback time, in milliseconds
	private int mPlaybackDurationMilliseconds = 0; // the duration of the narrative, in milliseconds

	private boolean mFinishedLoadingImages = false; // for tracking image loads, particularly during very short frames
	private String mCurrentPlaybackImagePath = null; // cached path for avoiding reloads where possible
	private String mBackgroundPlaybackImagePath = null; // cached next image path for avoiding reloads where possible
	private Bitmap mAudioPictureBitmap = null; // cached audio icon for avoiding reloads where possible

	private ArrayList<CustomMediaPlayer> mMediaPlayers = new ArrayList<>(MAX_AUDIO_ITEMS);

	private boolean mPlaying = true; // whether we're currently playing or paused
	private boolean mStateChanged = false; // whether we must reload/resize as the screen has rotated or state changed

	// UI elements for displaying, caching and animating media
	private SendToBackRelativeLayout mPlaybackRoot;
	private LinearLayout mPlaybackControlsWrapper;
	private ImageView mCurrentPlaybackImage;
	private ImageView mBackgroundPlaybackImage;
	private AutoResizeTextView mPlaybackText;
	private AutoResizeTextView mPlaybackTextWithImage;
	private Animation mFadeOutAnimation;
	private PlaybackController mPlaybackController;

	// the special mode for editing frame timings
	private boolean mAllowTimingMode;
	private boolean mTimingModeEnabled;
	private boolean mTimingPreviewEnabled;
	private String mPreviousTimingModeFrame;
	private RelativeLayout mTimingEditorBanner;
	private RelativeLayout mTimingEditorMinimised;
	private TextView mTimingEditorBannerHint;
	private ImageButton mTimingEditorMinimiseButton;
	private Button mTimingEditorResetResumeButton;
	private Button mTimingEditorPreviewSaveButton;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.playback_view);

		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayShowTitleEnabled(true);
			actionBar.setDisplayHomeAsUpEnabled(true);
		}

		setupUI(); // initialise the interface and fullscreen controls/timeouts
		refreshPlayback(); // initialise (and start) playback
	}

	@Override
	protected void onPostCreate(Bundle savedInstanceState) {
		super.onPostCreate(savedInstanceState);
		delayedHide(AUTO_HIDE_INITIAL_DELAY_MILLIS); // the initial hide is shorter, for better presentation
	}

	@Override
	public void onConfigurationChanged(@NonNull Configuration newConfig) {
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
		if (mTimingModeEnabled || mTimingPreviewEnabled) {
			showDiscardTimingEditsConfirmation(true);
			return;
		}

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
		if (mAllowTimingMode) {
			inflater.inflate(R.menu.edit_timing, menu);
		}
		inflater.inflate(R.menu.make_template, menu);
		inflater.inflate(R.menu.delete_narrative, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		handleNonPlaybackButtonClick(true); // pause all actions when leaving playback
		if (mNarrativeInternalId != null) {
			switch (item.getItemId()) {
				case android.R.id.home:
					onBackPressed(); // to make sure we handle mid-timing editor exit properly
					return true;

				case R.id.menu_make_template:
					runQueuedBackgroundTask(getNarrativeTemplateRunnable(mNarrativeInternalId, true));
					return true;

				case R.id.menu_edit_frame:
					if (mTimingModeEnabled || mTimingPreviewEnabled) {
						showDiscardTimingEditsConfirmation(false);
						return true;
					}

					final String currentFrameId = getCurrentFrameId();
					mSystemUiHider.show(); // need to show before the calculation for image size in frame editor

					final Intent frameEditorIntent = new Intent(PlaybackActivity.this, FrameEditorActivity.class);
					frameEditorIntent.putExtra(getString(R.string.extra_internal_id), currentFrameId);
					startActivityForResult(frameEditorIntent, MediaPhone.R_id_intent_frame_editor);

					// make sure we're not using any out of date images - done here so the ui will have updated before return
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

				case R.id.menu_edit_timing:
					if (mTimingModeEnabled || mTimingPreviewEnabled) {
						return true; // don't activate more than once
					}

					mSystemUiHider.show(); // don't auto-hide in edit mode - done first as playback refreshes its visibility

					setTimingEditorBannerContents(false);
					if (mTimingEditorBanner == null) {
						mTimingEditorBanner = findViewById(R.id.timing_editor_banner);
					}
					mTimingEditorBanner.setVisibility(View.VISIBLE);

					// we currently don't save recording state on rotation, so must disable
					UIUtilities.setScreenOrientationFixed(PlaybackActivity.this, true);

					mMediaController.seekTo(0);
					handleSeekEnd();
					mPlaybackController.setRecordingMode(true);

					// need to enable editor *after* resetting playback because playback is handled differently in edit mode
					mTimingModeEnabled = true;

					for (PlaybackMediaHolder holder : mNarrativeContent) {
						holder.removePlaybackOffsets(); // remove all fades/overlaps so we can more precisely edit timings
						if (MediaPhone.DEBUG) {
							Log.d(DebugUtilities.getLogTag(this), holder.toString());
						}
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
	public void onActivityResult(int requestCode, int resultCode, Intent resultIntent) {
		switch (requestCode) {
			case MediaPhone.R_id_intent_frame_editor:
				// make sure we reload playback when returning
				mNarrativeContent = null;
				refreshPlayback();
				handleNonPlaybackButtonClick(false);
				break;
			default:
				super.onActivityResult(requestCode, resultCode, resultIntent);
				break;
		}
	}

	@Override
	protected void loadPreferences(SharedPreferences mediaPhoneSettings) {
		// no normal preferences apply to this activity
	}

	@Override
	protected void configureInterfacePreferences(SharedPreferences mediaPhoneSettings) {
		mAllowTimingMode = mediaPhoneSettings.getBoolean(getString(R.string.key_timing_editor),
				getResources().getBoolean(R.bool.default_timing_editor));
		supportInvalidateOptionsMenu();
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		// we need to check whether we're in tests (currently just used for automatically capturing screenshots) and, if so,
		// disable automatic playback and UI hiding because the 50ms repeated Handler call causes a huge delay and makes
		// capturing the correct screenshot impossible
		handleNonPlaybackButtonClick(BuildConfig.IS_TESTING.get() || !hasFocus);
	}

	public void handleButtonClicks(View view) {

		switch (view.getId()) {
			case R.id.edit_mode_minimise:
				mPlaybackControlsWrapper.setVisibility(View.GONE);
				if (mTimingEditorMinimised == null) {
					mTimingEditorMinimised = findViewById(R.id.timing_editor_minimised);
				}
				mTimingEditorMinimised.setVisibility(View.VISIBLE);

				ProgressBar mRecordIndicator = findViewById(R.id.edit_mode_minimised_record);
				if (mRecordIndicator != null) {
					Drawable progressDrawable = mRecordIndicator.getIndeterminateDrawable().mutate();
					progressDrawable.setColorFilter(BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
							ContextCompat.getColor(PlaybackActivity.this, R.color.media_controller_recording),
							BlendModeCompat.SRC_IN));
					mRecordIndicator.setIndeterminateDrawable(progressDrawable);
				}
				break;

			case R.id.edit_mode_previous:
				mMediaController.seekButton(-1);
				break;

			case R.id.edit_mode_next:
				mMediaController.seekButton(1);
				break;

			case R.id.edit_mode_restore:
			case R.id.edit_mode_restore_button:
				mPlaybackControlsWrapper.setVisibility(View.VISIBLE);
				mTimingEditorMinimised.setVisibility(View.GONE);
				mMediaController.pause();
				mPlaybackController.refreshController();
				break;

			case R.id.edit_mode_reset_resume:
				if (!mTimingPreviewEnabled) {
					AlertDialog.Builder builder = new AlertDialog.Builder(PlaybackActivity.this);
					builder.setTitle(R.string.timing_editor_reset_all_confirmation);
					builder.setMessage(R.string.timing_editor_reset_all_hint);
					builder.setNegativeButton(R.string.button_cancel, null);
					builder.setPositiveButton(R.string.timing_editor_reset_all, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int whichButton) {
							// reset all (non-audio) media to default durations
							ContentResolver contentResolver = getContentResolver();
							for (PlaybackMediaHolder holder : mNarrativeContent) {
								if (holder.mMediaType != MediaPhoneProvider.TYPE_AUDIO) { // audio has its own inherent duration;
									// skip
									final MediaItem currentMediaItem = MediaManager.findMediaByInternalId(contentResolver,
											holder.mMediaItemId);
									currentMediaItem.setDurationMilliseconds(-1);
									MediaManager.updateMedia(contentResolver, currentMediaItem);
								}
							}
							exitTimingEditorMode();
							UIUtilities.showToast(PlaybackActivity.this, R.string.timing_editor_reset_all_completed);
						}
					});
					builder.create().show();
				} else {
					showDiscardTimingEditsConfirmation(false);
				}
				break;

			case R.id.edit_mode_preview_save:
				if (!mTimingPreviewEnabled) {
					mTimingPreviewEnabled = true;
					mTimingModeEnabled = false;
					mMediaController.pause();
					mPlaybackController.setRecordingMode(false);
					setTimingEditorBannerContents(true);

					mMediaController.seekTo(0);
					handleSeekEnd();
					mMediaController.play();
					mPlaybackController.refreshController();
				} else {
					// save updated media durations
					// TODO: somehow restore original timings of media that is currently playing? (to make small edits easier)
					ContentResolver contentResolver = getContentResolver();
					for (PlaybackMediaHolder holder : mNarrativeContent) {
						// shouldn't need to skip audio here (should not be possible to change its duration), but to be sure...
						if (holder.mMediaType != MediaPhoneProvider.TYPE_AUDIO && holder.hasChangedDuration() &&
								holder.mSpanningFrameIds.size() == 1) {
							final MediaItem currentMediaItem = MediaManager.findMediaByInternalId(getContentResolver(),
									holder.mMediaItemId);
							if (MediaPhone.DEBUG) {
								Log.d(DebugUtilities.getLogTag(this), "Updating duration of " + holder.mMediaItemId + " from " +
										currentMediaItem.getDurationMilliseconds() + " to " + holder.getDuration());
							}
							currentMediaItem.setDurationMilliseconds(holder.getDuration());
							MediaManager.updateMedia(contentResolver, currentMediaItem);
						}
					}
					exitTimingEditorMode();
					UIUtilities.showToast(PlaybackActivity.this, R.string.timing_editor_preview_completed);
				}
				break;
		}
	}

	private void showDiscardTimingEditsConfirmation(final boolean exitOnDiscard) {
		AlertDialog.Builder builder = new AlertDialog.Builder(PlaybackActivity.this);
		builder.setTitle(R.string.timing_editor_exit_discard_confirmation);
		builder.setMessage(R.string.timing_editor_exit_discard_hint);
		builder.setNegativeButton(R.string.timing_editor_exit_discard_resume, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				mTimingModeEnabled = true;
				mMediaController.pause();
				mPlaybackController.setRecordingMode(true);
				setTimingEditorBannerContents(false);

				if (mTimingPreviewEnabled) { // only reset to start if they weren't actively editing
					mMediaController.seekTo(0);
					handleSeekEnd();
				}
				mTimingPreviewEnabled = false;
			}
		});
		builder.setPositiveButton(R.string.timing_editor_exit_discard, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int whichButton) {
				exitTimingEditorMode();
				UIUtilities.showToast(PlaybackActivity.this, R.string.timing_editor_discard_completed);
				if (exitOnDiscard) {
					onBackPressed();
				}
			}
		});
		builder.create().show();
	}

	private void setTimingEditorBannerContents(boolean isPreviewMode) {
		if (mTimingEditorBannerHint == null) {
			mTimingEditorBannerHint = findViewById(R.id.edit_mode_hint);
		}
		if (mTimingEditorResetResumeButton == null) {
			mTimingEditorResetResumeButton = findViewById(R.id.edit_mode_reset_resume);
		}
		if (mTimingEditorPreviewSaveButton == null) {
			mTimingEditorPreviewSaveButton = findViewById(R.id.edit_mode_preview_save);
		}
		if (!isPreviewMode) {
			int buttonColour = getResources().getColor(R.color.media_controller_recording);
			String instruction = getString(R.string.timing_editor_instruction,
					getString(R.string.timing_editor_record_icon, String.format("#%06x", (0xffffff & buttonColour))),
					getString(R.string.timing_editor_ffwd_icon), getString(R.string.timing_editor_rew_icon));
			mTimingEditorBannerHint.setText(Html.fromHtml(instruction));
			mTimingEditorResetResumeButton.setText(R.string.timing_editor_reset_all);
			mTimingEditorPreviewSaveButton.setText(R.string.timing_editor_preview);
		} else {
			mTimingEditorBannerHint.setText(R.string.timing_editor_preview_confirmation);
			mTimingEditorResetResumeButton.setText(R.string.button_cancel);
			mTimingEditorPreviewSaveButton.setText(R.string.button_save);
		}
	}

	private void exitTimingEditorMode() {
		mTimingModeEnabled = false;
		mTimingPreviewEnabled = false;
		mMediaController.pause();
		mPlaybackController.setRecordingMode(false);
		mNarrativeContent = null; // offsets were removed when initiating timing mode; must reload media to reset them
		refreshPlayback();
		mMediaController.seekTo(0); // if they began playback mid-narrative, we will have reverted to there; skip to start
		handleSeekEnd();
		UIUtilities.setScreenOrientationFixed(PlaybackActivity.this, false);
		mTimingEditorBanner.setVisibility(View.GONE);
		delayedHide(AUTO_HIDE_DELAY_MILLIS);
	}

	/**
	 * When clicking a button that is not part of the playback interface, we need to pause playback (in case a new
	 * activity is launched, for example). We also temporarily stop hiding the playback bar. On the second call, we
	 * resume hiding (but not playback).
	 *
	 * @param pause true if playback should be paused and autohide disabled; false to resume autohide after a non-playback action
	 */
	private void handleNonPlaybackButtonClick(boolean pause) {
		if (pause) {
			mShouldAutoHide = false;
			if (mPlaying) {
				mMediaController.pause();
				mPlaybackController.refreshController();
			}
		} else {
			mShouldAutoHide = true;
			delayedHide(AUTO_HIDE_DELAY_MILLIS);
		}
	}

	private void setupUI() {

		// keep hold of key UI elements
		mPlaybackRoot = findViewById(R.id.playback_root);
		mPlaybackControlsWrapper = findViewById(R.id.playback_controls_wrapper);
		mCurrentPlaybackImage = findViewById(R.id.playback_image);
		mBackgroundPlaybackImage = findViewById(R.id.playback_image_background);
		mPlaybackText = findViewById(R.id.playback_text);
		mPlaybackTextWithImage = findViewById(R.id.playback_text_with_image);

		// hack to fix fullscreen margin layout issues (not enough indent), but cause another (too much indent), which is itself
		// fixed by showing/hiding the view at the start/end of the animation, above
		int textMargin = getResources().getDimensionPixelSize(R.dimen.playback_text_margin);
		UIUtilities.addFullscreenMarginsCorrectorListener(PlaybackActivity.this, R.id.playback_root,
				new UIUtilities.MarginCorrectorHolder[]{
						new UIUtilities.MarginCorrectorHolder(R.id.playback_controls_wrapper),
						new UIUtilities.MarginCorrectorHolder(R.id.playback_text_with_image, true, false, true, false,
								textMargin,
								textMargin, textMargin, textMargin),
						new UIUtilities.MarginCorrectorHolder(R.id.timing_editor_minimised)
				});

		// set up a SystemUiHider instance to control the system UI for this activity
		mSystemUiHider = new SystemUiHider(PlaybackActivity.this, mPlaybackRoot, SystemUiHider.FLAG_HIDE_NAVIGATION);
		mSystemUiHider.setup();
		mSystemUiHider.hide(); // TODO: this is a slightly hacky way to ensure the initial screen size doesn't jump on hide
		mSystemUiHider.show(); // (undo the above hide command so we still have controls visible on start
		mSystemUiHider.setOnVisibilityChangeListener(new SystemUiHider.OnVisibilityChangeListener() {
			int mShortAnimTime = getResources().getInteger(android.R.integer.config_shortAnimTime);
			int mControlsHeight;

			@Override
			public void onVisibilityChange(final boolean visible) {
				ActionBar actionBar = getSupportActionBar();
				if (actionBar != null) {
					if (visible) {
						actionBar.show();
					} else {
						actionBar.hide();
					}
				}

				// use ViewPropertyAnimator API to animate the playback controls at the bottom of the screen (sliding up or down)
				if (mControlsHeight == 0) {
					mControlsHeight = mPlaybackControlsWrapper.getHeight();
				}

				// cancel the previous animation, and also show/hide the entire view where possible to work around margin issues
				ViewPropertyAnimator animator = mPlaybackControlsWrapper.animate();
				animator.cancel();
				animator.translationY(visible ? 0 : mControlsHeight).setDuration(mShortAnimTime);
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
					if (visible) {
						animator.withStartAction(new Runnable() {
							@Override
							public void run() {
								mPlaybackControlsWrapper.setVisibility(View.VISIBLE);
							}
						});
					} else {
						animator.withEndAction(new Runnable() {
							@Override
							public void run() {
								mPlaybackControlsWrapper.setVisibility(View.GONE);
							}
						});
					}
				}

				// schedule the next hide
				if (visible) {
					delayedHide(AUTO_HIDE_DELAY_MILLIS);
				}
			}
		});

		// set up non-playback button clicks
		mPlaybackController = findViewById(R.id.playback_controller);
		mPlaybackController.setOnClickListener(null); // so clicking on the playback bar doesn't hide it
		mPlaybackController.setButtonListeners(null, new View.OnClickListener() {
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
		mFadeOutAnimationDurationAdjustment = (int) mFadeOutAnimation.getDuration() / 3;

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
				if (TOGGLE_HIDE_ON_CLICK && !mTimingModeEnabled) {
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
	private Handler mHideHandler = new Handler();
	private Runnable mHideRunnable = new Runnable() {
		@Override
		public void run() {
			if (mShouldAutoHide && !mTimingModeEnabled) {
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
	private Handler mMediaAdvanceHandler = new Handler();
	private Runnable mMediaAdvanceRunnable = new Runnable() {
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
		int delayMillis = Math.min(PLAYBACK_UPDATE_INTERVAL_MILLIS,
				mPlaybackDurationMilliseconds - mPlaybackPositionMilliseconds);

		// we limit the lower bound to 50ms because otherwise we'd overload the message queue and nothing would happen
		mMediaAdvanceHandler.removeCallbacks(mMediaAdvanceRunnable);
		mMediaAdvanceHandler.postDelayed(mMediaAdvanceRunnable, Math.max(delayMillis, 50));
	}

	// handler and runnable for scheduling loading the full quality image when seeking
	private Handler mImageLoadHandler = new Handler();
	private Runnable mImageLoadRunnable = new Runnable() {
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
		NarrativeItem currentNarrative = NarrativesManager.findNarrativeByInternalId(contentResolver, mNarrativeInternalId);
		PlaybackNarrativeDescriptor narrativeProperties = new PlaybackNarrativeDescriptor(mFadeOutAnimationDurationAdjustment);
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
				handleSeekEnd();
			}
		});
		mPlaybackController.refreshController();

		return true;
	}

	/**
	 * Refresh the current playback state, loading media where appropriate. Will call initialisePlayback() first if
	 * mNarrativeContent is null
	 *
	 * <b>Note:</b> this should never be called directly, except for in onCreate when first starting and in
	 * onWindowFocusChanged (a special case) - use delayedPlaybackAdvance() at all other times
	 */
	private void refreshPlayback() {
		refreshPlayback(false);
	}

	private void refreshPlayback(boolean timingModeSkipFrame) {
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

		if (mTimingModeEnabled && !timingModeSkipFrame) {
			// in timing mode, we stay on the current frame forever until the user decides to move on (or we are forced due to a
			// media item's duration); just advance playback time and return in most cases
			boolean advanceMedia = adjustMediaDurations(1);
			if (!advanceMedia) {
				delayedPlaybackAdvance();
				return;
			}
		}

		// we preload content to speed up transitions and more accurately keep playback time
		final int preCachedPlaybackTime = mPlaybackPositionMilliseconds + PRELOAD_SIZE;

		// remove any media that is now outdated (also stopping outdated audio in the process)
		boolean itemsRemoved;
		if (mNarrativeContentIndex == 0) { // if we're resetting, remove all items to preserve their order
			mCurrentPlaybackItems.clear();
			itemsRemoved = true;
		} else {
			mOldPlaybackItems.clear();
			for (PlaybackMediaHolder holder : mCurrentPlaybackItems) {
				if (holder.getStartTime(true) > preCachedPlaybackTime ||
						holder.getEndTime(true) <= mPlaybackPositionMilliseconds) {
					mOldPlaybackItems.add(holder);
					if (holder.mMediaType == MediaPhoneProvider.TYPE_AUDIO) {
						CustomMediaPlayer p = getExistingAudio(holder.mMediaPath);
						if (p != null) {
							try {
								p.stop();
							} catch (IllegalStateException ignored) {
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
			if (holder.getStartTime(true) <= preCachedPlaybackTime && holder.getEndTime(true) > mPlaybackPositionMilliseconds &&
					new File(holder.mMediaPath).length() > 0) {
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
			mMediaController.pause();
			mPlaybackController.refreshController();
			if (mShouldAutoHide && !mSystemUiHider.isVisible()) {
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
		// TODO: memory allocated to the existing drawable - do we need to get the drawables and recycle the bitmaps?
		// TODO: (if so, need to beware of recycling the audio image bitmap, or just check for isRecycled() on load)
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
								scaledBitmap = BitmapUtilities.loadAndCreateScaledBitmap(holder.mMediaPath, mScreenSize.x,
										mScreenSize.y, BitmapUtilities.ScalingLogic.DOWNSCALE, true);
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
								scaledBitmap = BitmapUtilities.loadAndCreateScaledBitmap(holder.mMediaPath, mScreenSize.x,
										mScreenSize.y, BitmapUtilities.ScalingLogic.FIT, true);
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

								playerInputStream = new FileInputStream(holder.mMediaPath);
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

				default:
					break;
			}
		}

		// no image content on this frame - remove
		if (!hasImage && !hasAudio) {
			mImageLoadHandler.removeCallbacks(mImageLoadRunnable);
			mCurrentPlaybackImage.setImageDrawable(null);
			mCurrentPlaybackImagePath = null; // the current image is highly likely to be wrong - force reload
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
					// this frame is text only, but may be a previously-loaded image in the background; force reload just in case
					mImageLoadHandler.removeCallbacks(mImageLoadRunnable);
					mCurrentPlaybackImage.setImageDrawable(null);
					mCurrentPlaybackImagePath = null;

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

	// edit timings: direction of -1 = shorten media to current mPlaybackPositionMilliseconds; +1 = lengthen media similarly
	private boolean adjustMediaDurations(int direction) {
		if (mPlaybackPositionMilliseconds == 0) {
			return false; // don't adjust durations when initialising (i.e., wait until the user starts recording)
		}

		String currentFrame = getCurrentFrameId();
		if (MediaPhone.DEBUG) {
			Log.v(DebugUtilities.getLogTag(this),
					"Adjusting frame time of " + currentFrame + " by " + direction + "; current time: " +
							mPlaybackPositionMilliseconds);
		}

		// get all media items that end at the current frame, and check for minimum durations (set by audio as we don't (yet)
		// allow cropping audio files) and maximum durations (set by spanning media)
		ArrayList<PlaybackMediaHolder> currentPlayingMedia = new ArrayList<>();
		ArrayList<PlaybackMediaHolder> backgroundPlayingMedia = new ArrayList<>();
		int minimumFrameEndTime = 0;
		int maximumFrameEndTime = 0;
		for (PlaybackMediaHolder holder : mNarrativeContent) {
			final String endFrame = holder.mSpanningFrameIds.get(holder.mSpanningFrameIds.size() - 1);
			if (endFrame.equals(currentFrame)) {
				if (holder.mMediaType == MediaPhoneProvider.TYPE_AUDIO) {
					minimumFrameEndTime = Math.max(minimumFrameEndTime, holder.getEndTime(false));
					if (holder.mSpanningFrameIds.size() > 1) {
						// for spanning audio, the media it spans must fit within its duration
						maximumFrameEndTime = Math.max(maximumFrameEndTime, holder.getEndTime(false));
					}
				}
				currentPlayingMedia.add(holder);
			}

			// need to check *all* media that might apply (not just audio that ends here), so that spanning media is preserved
			if (holder.mSpanningFrameIds.size() > 1 && holder.mSpanningFrameIds.contains(currentFrame) &&
					!currentPlayingMedia.contains(holder)) {
				if (holder.mMediaType == MediaPhoneProvider.TYPE_AUDIO) {
					maximumFrameEndTime = Math.max(maximumFrameEndTime, holder.getEndTime(false));
				} else if (!backgroundPlayingMedia.contains(holder)) {
					if (MediaPhone.DEBUG) {
						Log.d(DebugUtilities.getLogTag(this), "Adding missed background frame " + holder.mParentFrameId);
					}
					backgroundPlayingMedia.add(holder);
				}
			}
		}

		// the end time that can be set for this frame has both a minimum and a maximum as set by audio durations
		if (maximumFrameEndTime == 0) {
			maximumFrameEndTime = mPlaybackDurationMilliseconds + PLAYBACK_UPDATE_INTERVAL_MILLIS; // no audio limit = no maximum
		}
		// we ensure a minimum of 2 x PLAYBACK_UPDATE_INTERVAL_MILLIS (by adding PLAYBACK_UPDATE_INTERVAL_MILLIS to the already
		// accounted for normal playback advance) for frames that aren't length-restricted, which means we don't run into issues
		// where frame length is less than the playback interval, leading to brief and uneditable frame components
		int endTime = Math.min(Math.max(minimumFrameEndTime,
				mPlaybackPositionMilliseconds + PLAYBACK_UPDATE_INTERVAL_MILLIS + (direction < 0 ? 0 : 1)), maximumFrameEndTime);
		if (MediaPhone.DEBUG) {
			Log.d(DebugUtilities.getLogTag(this),
					"Timing adjustments set: end = " + endTime + "; min = " + minimumFrameEndTime + "; max = " +
							maximumFrameEndTime);
		}

		// adjust the currently playing media items to set the new end time (ignoring audio items)
		int offset = 0;
		boolean updateMediaOnReturn = false;
		for (PlaybackMediaHolder holder : currentPlayingMedia) {
			if (holder.mMediaType != MediaPhoneProvider.TYPE_AUDIO) {
				// calculate the offset for all based on the first available item
				// TODO: this could cause minor inconsistencies with app variants that allow specific durations to be set
				if (offset == 0) {
					offset = holder.getEndTime(false) - endTime;
				}
				if (MediaPhone.DEBUG) {
					Log.d(DebugUtilities.getLogTag(this),
							"Setting end time (" + holder.mParentFrameId + ") to " + endTime + "; offset: " + offset);
				}

				if (direction > 0 && Math.abs(mPlaybackPositionMilliseconds - endTime) < PLAYBACK_UPDATE_INTERVAL_MILLIS) {
					if (MediaPhone.DEBUG) {
						Log.d(DebugUtilities.getLogTag(this),
								"Found end of spanning media; forcing frame switch (current: " + mPlaybackPositionMilliseconds +
										"; end: " + endTime + ")");
					}
					// if we are trying to extend by a small amount (less than the interval) then it means we have found the end
					// of a spanning frame that is limited by an end time; in this case we should switch to the next frame after
					updateMediaOnReturn = true;
				}
				holder.setEndTime(endTime);
			}
		}

		// adjust subsequent frame timings to start where this frame left off
		for (PlaybackMediaHolder holder : mNarrativeContent) {
			final int holderStartTime = holder.getStartTime(false);
			final int holderEndTime = holder.getEndTime(false);
			if (holderStartTime >= maximumFrameEndTime) { // we can't extend (or shorten) beyond this audio-imposed limit
				break;
			}
			if (holderStartTime > mPlaybackPositionMilliseconds) {
				if (MediaPhone.DEBUG) {
					Log.d(DebugUtilities.getLogTag(this),
							"Updating subsequent media times (" + holder.mParentFrameId + "): " + holderStartTime + " to " +
									(holderStartTime - offset) + ", offset: " + offset);
				}
				if (holderStartTime - offset >= maximumFrameEndTime) {
					// TODO: we did try to give these frames a minimum duration here, but it ended up being too confusing in the
					// TODO: interface - for now we have a reset button to restore the original frame/media timings
					holder.setStartTime(maximumFrameEndTime);
				} else {
					holder.setStartTime((holderStartTime - offset));
				}

				if (holderEndTime == maximumFrameEndTime || holderEndTime - offset > maximumFrameEndTime) {
					holder.setEndTime(maximumFrameEndTime);
				} else {
					holder.setEndTime((holderEndTime - offset));
				}
			}
		}

		for (PlaybackMediaHolder holder : backgroundPlayingMedia) {
			// this media is spanning over the current frame - it should be extended to the maximum possible element time
			String finalFrame = holder.mSpanningFrameIds.get(holder.mSpanningFrameIds.size() - 1);
			int maxMediaEnd = 0;
			for (PlaybackMediaHolder subFrame : mNarrativeContent) {
				if (subFrame.mParentFrameId.equals(finalFrame)) {
					maxMediaEnd = Math.max(subFrame.getEndTime(false), maxMediaEnd);
				}
			}
			if (MediaPhone.DEBUG) {
				Log.d(DebugUtilities.getLogTag(this),
						"Setting end of spanning media from " + holder.mParentFrameId + " to " + maxMediaEnd);
			}
			holder.setEndTime(maxMediaEnd);
		}

		// if shortening, we are now at this position in playback
		if (direction < 0) {
			mPlaybackPositionMilliseconds = endTime;
		}

		// update time-to-frame map, duration and controller - note that this approach will mean that if we have multiple frames
		// with effectively a zero duration, starting at the same time, then only one will appear in the map
		String comparisonFrame = null;
		mTimeToFrameMap.clear();
		mPlaybackDurationMilliseconds = 0;
		for (PlaybackMediaHolder holder : mNarrativeContent) {
			if (!holder.mParentFrameId.equals(comparisonFrame)) {
				comparisonFrame = holder.mParentFrameId;
				mTimeToFrameMap.put(holder.getStartTime(false), comparisonFrame);
			}
			mPlaybackDurationMilliseconds = Math.max(mPlaybackDurationMilliseconds, holder.getEndTime(false));
		}
		mPlaybackController.refreshController(); // TODO: dragging while recording works, but could be confusing - disable it?

		// we've switched frames since the last call - make sure we update all media (which may be missed if durations changed)
		if (mPreviousTimingModeFrame != null && !currentFrame.equals(mPreviousTimingModeFrame)) {
			updateMediaOnReturn = true;
		}

		if (MediaPhone.DEBUG) {
			for (PlaybackMediaHolder holder : mNarrativeContent) {
				Log.d(DebugUtilities.getLogTag(this), holder.toString());
			}
			for (LinkedHashMap.Entry<Integer, String> entry : mTimeToFrameMap.entrySet()) {
				Log.d(DebugUtilities.getLogTag(this), "time: " + entry.getKey() + "; frame: " + entry.getValue());
			}

		}
		Log.v(DebugUtilities.getLogTag(this),
				"Timing adjustment completed: current time = " + mPlaybackPositionMilliseconds + "; duration = " +
						mPlaybackDurationMilliseconds + "; update media = " + updateMediaOnReturn);

		mPreviousTimingModeFrame = currentFrame;
		return updateMediaOnReturn;
	}

	private void swapBackgroundImage() {
		// if the image hasn't yet loaded, it's likely we're playing a narrative with really short frames - to try to
		// keep up with playback, cancel loading and just show a downscaled version; queuing the full-sized version
		if (mBackgroundPlaybackImage.getTag() != null) { // presence of a tag indicates that an image is being loaded
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
		// TODO: 1) if we've seeked, and swap from a null image before loading, this looks bad; probably a non-problem...
		// TODO: 2) when swapping between very short frames, faster devices end up loading the subsequent image before the fade
		// TODO: completes (i.e., images 1, 2, 3 end up being 1, 3 (briefly), 2, 3) - currently this is fixed by cancelling
		// TODO: all animations as part of loadScreenSizedImageInBackground, and not starting animations if the most recent one
		// TODO: was within the animation duration, which mostly fixes this but still leaves a small flicker on the first swap...
		// TODO: is there a better way?
		if (System.currentTimeMillis() - mLastFadeOutAnimationTime > mFadeOutAnimation.getDuration()) {
			mBackgroundPlaybackImage.startAnimation(mFadeOutAnimation);
		}
		mLastFadeOutAnimationTime = System.currentTimeMillis();
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
							if (player.mMediaStartTime <= mPlaybackPositionMilliseconds &&
									player.mMediaEndTime > mPlaybackPositionMilliseconds) {
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
			if (player.mMediaStartTime <= mPlaybackPositionMilliseconds && player.mMediaEndTime > mPlaybackPositionMilliseconds) {
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
			if (MediaPhone.DEBUG) {
				Log.d(DebugUtilities.getLogTag(this), "Playback error - what: " + what + ", extra: " + extra);
			}
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
	private static class CustomMediaPlayer extends MediaPlayer {
		private boolean mPlaybackPrepared = false;
		private boolean mHasPlayed = false;
		private String mMediaPath = null;
		private int mMediaStartTime = 0;
		private int mMediaEndTime = 0;

		private void resetCustomAttributes() {
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
			mPreviousTimingModeFrame = null; // we don't want multiple instances of editing to compare with previous frames
			if (mPlaybackPositionMilliseconds >= mPlaybackDurationMilliseconds) {
				// if we've reached the end of playback (or timing editing), start again from the beginning
				seekTo(0);
				handleSeekEnd();
			}
			mPlaying = true;
			playPreparedAudio(true);
			delayedPlaybackAdvance();

			if (mTimingModeEnabled) {
				if (mTimingEditorMinimiseButton == null) {
					mTimingEditorMinimiseButton = findViewById(R.id.edit_mode_minimise);
				}
				mTimingEditorMinimiseButton.setVisibility(View.VISIBLE);
			}
		}

		@Override
		public void pause() {
			// TODO: stop sending handler messages? (rather than just not updating) - need to consider seeking if so
			mPlaying = false;
			pauseAudio();
			if (mTimingModeEnabled) {
				mPlaybackControlsWrapper.setVisibility(View.VISIBLE);
				if (mTimingEditorMinimised != null) {
					mTimingEditorMinimised.setVisibility(View.GONE);
				}
				if (mTimingEditorMinimiseButton != null) {
					mTimingEditorMinimiseButton.setVisibility(View.GONE);
				}
			}
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
		public void seekButton(int direction) {
			if (direction == 0) {
				return;
			}

			// we are editing timings - if moving forward, shorten the current frame
			if (mTimingModeEnabled && direction > 0 && mPlaying) {
				adjustMediaDurations(-1); // shorten the current frame's duration
				mPlaybackPositionMilliseconds -= 1; // so we just skip to the next frame (below), rather than by two frames
			}

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

			seekTo(direction < 0 ? previousFrameTime : nextFrameTime);
			handleSeekEnd();
		}

		@Override
		public void seekTo(int pos) {
			// seekTo is used internally in the controller to handle dragging the progress bar - because of this, it simply
			// seeks, and doesn't handle forcing final media changes between frames until afterwards (in handleSeekEnd) -
			// as a result, if calling seekTo, you must also call handleSeekEnd to make sure media is updated properly
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
			refreshPlayback(mTimingModeEnabled);
		}

		@Override
		public boolean isPlaying() {
			return mPlaying;
		}
	};

	private void handleSeekEnd() {
		// when a drag/seek ends we need to make sure we don't continue reloading any cached images
		cancelLoadingScreenSizedImageInBackground(mCurrentPlaybackImage);
		mImageLoadHandler.removeCallbacks(mImageLoadRunnable);
		resetImagePaths(); // the current and previous cached images are highly likely to be wrong - reload

		// force a reload of playback content - like in seekTo itself, this is inefficient, but probably not worth working around
		mNarrativeContentIndex = 0;

		// schedule playback to continue (we preview content when skipping, so need to restore original state)
		if (mPlaying) {
			playPreparedAudio(true);
		} else {
			mMediaController.pause();
		}

		mMediaAdvanceHandler.removeCallbacks(mMediaAdvanceRunnable);
		refreshPlayback(mTimingModeEnabled);
	}
}
