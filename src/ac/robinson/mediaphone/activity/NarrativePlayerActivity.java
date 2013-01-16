/*
 *  Copyright (C) 2012 Simon Robinson
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

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;

import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediaphone.MediaPhoneActivity;
import ac.robinson.mediaphone.R;
import ac.robinson.mediaphone.provider.FrameItem;
import ac.robinson.mediaphone.provider.FramesManager;
import ac.robinson.mediaphone.provider.MediaPhoneProvider;
import ac.robinson.mediaphone.provider.NarrativeItem;
import ac.robinson.mediaphone.provider.NarrativesManager;
import ac.robinson.mediautilities.FrameMediaContainer;
import ac.robinson.util.BitmapUtilities;
import ac.robinson.util.DebugUtilities;
import ac.robinson.util.IOUtilities;
import ac.robinson.util.UIUtilities;
import ac.robinson.view.AutoResizeTextView;
import ac.robinson.view.CustomMediaController;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.SoundPool;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.RelativeLayout;

import com.larvalabs.svgandroid.SVGParser;

public class NarrativePlayerActivity extends MediaPhoneActivity {

	private final int EXTRA_AUDIO_ITEMS = 2; // 3 audio items max, but only 2 for sound pool (other is in MediaPlayer)
	private SoundPool mSoundPool;
	private ArrayList<Integer> mFrameSounds;
	private int mNumExtraSounds;
	private boolean mMediaPlayerPrepared;
	private boolean mSoundPoolPrepared;
	private AssetFileDescriptor mSilenceFileDescriptor = null;
	private boolean mSilenceFilePlaying;
	private long mPlaybackStartTime;

	private MediaPlayer mMediaPlayer;
	private boolean mMediaPlayerError;
	private boolean mHasPlayed;
	private boolean mIsLoading;
	private CustomMediaController mMediaController;
	private ArrayList<FrameMediaContainer> mNarrativeContentList;
	private int mNarrativeDuration;
	private int mPlaybackPosition;
	private int mInitialPlaybackOffset;
	private int mNonAudioOffset;
	private FrameMediaContainer mCurrentFrameContainer;
	private Bitmap mAudioPictureBitmap = null;

	private boolean mShowBackButton = false; // loaded from preferences on startup

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		UIUtilities.configureActionBar(this, true, true, R.string.title_playback, 0);
		UIUtilities.actionBarVisibility(this, false);
		setContentView(R.layout.narrative_player);

		mIsLoading = false;
		mMediaPlayerError = false;

		// load previous state on screen rotation
		mHasPlayed = false; // will begin playing if not playing already; used to stop unplayable narratives
		mPlaybackPosition = -1;
		if (savedInstanceState != null) {
			// mIsPlaying = savedInstanceState.getBoolean(getString(R.string.extra_is_playing));
			mPlaybackPosition = savedInstanceState.getInt(getString(R.string.extra_playback_position));
			mInitialPlaybackOffset = savedInstanceState.getInt(getString(R.string.extra_playback_offset));
			mNonAudioOffset = savedInstanceState.getInt(getString(R.string.extra_playback_non_audio_offset));
		}
	}

	@Override
	public void onSaveInstanceState(Bundle savedInstanceState) {
		// savedInstanceState.putBoolean(getString(R.string.extra_is_playing), mIsPlaying);
		savedInstanceState.putInt(getString(R.string.extra_playback_position), mPlaybackPosition);
		savedInstanceState.putInt(getString(R.string.extra_playback_offset),
				mMediaPlayerController.getCurrentPosition() - mPlaybackPosition);
		savedInstanceState.putInt(getString(R.string.extra_playback_non_audio_offset), mNonAudioOffset);
		super.onSaveInstanceState(savedInstanceState);
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus && !mHasPlayed) {
			preparePlayback();
		} else {
			showMediaController(CustomMediaController.DEFAULT_VISIBILITY_TIMEOUT);
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		pauseMediaController();
	}

	@Override
	protected void onDestroy() {
		releasePlayer();
		super.onDestroy();
	}

	@Override
	public void onBackPressed() {
		if (mCurrentFrameContainer != null) {
			saveLastEditedFrame(mCurrentFrameContainer.mFrameId);
		}
		finish();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// TODO: if we couldn't open a temporary directory then exporting won't work
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.export_narrative, menu);
		inflater.inflate(R.menu.make_template, menu);
		inflater.inflate(R.menu.delete_narrative, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		final int itemId = item.getItemId();
		switch (itemId) {
			case R.id.menu_make_template:
				pauseMediaController();
				FrameItem templateFrame = FramesManager.findFrameByInternalId(getContentResolver(),
						mCurrentFrameContainer.mFrameId);
				runBackgroundTask(getNarrativeTemplateRunnable(templateFrame.getParentId(),
						MediaPhoneProvider.getNewInternalId(), true)); // don't need the id
				// TODO: do we need to keep the screen on?
				return true;

			case R.id.menu_delete_narrative:
				pauseMediaController();
				deleteNarrativeDialog(mCurrentFrameContainer.mFrameId);
				return true;

			case R.id.menu_export_narrative:
				exportNarrative();
				return true;

			case R.id.menu_preferences:
				pauseMediaController(); // intentionally not returning
			default:
				return super.onOptionsItemSelected(item);
		}
	}

	@Override
	protected void loadPreferences(SharedPreferences mediaPhoneSettings) {
		// the soft back button (necessary in some circumstances)
		mShowBackButton = Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB
				&& mediaPhoneSettings.getBoolean(getString(R.string.key_show_back_button),
						getResources().getBoolean(R.bool.default_show_back_button));
		setMediaControllerListeners();
	}

	private void preparePlayback() {
		if (mNarrativeContentList != null && mNarrativeContentList.size() > 0 && mMediaPlayer != null
				&& mSoundPool != null && mMediaController != null && mPlaybackPosition >= 0) {
			return; // no need to re-initialise
		}

		// need the parent id
		final Intent intent = getIntent();
		if (intent == null) {
			UIUtilities.showToast(NarrativePlayerActivity.this, R.string.error_loading_narrative_player);
			onBackPressed();
			return;
		}
		String startFrameId = intent.getStringExtra(getString(R.string.extra_internal_id));

		// TODO: lazily load (either via AsyncTask/Thread, or ImageCache for low-quality versions, later replaced)
		ContentResolver contentResolver = getContentResolver();
		FrameItem currentFrame = FramesManager.findFrameByInternalId(contentResolver, startFrameId);
		NarrativeItem currentNarrative = NarrativesManager.findNarrativeByInternalId(contentResolver,
				currentFrame.getParentId());
		mNarrativeContentList = currentNarrative.getContentList(contentResolver);

		// first launch
		boolean updatePosition = mPlaybackPosition < 0;
		if (updatePosition) {
			mInitialPlaybackOffset = 0;
			mNonAudioOffset = 0;
		}
		mNarrativeDuration = 0;
		for (FrameMediaContainer container : mNarrativeContentList) {
			if (updatePosition && startFrameId.equals(container.mFrameId)) {
				updatePosition = false;
				mPlaybackPosition = mNarrativeDuration;
			}
			mNarrativeDuration += container.mFrameMaxDuration;
		}
		if (mPlaybackPosition < 0) {
			mPlaybackPosition = 0;
			mInitialPlaybackOffset = 0;
			mNonAudioOffset = 0;
		}

		mCurrentFrameContainer = getMediaContainer(mPlaybackPosition, true);

		releasePlayer();
		mMediaPlayer = new MediaPlayer();
		mSoundPool = new SoundPool(EXTRA_AUDIO_ITEMS, AudioManager.STREAM_MUSIC, 100);
		mFrameSounds = new ArrayList<Integer>();

		mMediaController = new CustomMediaController(this);
		setMediaControllerListeners();

		RelativeLayout parentLayout = (RelativeLayout) findViewById(R.id.narrative_playback_container);
		RelativeLayout.LayoutParams controllerLayout = new RelativeLayout.LayoutParams(
				RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
		controllerLayout.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
		controllerLayout.setMargins(0, 0, 0, getResources().getDimensionPixelSize(R.dimen.button_padding));
		parentLayout.addView(mMediaController, controllerLayout);
		mMediaController.setAnchorView(findViewById(R.id.image_playback));
		showMediaController(CustomMediaController.DEFAULT_VISIBILITY_TIMEOUT); // (can use 0 for permanent visibility)

		mHasPlayed = true;
		prepareMediaItems(mCurrentFrameContainer);
	}

	public void handleButtonClicks(View currentButton) {
		showMediaController(CustomMediaController.DEFAULT_VISIBILITY_TIMEOUT);
	}

	private void showMediaController(int timeout) {
		if (mMediaController != null) {
			// make sure the text view is visible above the playback bar
			Resources res = getResources();
			int mediaControllerHeight = res.getDimensionPixelSize(R.dimen.media_controller_height);
			boolean hasImage = mCurrentFrameContainer.mImagePath != null;
			AutoResizeTextView textView = (AutoResizeTextView) findViewById(R.id.text_playback);
			if (textView.getVisibility() == View.VISIBLE) {
				if (hasImage) {
					RelativeLayout.LayoutParams textLayout = (RelativeLayout.LayoutParams) textView.getLayoutParams();
					textLayout.setMargins(0, 0, 0, mediaControllerHeight);
				} else {
					int textPadding = res.getDimensionPixelSize(R.dimen.playback_text_padding);
					textView.setPadding(textPadding, textPadding, textPadding, mediaControllerHeight);
				}
			}

			if (!mMediaController.isShowing() || timeout <= 0) {
				mMediaController.show(timeout);
			} else {
				mMediaController.refreshShowTimeout();
			}
		}
	}

	private void pauseMediaController() {
		mMediaPlayerController.pause();
		showMediaController(-1); // to keep on showing until done here
		UIUtilities.releaseKeepScreenOn(getWindow());
	}

	private void setMediaControllerListeners() {
		if (mMediaController != null) {
			mMediaController.setPrevNextListeners(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					exportNarrative();
				}
			}, mShowBackButton ? new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					onBackPressed();
				}
			} : null);
		}
	}

	private void exportNarrative() {
		pauseMediaController(); // will also show the controller if applicable
		if (!canSendNarratives()) {
			UIUtilities.showToast(NarrativePlayerActivity.this, R.string.export_potential_problem);
		}
		FrameItem exportFrame = FramesManager.findFrameByInternalId(getContentResolver(),
				mCurrentFrameContainer.mFrameId);
		if (exportFrame != null) {
			// released this when pausing; important to keep awake to export because we only have one chance to display
			// the export options after creating mov or smil file (will be cancelled on screen unlock; Android is weird)
			// TODO: move to a better (e.g. notification bar) method of exporting?
			UIUtilities.acquireKeepScreenOn(getWindow());
			exportContent(exportFrame.getParentId(), false);
		}
	}

	private FrameMediaContainer getMediaContainer(int narrativePlaybackPosition, boolean updatePlaybackPosition) {
		mIsLoading = true;
		int currentPosition = 0;
		for (FrameMediaContainer container : mNarrativeContentList) {
			int newPosition = currentPosition + container.mFrameMaxDuration;
			if (narrativePlaybackPosition >= currentPosition && narrativePlaybackPosition < newPosition) {
				if (updatePlaybackPosition) {
					mPlaybackPosition = currentPosition;
				}
				return container;
			}
			currentPosition = newPosition;
		}
		return null;
	}

	private void prepareMediaItems(FrameMediaContainer container) {
		// load the audio for the media player
		Resources res = getResources();
		mSoundPoolPrepared = false;
		mMediaPlayerPrepared = false;
		mMediaPlayerError = false;
		mNonAudioOffset = 0;
		unloadSoundPool();
		mSoundPool.setOnLoadCompleteListener(mSoundPoolLoadListener);
		mNumExtraSounds = 0;
		String currentAudioItem = null;
		boolean soundPoolAllowed = !DebugUtilities.hasSoundPoolBug();
		for (int i = 0, n = container.mAudioDurations.size(); i < n; i++) {
			if (container.mAudioDurations.get(i).intValue() == container.mFrameMaxDuration) {
				currentAudioItem = container.mAudioPaths.get(i);
			} else {
				// playing *anything* in SoundPool at the same time as MediaPlayer crashes on Galaxy Tab
				if (soundPoolAllowed) {
					mSoundPool.load(container.mAudioPaths.get(i), 1);
					mNumExtraSounds += 1;
				}
			}
		}
		if (mNumExtraSounds == 0) {
			mSoundPoolPrepared = true;
		}

		FileInputStream playerInputStream = null;
		mSilenceFilePlaying = false;
		try {
			mMediaPlayer.reset();
			mMediaPlayer.setLooping(false);
			if (currentAudioItem == null || (!(new File(currentAudioItem).exists()))) {
				mSilenceFilePlaying = true;
				if (mSilenceFileDescriptor == null) {
					mSilenceFileDescriptor = res.openRawResourceFd(R.raw.silence_100ms);
				}
				mMediaPlayer.setDataSource(mSilenceFileDescriptor.getFileDescriptor(),
						mSilenceFileDescriptor.getStartOffset(), mSilenceFileDescriptor.getDeclaredLength());
			} else {
				// can't play from data directory (they're private; permissions don't work), must use an input stream
				// mMediaPlayer.setDataSource(currentAudioItem);
				playerInputStream = new FileInputStream(new File(currentAudioItem));
				mMediaPlayer.setDataSource(playerInputStream.getFD());
			}
			mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
			mMediaPlayer.setOnPreparedListener(mMediaPlayerPreparedListener);
			// mMediaPlayer.setOnCompletionListener(mMediaPlayerCompletionListener); // now done later - better pausing
			mMediaPlayer.setOnErrorListener(mMediaPlayerErrorListener);
			mMediaPlayer.prepareAsync();
		} catch (Throwable t) {
			UIUtilities.showToast(NarrativePlayerActivity.this, R.string.error_loading_narrative_player);
			onBackPressed();
			return;
		} finally {
			IOUtilities.closeStream(playerInputStream);
		}

		// load the image
		ImageView photoDisplay = (ImageView) findViewById(R.id.image_playback);
		if (container.mImagePath != null && new File(container.mImagePath).exists()) {
			Bitmap scaledBitmap = BitmapUtilities.loadAndCreateScaledBitmap(container.mImagePath,
					photoDisplay.getWidth(), photoDisplay.getHeight(), BitmapUtilities.ScalingLogic.FIT, true);
			photoDisplay.setImageBitmap(scaledBitmap);
			photoDisplay.setPadding(0, 0, 0, 0);
			photoDisplay.setScaleType(ScaleType.CENTER_INSIDE);
		} else if (TextUtils.isEmpty(container.mTextContent)) {
			if (mAudioPictureBitmap == null) {
				mAudioPictureBitmap = SVGParser.getSVGFromResource(res, R.raw.ic_audio_playback).getBitmap(
						photoDisplay.getWidth(), photoDisplay.getHeight());
			}
			photoDisplay.setImageBitmap(mAudioPictureBitmap);
			photoDisplay.setPadding(0, 0, 0,
					(mMediaController.isShowing() ? res.getDimensionPixelSize(R.dimen.media_controller_height) : 0));
			photoDisplay.setScaleType(ScaleType.FIT_CENTER);
		} else {
			photoDisplay.setImageDrawable(null);
			photoDisplay.setBackgroundColor(res.getColor(R.color.export_background));
		}

		// load the text
		AutoResizeTextView textView = (AutoResizeTextView) findViewById(R.id.text_playback);
		if (!TextUtils.isEmpty(container.mTextContent)) {
			textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimensionPixelSize(R.dimen.playback_text));
			textView.setText(container.mTextContent);
			RelativeLayout.LayoutParams textLayout = new RelativeLayout.LayoutParams(
					RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
			textLayout.addRule(RelativeLayout.CENTER_HORIZONTAL);
			int textViewHeight = res.getDimensionPixelSize(R.dimen.media_controller_height);
			int textViewPadding = res.getDimensionPixelSize(R.dimen.playback_text_padding);
			if (container.mImagePath != null) {
				textView.setMaxHeight(res.getDimensionPixelSize(R.dimen.playback_maximum_text_height_with_image));
				textLayout.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
				textView.setPadding(textViewPadding, textViewPadding, textViewPadding, textViewPadding);
				textView.setBackgroundResource(R.drawable.rounded_playback_text);
				textView.setTextColor(res.getColor(R.color.export_text_with_image));
			} else {
				textView.setMaxHeight(photoDisplay.getHeight()); // no way to clear, so set to parent height
				textLayout.addRule(RelativeLayout.CENTER_VERTICAL);
				textView.setPadding(textViewPadding, textViewPadding, textViewPadding,
						(mMediaController.isShowing() ? textViewHeight : textViewPadding));
				textView.setBackgroundColor(res.getColor(android.R.color.transparent));
				textView.setTextColor(res.getColor(R.color.export_text_no_image));
			}
			if (mMediaController.isShowing()) {
				textLayout.setMargins(0, 0, 0, textViewHeight);
			} else {
				textLayout.setMargins(0, 0, 0, textViewPadding);
			}
			textView.setLayoutParams(textLayout);
			textView.setVisibility(View.VISIBLE);
		} else {
			textView.setVisibility(View.GONE);
		}
	}

	private void unloadSoundPool() {
		for (Integer soundId : mFrameSounds) {
			mSoundPool.stop(soundId);
			mSoundPool.unload(soundId);
		}
		mFrameSounds.clear();
	}

	private void releasePlayer() {
		UIUtilities.releaseKeepScreenOn(getWindow());
		// release controller first, so we don't play to a null player
		if (mMediaController != null) {
			mMediaController.hide();
			((RelativeLayout) findViewById(R.id.narrative_playback_container)).removeView(mMediaController);
			mMediaController.setMediaPlayer(null);
			mMediaController = null;
		}
		if (mMediaPlayer != null) {
			try {
				mMediaPlayer.stop();
			} catch (IllegalStateException e) {
			}
			mMediaPlayer.release();
			mMediaPlayer = null;
		}
		if (mSoundPool != null) {
			unloadSoundPool();
			mSoundPool.release();
			mSoundPool = null;
		}
	}

	private CustomMediaController.MediaPlayerControl mMediaPlayerController = new CustomMediaController.MediaPlayerControl() {
		@Override
		public void start() {
			// so we return to the start when playing from the end
			if (mPlaybackPosition < 0) {
				mPlaybackPosition = 0;
				mInitialPlaybackOffset = 0;
				mNonAudioOffset = 0;
				mCurrentFrameContainer = getMediaContainer(mPlaybackPosition, true);
				prepareMediaItems(mCurrentFrameContainer);
			} else {
				mMediaPlayer.setOnCompletionListener(mMediaPlayerCompletionListener);
				mPlaybackStartTime = System.currentTimeMillis() - mMediaPlayer.getCurrentPosition();
				mMediaPlayer.start();
				mSoundPool.autoResume(); // TODO: check this works
				showMediaController(CustomMediaController.DEFAULT_VISIBILITY_TIMEOUT);
			}
			UIUtilities.acquireKeepScreenOn(getWindow());
		}

		@Override
		public void pause() {
			mIsLoading = false;
			mMediaPlayer.setOnCompletionListener(null); // make sure we don't continue accidentally
			mMediaPlayer.pause();
			mSoundPool.autoPause(); // TODO: check this works
			UIUtilities.releaseKeepScreenOn(getWindow());
		}

		@Override
		public int getDuration() {
			return mNarrativeDuration;
		}

		@Override
		public int getCurrentPosition() {
			if (mPlaybackPosition < 0) {
				return mNarrativeDuration;
			} else {
				return mPlaybackPosition
						+ mNonAudioOffset
						+ (mSilenceFilePlaying ? (int) (System.currentTimeMillis() - mPlaybackStartTime) : mMediaPlayer
								.getCurrentPosition());
			}
		}

		@Override
		public void seekTo(int pos) {
			// TODO: seek others (is it even possible with soundpool?)
			int actualPos = pos - mPlaybackPosition;
			if (mPlaybackPosition < 0) { // so we allow seeking from the end
				mPlaybackPosition = mNarrativeDuration - mCurrentFrameContainer.mFrameMaxDuration;
			}
			if (actualPos >= 0 && actualPos < mCurrentFrameContainer.mFrameMaxDuration) {
				if (mIsLoading
						|| (actualPos < mMediaPlayer.getDuration() && mCurrentFrameContainer.mAudioPaths.size() > 0)) {
					if (!mIsLoading) {
						if (mMediaController.isDragging()) {
							mMediaPlayer.setOnCompletionListener(mMediaPlayerCompletionListener);
						}
						mPlaybackStartTime = System.currentTimeMillis() - actualPos;
						mMediaPlayer.seekTo(actualPos);
						if (!mMediaPlayer.isPlaying()) { // we started from the end
							mMediaPlayer.start();
							UIUtilities.acquireKeepScreenOn(getWindow());
						}
					} else {
						// still loading - come here so we don't reload the same item again
					}
				} else {
					// for image- or text-only frames
					mNonAudioOffset = actualPos;
					if (mMediaController.isDragging()) {
						mMediaPlayer.setOnCompletionListener(mMediaPlayerCompletionListener);
					}
					mPlaybackStartTime = System.currentTimeMillis();
					mMediaPlayer.seekTo(0);
					mMediaPlayer.start();
					mMediaController.setProgress();
				}
			} else if (pos >= 0 && pos < mNarrativeDuration) {
				FrameMediaContainer newContainer = getMediaContainer(pos, true);
				if (newContainer != mCurrentFrameContainer) {
					mCurrentFrameContainer = newContainer;
					prepareMediaItems(mCurrentFrameContainer);
					mInitialPlaybackOffset = pos - mPlaybackPosition;
				} else {
					mIsLoading = false;
				}
			}
		}

		@Override
		public boolean isPlaying() {
			return mMediaPlayer.isPlaying();
		}

		@Override
		public boolean isLoading() {
			return mIsLoading;
		}

		@Override
		public int getBufferPercentage() {
			return 0;
		}

		@Override
		public boolean canPause() {
			return true;
		}

		@Override
		public boolean canSeekBackward() {
			return true;
		}

		@Override
		public boolean canSeekForward() {
			return true;
		}

		@Override
		public void onControllerVisibilityChange(boolean visible) {
			if (visible) {
				UIUtilities.setNonFullScreen(getWindow());
			} else {
				UIUtilities.setFullScreen(getWindow());
			}
		}
	};

	private void startPlayers() {
		// so that we don't start playing after pause if we were loading
		if (mIsLoading) {
			for (Integer soundId : mFrameSounds) {
				mSoundPool.play(soundId, 1, 1, 1, 0, 1f); // volume is % of *current*, rather than maximum
				// TODO: seek to mInitialPlaybackOffset
			}

			mMediaPlayer.setOnCompletionListener(mMediaPlayerCompletionListener);
			mPlaybackStartTime = System.currentTimeMillis() - mInitialPlaybackOffset;
			mMediaPlayer.seekTo(mInitialPlaybackOffset);
			mMediaPlayer.start();

			mIsLoading = false;
			mMediaController.setMediaPlayer(mMediaPlayerController);

			UIUtilities.acquireKeepScreenOn(getWindow());
		}
	}

	private SoundPool.OnLoadCompleteListener mSoundPoolLoadListener = new SoundPool.OnLoadCompleteListener() {
		@Override
		public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
			mFrameSounds.add(sampleId);
			if (mFrameSounds.size() >= mNumExtraSounds) {
				mSoundPoolPrepared = true;
			}
			if (mSoundPoolPrepared && mMediaPlayerPrepared) {
				startPlayers();
			}
		}
	};

	private OnPreparedListener mMediaPlayerPreparedListener = new OnPreparedListener() {
		@Override
		public void onPrepared(MediaPlayer mp) {
			mMediaPlayerPrepared = true;
			if (mSoundPoolPrepared) {
				startPlayers();
			}
		}
	};

	private OnCompletionListener mMediaPlayerCompletionListener = new OnCompletionListener() {
		@Override
		public void onCompletion(MediaPlayer mp) {
			if (mMediaPlayerError) {
				// releasePlayer(); // don't do this, as it means the player will be null; instead we resume from errors
				mCurrentFrameContainer = getMediaContainer(mPlaybackPosition, false);
				prepareMediaItems(mCurrentFrameContainer);
				mMediaPlayerError = false;
				return;
			}
			mInitialPlaybackOffset = 0;
			int currentPosition = mMediaPlayerController.getCurrentPosition()
					+ (mMediaPlayer.getDuration() - mMediaPlayer.getCurrentPosition()) + 1;
			if (currentPosition < mNarrativeDuration) {
				mMediaPlayerController.seekTo(currentPosition);
			} else if (!mMediaController.isDragging()) {
				// move to just before the end (accounting for mNarrativeDuration errors)
				mMediaPlayerController.seekTo(currentPosition - 2);
				pauseMediaController(); // will also show the controller if applicable
				mPlaybackPosition = -1; // so we start from the beginning
			}
		}
	};

	private OnErrorListener mMediaPlayerErrorListener = new OnErrorListener() {
		@Override
		public boolean onError(MediaPlayer mp, int what, int extra) {
			mMediaPlayerError = true;
			// UIUtilities.showToast(NarrativePlayerActivity.this, R.string.error_loading_narrative_player);
			if (MediaPhone.DEBUG)
				Log.d(DebugUtilities.getLogTag(this), "Playback error Ð what: " + what + ", extra: " + extra);
			return false; // not handled -> onCompletionListener will be called
		}
	};
}
