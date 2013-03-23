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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediaphone.MediaPhoneActivity;
import ac.robinson.mediaphone.R;
import ac.robinson.mediaphone.provider.MediaItem;
import ac.robinson.mediaphone.provider.MediaManager;
import ac.robinson.mediaphone.provider.MediaPhoneProvider;
import ac.robinson.mediaphone.view.VUMeter;
import ac.robinson.mediautilities.MediaUtilities;
import ac.robinson.util.AndroidUtilities;
import ac.robinson.util.DebugUtilities;
import ac.robinson.util.IOUtilities;
import ac.robinson.util.StringUtilities;
import ac.robinson.util.UIUtilities;
import ac.robinson.view.CenteredImageTextButton;
import ac.robinson.view.CustomMediaController;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.MediaRecorder;
import android.media.MediaRecorder.OnErrorListener;
import android.media.MediaRecorder.OnInfoListener;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.AutoCloseInputStream;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.ringdroid.soundfile.CheapSoundFile;

public class AudioActivity extends MediaPhoneActivity {

	private String mMediaItemInternalId;
	private boolean mHasEditedMedia = false;
	private boolean mShowOptionsMenu = false;
	private boolean mSwitchedFrames = false;

	private boolean mRecordingIsAllowed; // TODO: currently extension-based, but we can't actually process all AAC files
	private boolean mDoesNotHaveMicrophone;
	private PathAndStateSavingMediaRecorder mMediaRecorder;
	private MediaPlayer mMediaPlayer;
	private NoSwipeCustomMediaController mMediaController;
	private TextView mRecordingDurationText;
	private Handler mTextUpdateHandler = new TextUpdateHandler();
	private ScheduledThreadPoolExecutor mAudioTextScheduler;
	private boolean mAudioRecordingInProgress = false;
	private long mTimeRecordingStarted = 0;
	private long mAudioDuration = 0;
	private Handler mButtonIconBlinkHandler = new ButtonIconBlinkHandler();
	private ScheduledThreadPoolExecutor mButtonIconBlinkScheduler;
	private int mNextBlinkMode = R.id.msg_blink_icon_off;

	private Handler mSwipeEnablerHandler = new SwipeEnablerHandler();
	private boolean mSwitchingFrames;
	private int mSwitchFrameDirection;
	private boolean mSwitchFrameShowOptionsMenu;
	private boolean mContinueRecordingAfterSplit;

	// loaded properly from preferences on initialisation
	private boolean mAddToMediaLibrary = false;
	private int mAudioBitrate = 8000;

	private enum DisplayMode {
		PLAY_AUDIO, RECORD_AUDIO
	};

	private enum AfterRecordingMode {
		DO_NOTHING, SWITCH_TO_PLAYBACK, SPLIT_FRAME, SWITCH_FRAME
	};

	private DisplayMode mDisplayMode;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			setTheme(R.style.default_light_theme); // light looks *much* better beyond honeycomb
		}
		UIUtilities.configureActionBar(this, true, true, R.string.title_frame_editor, R.string.title_audio);
		setContentView(R.layout.audio_view);

		// so that the volume controls always control media volume (rather than ringtone etc.)
		setVolumeControlStream(AudioManager.STREAM_MUSIC);

		mDoesNotHaveMicrophone = !getPackageManager().hasSystemFeature("android.hardware.microphone");

		mRecordingDurationText = ((TextView) findViewById(R.id.audio_recording_progress));
		mDisplayMode = DisplayMode.PLAY_AUDIO;
		mMediaItemInternalId = null;
		mShowOptionsMenu = false;
		mSwitchedFrames = false;

		// load previous id on screen rotation
		if (savedInstanceState != null) {
			mMediaItemInternalId = savedInstanceState.getString(getString(R.string.extra_internal_id));
			mHasEditedMedia = savedInstanceState.getBoolean(getString(R.string.extra_media_edited), true);
			mSwitchedFrames = savedInstanceState.getBoolean(getString(R.string.extra_switched_frames));
			if (mHasEditedMedia) {
				setBackButtonIcons(AudioActivity.this, R.id.button_finished_audio, R.id.button_cancel_recording, true);
			}
		}

		// load the media itself
		loadMediaContainer();
	}

	@Override
	public void onSaveInstanceState(Bundle savedInstanceState) {
		// no need to save display mode as we don't allow rotation when actually recording
		savedInstanceState.putString(getString(R.string.extra_internal_id), mMediaItemInternalId);
		savedInstanceState.putBoolean(getString(R.string.extra_media_edited), mHasEditedMedia);
		savedInstanceState.putBoolean(getString(R.string.extra_switched_frames), mSwitchedFrames);
		super.onSaveInstanceState(savedInstanceState);
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) {
			if (mShowOptionsMenu) {
				mShowOptionsMenu = false;
				openOptionsMenu();
			}
			registerForSwipeEvents(); // here to avoid crashing due to double-swiping
		}
	}

	// @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) is for callOnClick()
	@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1)
	@Override
	protected void onPause() {
		if (!mAudioRecordingInProgress && mMediaPlayer != null && mMediaPlayer.isPlaying()) {
			// call the click method so we update the interface - hacky but it works
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
				findViewById(R.id.pause).callOnClick();
			} else {
				findViewById(R.id.pause).performClick();
			}
		}
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		releaseAll();
		super.onDestroy();
	}

	@Override
	public void onBackPressed() {
		// managed to press back before loading the media - wait
		if (mMediaItemInternalId == null) {
			return;
		}

		ContentResolver contentResolver = getContentResolver();
		final MediaItem audioMediaItem = MediaManager.findMediaByInternalId(contentResolver, mMediaItemInternalId);
		if (audioMediaItem != null) {
			switch (mDisplayMode) {
				case PLAY_AUDIO:
					// deleted the picture (media item already set deleted) - update the icon
					if (mHasEditedMedia) {
						runBackgroundTask(getFrameIconUpdaterRunnable(audioMediaItem.getParentId()));
					}
					break;

				case RECORD_AUDIO:
					if (mAudioRecordingInProgress) {
						stopRecording(AfterRecordingMode.SWITCH_TO_PLAYBACK); // switch to playback afterwards
						return;
					} else {
						// play if they have recorded, exit otherwise
						if (audioMediaItem.getFile().length() > 0) {
							// recorded new audio (rather than just cancelling the recording) - update the icon
							if (mHasEditedMedia) {
								runBackgroundTask(getFrameIconUpdaterRunnable(audioMediaItem.getParentId()));
								setBackButtonIcons(AudioActivity.this, R.id.button_finished_audio,
										R.id.button_cancel_recording, true);

								// if we do this then we can't tell whether to change icons on screen rotation; disabled
								// mHasEditedMedia = false; // we've saved the icon, so are no longer in edit mode
							}

							// show hint after recording, but not if switching frames (will get shown over frame editor)
							switchToPlayback(!mSwitchingFrames);
							return;

						} else {
							// so we don't leave an empty stub
							audioMediaItem.setDeleted(true);
							MediaManager.updateMedia(contentResolver, audioMediaItem);
						}
					}
					break;
			}

			saveLastEditedFrame(audioMediaItem != null ? audioMediaItem.getParentId() : null);
		}

		if (mSwitchingFrames) { // so the parent exits too (or we end up with multiple copies of the frame editor)
			setResult(mHasEditedMedia ? R.id.result_audio_ok_exit : R.id.result_audio_cancelled_exit);
		} else {
			setResult(mHasEditedMedia ? Activity.RESULT_OK : Activity.RESULT_CANCELED);
		}
		super.onBackPressed();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		setupMenuNavigationButtonsFromMedia(inflater, menu, getContentResolver(), mMediaItemInternalId, mHasEditedMedia);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		final int itemId = item.getItemId();
		switch (itemId) {
			case R.id.menu_previous_frame:
			case R.id.menu_next_frame:
				performSwitchFrames(itemId, true);
				return true;

			case R.id.menu_add_frame:
				if (mAudioRecordingInProgress) {
					findViewById(R.id.button_record_audio).setEnabled(false);
					mContinueRecordingAfterSplit = true;
					stopRecording(AfterRecordingMode.SPLIT_FRAME);
				} else {
					MediaItem audioMediaItem = MediaManager.findMediaByInternalId(getContentResolver(),
							mMediaItemInternalId);
					if (audioMediaItem != null && audioMediaItem.getFile().length() > 0) {
						mContinueRecordingAfterSplit = false;
						runBackgroundTask(getFrameSplitterRunnable(mMediaItemInternalId));
					} else {
						UIUtilities.showToast(AudioActivity.this, R.string.split_audio_add_content);
					}
				}
				return true;

			case R.id.menu_back_without_editing:
			case R.id.menu_finished_editing:
				onBackPressed();
				return true;

			default:
				return super.onOptionsItemSelected(item);
		}
	}

	@Override
	protected void loadPreferences(SharedPreferences mediaPhoneSettings) {
		Resources res = getResources();

		// whether to add recorded audio to the device's media library
		mAddToMediaLibrary = mediaPhoneSettings.getBoolean(getString(R.string.key_audio_to_media),
				res.getBoolean(R.bool.default_audio_to_media));

		// preferred audio bit rate
		int newBitrate = res.getInteger(R.integer.default_audio_bitrate);
		try {
			String requestedBitrateString = mediaPhoneSettings.getString(getString(R.string.key_audio_bitrate), null);
			newBitrate = Integer.valueOf(requestedBitrateString);
		} catch (Exception e) {
			newBitrate = res.getInteger(R.integer.default_audio_bitrate);
		}

		// need to update the media recorder if we change the bit rate from this activity
		if (newBitrate != mAudioBitrate) {
			mAudioBitrate = newBitrate;
			if (mDisplayMode == DisplayMode.RECORD_AUDIO && !mAudioRecordingInProgress) {
				MediaItem audioMediaItem = MediaManager.findMediaByInternalId(getContentResolver(),
						mMediaItemInternalId);
				if (audioMediaItem != null) { // not hugely important - new bit rate will apply next time
					if (!initialiseAudioRecording(audioMediaItem.getFile())) {
						UIUtilities.showToast(AudioActivity.this, R.string.error_loading_audio_editor);
						onBackPressed();
					}
				}
			}
		}
	}

	@Override
	protected void configureInterfacePreferences(SharedPreferences mediaPhoneSettings) {
		// the soft done/back button
		int newVisibility = View.VISIBLE;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB
				|| !mediaPhoneSettings.getBoolean(getString(R.string.key_show_back_button),
						getResources().getBoolean(R.bool.default_show_back_button))) {
			newVisibility = View.GONE;
		}
		findViewById(R.id.button_finished_audio).setVisibility(newVisibility);
		findViewById(R.id.button_cancel_recording).setVisibility(newVisibility);
	}

	private void loadMediaContainer() {

		// first launch
		ContentResolver contentResolver = getContentResolver();
		boolean firstLaunch = mMediaItemInternalId == null;
		if (firstLaunch) {

			// editing an existing frame
			String parentInternalId = null;
			String mediaInternalId = null;
			final Intent intent = getIntent();
			if (intent != null) {
				parentInternalId = intent.getStringExtra(getString(R.string.extra_parent_id));
				mediaInternalId = intent.getStringExtra(getString(R.string.extra_internal_id));
				mShowOptionsMenu = intent.getBooleanExtra(getString(R.string.extra_show_options_menu), false);
				mSwitchedFrames = intent.getBooleanExtra(getString(R.string.extra_switched_frames), false);
				if (mSwitchedFrames) {
					firstLaunch = false; // so we don't show hints
				}
			}
			if (parentInternalId == null) {
				UIUtilities.showToast(AudioActivity.this, R.string.error_loading_audio_editor);
				mMediaItemInternalId = "-1"; // so we exit
				onBackPressed();
				return;
			}

			// add a new media item if it doesn't already exist - unlike others we need to pass the id as an extra here
			if (mediaInternalId == null) {
				MediaItem audioMediaItem = new MediaItem(parentInternalId, MediaPhone.EXTENSION_AUDIO_FILE,
						MediaPhoneProvider.TYPE_AUDIO);
				mMediaItemInternalId = audioMediaItem.getInternalId();
				MediaManager.addMedia(contentResolver, audioMediaItem);
			} else {
				mMediaItemInternalId = mediaInternalId;
			}
		}

		// load the existing audio
		final MediaItem audioMediaItem = MediaManager.findMediaByInternalId(contentResolver, mMediaItemInternalId);
		if (audioMediaItem != null) {
			mRecordingIsAllowed = true;
			final File currentFile = audioMediaItem.getFile();
			if (currentFile.length() > 0) {
				mRecordingIsAllowed = recordingIsAllowed(currentFile);
				mAudioDuration = audioMediaItem.getDurationMilliseconds();
				switchToPlayback(firstLaunch);
			} else {
				if (mDoesNotHaveMicrophone && firstLaunch) {
					UIUtilities.showToast(AudioActivity.this, R.string.error_recording_audio_no_microphone, true);
				}
				switchToRecording(currentFile);
			}
		} else {
			UIUtilities.showToast(AudioActivity.this, R.string.error_loading_audio_editor);
			onBackPressed();
		}
	}

	private void releaseAll() {
		if (mAudioRecordingInProgress) {
			stopRecording(AfterRecordingMode.DO_NOTHING);
		}
		stopButtonIconBlinkScheduler();
		stopTextScheduler();
		releasePlayer();
		releaseRecorder();
	}

	private void releaseRecorder() {
		UIUtilities.releaseKeepScreenOn(getWindow());
		stopButtonIconBlinkScheduler();
		if (mMediaRecorder != null) {
			try {
				mMediaRecorder.stop();
			} catch (Throwable t) {
			}
			mMediaRecorder.release();
		}
		mMediaRecorder = null;
	}

	private boolean recordingIsAllowed(File currentFile) {
		String currentFileExtension = IOUtilities.getFileExtension(currentFile.getAbsolutePath());
		if (!AndroidUtilities.arrayContains(MediaPhone.EDITABLE_AUDIO_EXTENSIONS, currentFileExtension)) {
			return false;
		}
		if (DebugUtilities.supportsAMRAudioRecordingOnly()
				&& !AndroidUtilities.arrayContains(MediaUtilities.AMR_FILE_EXTENSIONS, currentFileExtension)) {
			return false;
		}
		return true;
	}

	private void switchToRecording(File currentFile) {
		if (!mRecordingIsAllowed) { // can only edit m4a
			// could switch to import automatically here, but it's probably confusing UI-wise to do that
			UIUtilities.showToast(AudioActivity.this, R.string.retake_audio_forbidden, true);
			return;
		}

		releasePlayer();
		releaseRecorder();

		mDisplayMode = DisplayMode.RECORD_AUDIO;

		// can't record without a microphone present - import only (notification has already been shown)
		if (mDoesNotHaveMicrophone) {
			importAudio();
			return;
		}

		// disable screen rotation and screen sleeping while in the recorder
		UIUtilities.setScreenOrientationFixed(this, true);

		mTimeRecordingStarted = System.currentTimeMillis(); // hack - make sure scheduled updates are correct
		updateAudioRecordingText(mAudioDuration);

		mMediaRecorder = new PathAndStateSavingMediaRecorder();

		// always record into a temporary file, then combine later
		if (initialiseAudioRecording(currentFile)) {
			findViewById(R.id.audio_preview_container).setVisibility(View.GONE);
			findViewById(R.id.audio_preview_controls).setVisibility(View.GONE);
			findViewById(R.id.audio_recording).setVisibility(View.VISIBLE);
			findViewById(R.id.audio_recording_controls).setVisibility(View.VISIBLE);
		}
	}

	// @TargetApi(Build.VERSION_CODES.GINGERBREAD_MR1) is for AAC/HE_AAC audio recording
	@TargetApi(Build.VERSION_CODES.GINGERBREAD_MR1)
	private boolean initialiseAudioRecording(File currentFile) {

		// where to record the new audio
		File parentDirectory = currentFile.getParentFile();

		// the devices that only support AMR also seem to have a bug where reset() doesn't work - need to re-create
		boolean amrOnly = DebugUtilities.supportsAMRAudioRecordingOnly();
		if (amrOnly) {
			releaseRecorder(); // do here so we don't release the blink scheduler
			mMediaRecorder = new PathAndStateSavingMediaRecorder();
		}

		// we must always record at the same sample rate within a single file - read the existing file to check
		int enforcedSampleRate = -1;
		boolean isAMR = false;
		if (currentFile.length() > 0) {
			// blink the button as a hint that we can continue recording
			mButtonIconBlinkScheduler = new ScheduledThreadPoolExecutor(2);
			scheduleNextButtonIconBlinkUpdate(0);

			// won't get to init if < GINGERBREAD_MR1 or AMR-only and it is an M4A file (can't record in this situation)
			String currentFileExtension = IOUtilities.getFileExtension(currentFile.getAbsolutePath());
			isAMR = AndroidUtilities.arrayContains(MediaUtilities.AMR_FILE_EXTENSIONS, currentFileExtension);

			// we only support one AMR recording/editing rate, so no need to get sample rate for AMR files
			if (!isAMR) {
				try {
					CheapSoundFile existingFile = CheapSoundFile.create(currentFile.getAbsolutePath(), true, null);
					enforcedSampleRate = existingFile.getSampleRate();
				} catch (Exception e) {
					enforcedSampleRate = -1;
				}
			}
		}

		mMediaRecorder.reset();
		mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
		mMediaRecorder.setAudioChannels(1); // 2 channels breaks recording TODO: only for amr?

		// prefer mpeg4
		if (!amrOnly && !isAMR) {
			mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
		} else {
			mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
		}
		mMediaRecorder.setOutputFile(new File(parentDirectory, MediaPhoneProvider.getNewInternalId() + "."
				+ MediaPhone.EXTENSION_AUDIO_FILE).getAbsolutePath()); // MediaPhone automatically sets to amr or m4a

		// prefer AAC - see: http://developer.android.com/guide/appendix/media-formats.html
		if (!amrOnly && !isAMR) {
			mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); // because HE_AAC doesn't export properly
			mMediaRecorder.setAudioEncodingBitRate(96000); // hardcoded so we don't accidentally change via globals
			if (enforcedSampleRate > 0) {
				mMediaRecorder.setAudioSamplingRate(enforcedSampleRate);
			} else {
				mMediaRecorder.setAudioSamplingRate(mAudioBitrate);
			}
		} else {
			// AMR-NB encoder seems to *only* accept 12200/8000 - hardcoded so we don't accidentally change via globals
			mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
			mMediaRecorder.setAudioEncodingBitRate(12200);
			mMediaRecorder.setAudioSamplingRate(8000);
		}

		try {
			mMediaRecorder.prepare();
			return true;
		} catch (Throwable t) {
			releaseRecorder();
			UIUtilities.showToast(AudioActivity.this, R.string.error_loading_audio_editor);
			onBackPressed();
		}
		return false;
	}

	private void startRecording() {
		mHasEditedMedia = true;
		mAudioRecordingInProgress = true;
		UIUtilities.acquireKeepScreenOn(getWindow());
		stopButtonIconBlinkScheduler();
		mAudioTextScheduler = new ScheduledThreadPoolExecutor(2);

		mMediaRecorder.setOnErrorListener(new OnErrorListener() {
			@Override
			public void onError(MediaRecorder mr, int what, int extra) {
				UIUtilities.showToast(AudioActivity.this, R.string.error_recording_audio);
				if (MediaPhone.DEBUG)
					Log.d(DebugUtilities.getLogTag(this), "Recording error - what: " + what + ", extra: " + extra);
				stopRecordingTrackers();
				resetRecordingInterface();
			}
		});
		mMediaRecorder.setOnInfoListener(new OnInfoListener() {
			@Override
			public void onInfo(MediaRecorder mr, int what, int extra) {
				// if (MediaPhone.DEBUG)
				// Log.d(MediaPhone.getLogTag(this), "Recording info - what: " + what + ", extra: " + extra);
			}
		});

		try {
			mMediaRecorder.start();
			setBackButtonIcons(AudioActivity.this, R.id.button_finished_audio, R.id.button_cancel_recording, true);
		} catch (Throwable t) {
			UIUtilities.showToast(AudioActivity.this, R.string.error_recording_audio);
			if (MediaPhone.DEBUG)
				Log.d(DebugUtilities.getLogTag(this), "Recording error: " + t.getLocalizedMessage());
			stopRecordingTrackers();
			resetRecordingInterface();
			return;
		}

		mTimeRecordingStarted = System.currentTimeMillis();
		VUMeter vumeter = ((VUMeter) findViewById(R.id.vu_meter));
		vumeter.setRecorder(mMediaRecorder, vumeter.new RecordingStartedListener() {
			@Override
			public void recordingStarted() {
				scheduleNextAudioTextUpdate(getResources().getInteger(R.integer.audio_timer_update_interval));
				CenteredImageTextButton recordButton = (CenteredImageTextButton) findViewById(R.id.button_record_audio);
				recordButton.setEnabled(true);
				recordButton.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_pause, 0, 0);
			}
		});
	}

	private void stopRecordingTrackers() {
		stopTextScheduler();
		((VUMeter) findViewById(R.id.vu_meter)).setRecorder(null, null);
	}

	private void resetRecordingInterface() {
		mAudioRecordingInProgress = false;
		CenteredImageTextButton recordButton = (CenteredImageTextButton) findViewById(R.id.button_record_audio);
		recordButton.setEnabled(true);
		recordButton.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_record, 0, 0);
		UIUtilities.releaseKeepScreenOn(getWindow());
	}

	private void stopRecording(final AfterRecordingMode afterRecordingMode) {
		stopRecordingTrackers();

		long audioDuration = System.currentTimeMillis() - mTimeRecordingStarted;
		try {
			if (mMediaRecorder.isRecording()) {
				mMediaRecorder.stop();
			}
		} catch (IllegalStateException e) { // not actually recording
			UIUtilities.showToast(AudioActivity.this, R.string.error_recording_audio);
			updateAudioRecordingText(0);
			return;
		} catch (RuntimeException e) { // no audio data received - pressed start/stop too quickly
			UIUtilities.showToast(AudioActivity.this, R.string.error_recording_audio);
			updateAudioRecordingText(0);
			return;
		} catch (Throwable t) {
			UIUtilities.showToast(AudioActivity.this, R.string.error_recording_audio);
			updateAudioRecordingText(0);
			return;
		} finally {
			resetRecordingInterface();
		}

		mAudioDuration += audioDuration;
		updateAudioRecordingText(mAudioDuration);

		final File newAudioFile = new File(mMediaRecorder.getOutputFile());
		ContentResolver contentResolver = getContentResolver();
		MediaItem audioMediaItem = MediaManager.findMediaByInternalId(contentResolver, mMediaItemInternalId);
		if (audioMediaItem == null) {
			UIUtilities.showToast(AudioActivity.this, R.string.error_recording_audio);
			if (MediaPhone.DEBUG)
				Log.d(DebugUtilities.getLogTag(this), "Recording error: couldn't load media item");
			return; // can't save if we can't find the media item
		}

		// check we recorded in an editable file (no longer really necessary, but handles, e.g replacing mp3 with m4a)
		final File currentFile = audioMediaItem.getFile();
		mRecordingIsAllowed = recordingIsAllowed(currentFile);

		if (currentFile.length() <= 0) {

			// this is the first audio recording, so just update the duration
			newAudioFile.renameTo(currentFile);
			int preciseDuration = IOUtilities.getAudioFileLength(currentFile);
			if (preciseDuration > 0) {
				audioMediaItem.setDurationMilliseconds(preciseDuration);
			} else {
				// just in case (will break playback due to inaccuracy)
				if (MediaPhone.DEBUG)
					Log.d(DebugUtilities.getLogTag(this), "Warning: setting approximate audio duration");
				audioMediaItem.setDurationMilliseconds((int) audioDuration);
			}
			MediaManager.updateMedia(contentResolver, audioMediaItem);

			// prepare to continue recording - done after creating the file so we can examine its bitrate
			initialiseAudioRecording(currentFile);

			if (mAddToMediaLibrary) {
				runBackgroundTask(getMediaLibraryAdderRunnable(currentFile.getAbsolutePath(),
						Environment.DIRECTORY_MUSIC));
			}

			if (afterRecordingMode == AfterRecordingMode.SWITCH_TO_PLAYBACK) {
				onBackPressed();
				return;
			} else if (afterRecordingMode == AfterRecordingMode.SPLIT_FRAME) {
				runBackgroundTask(getFrameSplitterRunnable(mMediaItemInternalId));
				return;
			} else if (afterRecordingMode == AfterRecordingMode.SWITCH_FRAME) {
				completeSwitchFrames();
				return;
			} else if (afterRecordingMode == AfterRecordingMode.DO_NOTHING && !mRecordingIsAllowed) {
				onBackPressed();
				return;
			}

		} else {
			// prepare to continue recording
			initialiseAudioRecording(currentFile);

			// a progress listener for the audio merging task (debugging only)
			final CheapSoundFile.ProgressListener loadProgressListener;
			if (MediaPhone.DEBUG) {
				loadProgressListener = new CheapSoundFile.ProgressListener() {
					public boolean reportProgress(double fractionComplete) {
						Log.d(DebugUtilities.getLogTag(this), "Loading progress: " + fractionComplete);
						return true;
					}
				};
			} else {
				loadProgressListener = null;
			}

			// combine the two recordings in a new background task
			runBackgroundTask(new BackgroundRunnable() {
				@Override
				public int getTaskId() {
					if (afterRecordingMode == AfterRecordingMode.SWITCH_TO_PLAYBACK) {
						return Math.abs(R.id.audio_switch_to_playback_task_complete); // positive to show dialog
					} else if (afterRecordingMode == AfterRecordingMode.SPLIT_FRAME) {
						return Math.abs(R.id.split_frame_task_complete); // positive to show dialog
					} else if (afterRecordingMode == AfterRecordingMode.SWITCH_FRAME) {
						return Math.abs(R.id.audio_switch_frame_task_complete); // positive to show dialog
					} else if (afterRecordingMode == AfterRecordingMode.DO_NOTHING && !mRecordingIsAllowed) {
						return Math.abs(R.id.audio_switch_to_playback_task_complete); // positive to show dialog
					} else {
						return -1; // negative for no dialog
					}
				}

				@Override
				public void run() {
					// join the audio files
					try {
						// so we can write directly to the media file
						// TODO: this is only necessary because we write the entire file - could have an alternative
						// method that only writes the new data (and the header/atoms for m4a - remember can be at end)
						File tempOriginalInput = new File(currentFile.getAbsolutePath() + "-temp."
								+ MediaPhone.EXTENSION_AUDIO_FILE);
						IOUtilities.copyFile(currentFile, tempOriginalInput);

						// load the files to be combined
						CheapSoundFile firstSoundFile = CheapSoundFile.create(tempOriginalInput.getAbsolutePath(),
								loadProgressListener);
						CheapSoundFile secondSoundFile = CheapSoundFile.create(newAudioFile.getAbsolutePath(),
								loadProgressListener);

						if (firstSoundFile != null && secondSoundFile != null) {

							// combine the audio and delete temporary files
							long newDuration = firstSoundFile.addSoundFile(secondSoundFile);
							firstSoundFile.writeFile(currentFile, 0, firstSoundFile.getNumFrames());
							tempOriginalInput.delete();
							newAudioFile.delete();

							ContentResolver contentResolver = getContentResolver();
							MediaItem newAudioMediaItem = MediaManager.findMediaByInternalId(contentResolver,
									mMediaItemInternalId);
							newAudioMediaItem.setDurationMilliseconds((int) newDuration);
							if (mAddToMediaLibrary) {
								runBackgroundTask(getMediaLibraryAdderRunnable(newAudioMediaItem.getFile()
										.getAbsolutePath(), Environment.DIRECTORY_MUSIC));
							}
							MediaManager.updateMedia(contentResolver, newAudioMediaItem);
						}
					} catch (FileNotFoundException e) {
						if (MediaPhone.DEBUG)
							Log.d(DebugUtilities.getLogTag(this), "Append audio: file not found");
					} catch (IOException e) {
						if (MediaPhone.DEBUG)
							Log.d(DebugUtilities.getLogTag(this), "Append audio: IOException");
					} catch (Throwable t) {
						if (MediaPhone.DEBUG)
							Log.d(DebugUtilities.getLogTag(this), "Append audio: Throwable");
					}

					// split the frame if necessary
					if (afterRecordingMode == AfterRecordingMode.SPLIT_FRAME) {
						getFrameSplitterRunnable(mMediaItemInternalId).run();
					}
				}
			});
		}
	}

	private void importAudio() {
		releaseAll(); // so we're not locking the file we want to copy to
		Intent intent = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI);
		try {
			startActivityForResult(intent, R.id.intent_audio_import);
		} catch (ActivityNotFoundException e) {
			UIUtilities.showToast(AudioActivity.this, R.string.import_audio_unavailable);
			if (mDoesNotHaveMicrophone) {
				onBackPressed(); // we can't do anything else here
			} else {
				MediaItem audioMediaItem = MediaManager.findMediaByInternalId(getContentResolver(),
						mMediaItemInternalId);
				if (audioMediaItem != null) {
					switchToRecording(audioMediaItem.getFile()); // released recorder, so switch back
				}
			}
		}
	}

	@Override
	protected void onBackgroundTaskProgressUpdate(int taskId) {
		taskId = Math.abs(taskId);
		if (taskId == Math.abs(R.id.audio_switch_to_playback_task_complete)) {
			onBackPressed();
		} else if (taskId == Math.abs(R.id.split_frame_task_complete)) {
			// reset when split frame task has finished
			mHasEditedMedia = false;
			setBackButtonIcons(AudioActivity.this, R.id.button_finished_audio, R.id.button_cancel_recording, false);
			mAudioDuration = 0;
			updateAudioRecordingText(0);

			// new frames have no content, so make sure to start in recording mode
			if (mDisplayMode != DisplayMode.RECORD_AUDIO) {
				MediaItem audioMediaItem = MediaManager.findMediaByInternalId(getContentResolver(),
						mMediaItemInternalId);
				if (audioMediaItem != null) {
					switchToRecording(audioMediaItem.getFile());
				}
			}

			// resume recording state if necessary
			if (mContinueRecordingAfterSplit) {
				mContinueRecordingAfterSplit = false;
				startRecording();
			}
		} else if (taskId == Math.abs(R.id.audio_switch_frame_task_complete)) {
			completeSwitchFrames();
		} else if (taskId == Math.abs(R.id.import_external_media_succeeded)) {
			mHasEditedMedia = true; // to force an icon update
			MediaItem audioMediaItem = MediaManager.findMediaByInternalId(getContentResolver(), mMediaItemInternalId);
			if (audioMediaItem != null) {
				mRecordingIsAllowed = recordingIsAllowed(audioMediaItem.getFile());
				mAudioDuration = audioMediaItem.getDurationMilliseconds();
			}
			onBackPressed(); // to start playback
		} else if (taskId == Math.abs(R.id.import_external_media_failed)
				|| taskId == Math.abs(R.id.import_external_media_cancelled)) {
			if (taskId == Math.abs(R.id.import_external_media_failed)) {
				UIUtilities.showToast(AudioActivity.this, R.string.import_audio_failed);
			}
			if (mDoesNotHaveMicrophone) {
				onBackPressed(); // we can't do anything else here
			} else {
				MediaItem audioMediaItem = MediaManager.findMediaByInternalId(getContentResolver(),
						mMediaItemInternalId);
				if (audioMediaItem != null) {
					switchToRecording(audioMediaItem.getFile()); // released recorder, so switch back
				}
			}
		}

		// *must* be after other tasks (so that we start recording before hiding the dialog)
		super.onBackgroundTaskProgressUpdate(taskId);
	}

	private boolean performSwitchFrames(int itemId, boolean showOptionsMenu) {
		if (mMediaItemInternalId != null) {
			mSwitchingFrames = true;
			if (mAudioRecordingInProgress) {
				// TODO: do we need to disable the menu buttons here to prevent double pressing?
				findViewById(R.id.button_record_audio).setEnabled(false);
				mContinueRecordingAfterSplit = true;

				mSwitchFrameDirection = itemId;
				mSwitchFrameShowOptionsMenu = showOptionsMenu;

				stopRecording(AfterRecordingMode.SWITCH_FRAME);
				return true;
			} else {
				MediaItem audioMediaItem = MediaManager.findMediaByInternalId(getContentResolver(),
						mMediaItemInternalId);
				if (audioMediaItem != null) {
					if (mDisplayMode == DisplayMode.RECORD_AUDIO && audioMediaItem.getFile().length() > 0) {
						onBackPressed(); // need to exit from recording mode when not recording but no player present
					}
					return switchFrames(audioMediaItem.getParentId(), itemId, R.string.extra_internal_id,
							showOptionsMenu, FrameEditorActivity.class);
				}
			}
		}
		return false;
	}

	private void completeSwitchFrames() {
		performSwitchFrames(mSwitchFrameDirection, mSwitchFrameShowOptionsMenu);
	}

	@Override
	protected boolean swipeNext() {
		return performSwitchFrames(R.id.menu_next_frame, false);
	}

	@Override
	protected boolean swipePrevious() {
		return performSwitchFrames(R.id.menu_previous_frame, false);
	}

	private void releasePlayer() {
		stopTextScheduler();
		if (mMediaPlayer != null) {
			try {
				mMediaPlayer.stop();
			} catch (Throwable t) {
			}
			mMediaPlayer.release();
			mMediaPlayer = null;
		}
		if (mMediaController != null) {
			mMediaController.hide();
			((RelativeLayout) findViewById(R.id.audio_preview_container)).removeView(mMediaController);
			mMediaController = null;
		}
	}

	private void switchToPlayback(boolean showAudioHint) {
		releaseRecorder();
		mDisplayMode = DisplayMode.PLAY_AUDIO;

		boolean playerError = false;
		MediaItem audioMediaItem = MediaManager.findMediaByInternalId(getContentResolver(), mMediaItemInternalId);
		if (audioMediaItem != null && audioMediaItem.getFile().length() > 0) {
			FileInputStream playerInputStream = null;
			try {
				releasePlayer();
				mMediaPlayer = new MediaPlayer();
				mMediaController = new NoSwipeCustomMediaController(AudioActivity.this);

				// can't play from data directory (they're private; permissions don't work), must use an input stream
				playerInputStream = new FileInputStream(audioMediaItem.getFile());
				mMediaPlayer.setDataSource(playerInputStream.getFD()); // audioMediaItem.getFile().getAbsolutePath()

				// volume is a percentage of *current*, rather than maximum, so this is unnecessary
				// mMediaPlayer.setVolume(volume, volume);
				mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
				mMediaPlayer.setLooping(true);

				mMediaPlayer.setOnPreparedListener(new OnPreparedListener() {
					@Override
					public void onPrepared(MediaPlayer mp) {
						mp.start();
						mMediaController.setMediaPlayer(mMediaPlayerController);

						// set up the media controller interface elements
						RelativeLayout parentLayout = (RelativeLayout) findViewById(R.id.audio_preview_container);
						RelativeLayout.LayoutParams controllerLayout = new RelativeLayout.LayoutParams(
								RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
						controllerLayout.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
						controllerLayout.setMargins(0, 0, 0,
								getResources().getDimensionPixelSize(R.dimen.button_padding));
						parentLayout.addView(mMediaController, controllerLayout);
						mMediaController.setAnchorView(findViewById(R.id.audio_preview_icon));
						mMediaController.show(0); // 0 for permanent visibility
					}
				});
				mMediaPlayer.prepareAsync();
			} catch (Throwable t) {
				UIUtilities.showToast(AudioActivity.this, R.string.error_playing_audio);
				playerError = true;
			} finally {
				IOUtilities.closeStream(playerInputStream);
			}
		}

		findViewById(R.id.audio_recording).setVisibility(View.GONE);
		findViewById(R.id.audio_recording_controls).setVisibility(View.GONE);
		findViewById(R.id.audio_preview_container).setVisibility(View.VISIBLE);
		findViewById(R.id.audio_preview_controls).setVisibility(View.VISIBLE);

		UIUtilities.setScreenOrientationFixed(this, false);
		if (playerError) {
			onBackPressed();
		} else if (showAudioHint && mRecordingIsAllowed) { // can only edit m4a
			UIUtilities.showToast(AudioActivity.this, mDoesNotHaveMicrophone ? R.string.retake_audio_hint_no_recording
					: R.string.retake_audio_hint);
		}
	}

	private CustomMediaController.MediaPlayerControl mMediaPlayerController = new CustomMediaController.MediaPlayerControl() {
		@Override
		public void start() {
			mMediaPlayer.start();
		}

		@Override
		public void pause() {
			mMediaPlayer.pause();
		}

		@Override
		public int getDuration() {
			return mMediaPlayer.getDuration();
		}

		@Override
		public int getCurrentPosition() {
			return mMediaPlayer.getCurrentPosition();
		}

		@Override
		public void seekTo(int pos) {
			if (pos >= 0 && pos < mMediaPlayer.getDuration()) {
				mMediaPlayer.seekTo(pos);
				if (!mMediaPlayer.isPlaying()) {
					mMediaPlayer.start();
				}
			}

			// disable swipe events so we don't swipe by mistake while moving
			disableSwipe();
			enableSwipeDelayed();
		}

		@Override
		public boolean isPlaying() {
			return mMediaPlayer.isPlaying();
		}

		@Override
		public boolean isLoading() {
			return mMediaPlayer.isPlaying();
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
		}
	};

	private class NoSwipeCustomMediaController extends CustomMediaController {
		private NoSwipeCustomMediaController(Context context) {
			super(context);
		}

		@Override
		public boolean onInterceptTouchEvent(MotionEvent event) {
			switch (event.getAction()) {
				case MotionEvent.ACTION_DOWN:
					disableSwipe();
					break;
				case MotionEvent.ACTION_UP:
					enableSwipeDelayed();
					break;
			}
			return false;
		}
	}

	private void disableSwipe() {
		mSwipeEnablerHandler.removeMessages(R.id.msg_enable_swipe_events);
		setSwipeEventsEnabled(false);
	}

	private void enableSwipeDelayed() {
		final Handler handler = mSwipeEnablerHandler;
		final Message message = handler.obtainMessage(R.id.msg_enable_swipe_events, AudioActivity.this);
		handler.removeMessages(R.id.msg_enable_swipe_events);
		handler.sendMessageDelayed(message, ViewConfiguration.getTapTimeout());
	}

	private static class SwipeEnablerHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case R.id.msg_enable_swipe_events:
					((AudioActivity) msg.obj).setSwipeEventsEnabled(true);
					break;
			}
		}
	}

	public void handleButtonClicks(View currentButton) {
		if (!verifyButtonClick(currentButton)) {
			return;
		}

		switch (currentButton.getId()) {
			case R.id.button_cancel_recording:
			case R.id.button_finished_audio:
				onBackPressed();
				break;

			case R.id.button_record_audio:
				currentButton.setEnabled(false); // don't let them press twice
				if (mAudioRecordingInProgress) {
					stopRecording(AfterRecordingMode.DO_NOTHING); // don't switch to playback afterwards (can continue)
				} else {
					startRecording();
				}
				break;

			case R.id.audio_view_root:
				if (mDisplayMode == DisplayMode.RECORD_AUDIO) {
					break;
				} // fine to follow through if we're not in recording mode
			case R.id.audio_preview_icon:
				MediaItem audioMediaItem = MediaManager.findMediaByInternalId(getContentResolver(),
						mMediaItemInternalId);
				if (audioMediaItem != null) {
					switchToRecording(audioMediaItem.getFile());
				}
				break;

			case R.id.button_delete_audio:
				AlertDialog.Builder builder = new AlertDialog.Builder(AudioActivity.this);
				builder.setTitle(R.string.delete_audio_confirmation);
				builder.setMessage(R.string.delete_audio_hint);
				builder.setIcon(android.R.drawable.ic_dialog_alert);
				builder.setNegativeButton(android.R.string.cancel, null);
				builder.setPositiveButton(R.string.button_delete, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int whichButton) {
						ContentResolver contentResolver = getContentResolver();
						MediaItem audioToDelete = MediaManager.findMediaByInternalId(contentResolver,
								mMediaItemInternalId);
						if (audioToDelete != null) {
							mHasEditedMedia = true;
							audioToDelete.setDeleted(true);
							MediaManager.updateMedia(contentResolver, audioToDelete);
							UIUtilities.showToast(AudioActivity.this, R.string.delete_audio_succeeded);
							onBackPressed();
						}
					}
				});
				AlertDialog alert = builder.create();
				alert.show();
				break;

			case R.id.button_import_audio:
				importAudio();
				break;
		}
	}

	private void stopTextScheduler() {
		if (mAudioTextScheduler != null) {
			mAudioTextScheduler.shutdownNow(); // doesn't allow new tasks to be created afterwards
			mAudioTextScheduler.remove(mAudioTextUpdateTask);
			mAudioTextScheduler.purge();
			mAudioTextScheduler = null;
		}
	}

	private void updateAudioRecordingText(long audioDuration) {
		mRecordingDurationText.setText(StringUtilities.millisecondsToTimeString(audioDuration, true, false));
	}

	private final Runnable mAudioTextUpdateTask = new Runnable() {
		public void run() {
			final Handler handler = mTextUpdateHandler;
			final Message message = handler.obtainMessage(R.id.msg_update_audio_duration_text, AudioActivity.this);
			handler.removeMessages(R.id.msg_update_audio_duration_text);
			handler.sendMessage(message);
		}
	};

	private void scheduleNextAudioTextUpdate(int delay) {
		try {
			if (mAudioTextScheduler != null) {
				mAudioTextScheduler.schedule(mAudioTextUpdateTask, delay, TimeUnit.MILLISECONDS);
			}
		} catch (RejectedExecutionException e) {
			// tried to schedule an update when already stopped
		}
	}

	private void handleTextUpdate() {
		if (mAudioTextScheduler != null && !mAudioTextScheduler.isShutdown()) {
			updateAudioRecordingText(mAudioDuration + System.currentTimeMillis() - mTimeRecordingStarted);
			scheduleNextAudioTextUpdate(getResources().getInteger(R.integer.audio_timer_update_interval));
		}
	}

	private static class TextUpdateHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case R.id.msg_update_audio_duration_text:
					((AudioActivity) msg.obj).handleTextUpdate();
					break;
			}
		}
	}

	private void stopButtonIconBlinkScheduler() {
		if (mButtonIconBlinkScheduler != null) {
			mButtonIconBlinkScheduler.shutdownNow(); // doesn't allow new tasks to be created afterwards
			mButtonIconBlinkScheduler.remove(mButtonIconBlinkTask);
			mButtonIconBlinkScheduler.purge();
			mButtonIconBlinkScheduler = null;
		}
		mNextBlinkMode = R.id.msg_blink_icon_off;
		((CenteredImageTextButton) findViewById(R.id.button_record_audio)).setCompoundDrawablesWithIntrinsicBounds(0,
				R.drawable.ic_record, 0, 0); // reset the button icon
	}

	private final Runnable mButtonIconBlinkTask = new Runnable() {
		public void run() {
			final Handler handler = mButtonIconBlinkHandler;
			final Message message = handler.obtainMessage(mNextBlinkMode, AudioActivity.this);
			handler.removeMessages(R.id.msg_blink_icon_off);
			handler.removeMessages(R.id.msg_blink_icon_on);
			handler.sendMessage(message);
		}
	};

	private void scheduleNextButtonIconBlinkUpdate(int delay) {
		try {
			if (mButtonIconBlinkScheduler != null) {
				mButtonIconBlinkScheduler.schedule(mButtonIconBlinkTask, delay, TimeUnit.MILLISECONDS);
			}
		} catch (RejectedExecutionException e) {
			// tried to schedule an update when already stopped
		}
	}

	private void handleButtonIconBlink(int currentBlinkMode) {
		if (mButtonIconBlinkScheduler != null && !mButtonIconBlinkScheduler.isShutdown()) {
			CenteredImageTextButton recordButton = (CenteredImageTextButton) findViewById(R.id.button_record_audio);
			if (currentBlinkMode == R.id.msg_blink_icon_on) {
				recordButton.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_record_paused, 0, 0);
			} else {
				recordButton.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_record, 0, 0);
			}
			mNextBlinkMode = (currentBlinkMode == R.id.msg_blink_icon_on ? R.id.msg_blink_icon_off
					: R.id.msg_blink_icon_on);
			scheduleNextButtonIconBlinkUpdate(getResources().getInteger(R.integer.audio_button_blink_update_interval));
		}
	}

	private static class ButtonIconBlinkHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case R.id.msg_blink_icon_off:
				case R.id.msg_blink_icon_on:
					((AudioActivity) msg.obj).handleButtonIconBlink(msg.what);
					break;
			}
		}
	}

	public class PathAndStateSavingMediaRecorder extends MediaRecorder {
		private String mOutputFile = null;
		private boolean mIsRecording = false;

		@Override
		public void setOutputFile(String path) {
			super.setOutputFile(path);
			mOutputFile = path;
		}

		public String getOutputFile() {
			return mOutputFile;
		}

		@Override
		public void start() throws IllegalStateException {
			super.start();
			mIsRecording = true;
		}

		@Override
		public void stop() throws IllegalStateException {
			mIsRecording = false;
			super.stop();
		}

		public boolean isRecording() {
			return mIsRecording;
		}
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent resultIntent) {
		switch (requestCode) {
			case R.id.intent_audio_import:
				if (resultCode != RESULT_OK) {
					onBackgroundTaskProgressUpdate(R.id.import_external_media_cancelled);
					break;
				}

				final Uri selectedAudio = resultIntent.getData();
				if (selectedAudio == null) {
					onBackgroundTaskProgressUpdate(R.id.import_external_media_cancelled);
					break;
				}

				final String filePath;
				final int fileDuration;
				Cursor c = getContentResolver()
						.query(selectedAudio,
								new String[] { MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION }, null,
								null, null);
				if (c != null) {
					if (c.moveToFirst()) {
						filePath = c.getString(c.getColumnIndex(MediaStore.Audio.Media.DATA));
						fileDuration = (int) c.getLong(c.getColumnIndex(MediaStore.Audio.Media.DURATION));
					} else {
						filePath = null;
						fileDuration = 0;
					}
					c.close();
					if (filePath == null || fileDuration <= 0) {
						onBackgroundTaskProgressUpdate(R.id.import_external_media_failed);
						break;
					}
				} else {
					onBackgroundTaskProgressUpdate(R.id.import_external_media_failed);
					break;
				}

				runBackgroundTask(new BackgroundRunnable() {
					boolean mImportSucceeded = false;

					@Override
					public int getTaskId() {
						return mImportSucceeded ? Math.abs(R.id.import_external_media_succeeded) : Math
								.abs(R.id.import_external_media_failed); // positive to show dialog
					}

					@Override
					public void run() {
						ContentResolver contentResolver = getContentResolver();
						MediaItem audioMediaItem = MediaManager.findMediaByInternalId(contentResolver,
								mMediaItemInternalId);
						if (audioMediaItem != null) {
							ContentProviderClient client = contentResolver.acquireContentProviderClient(selectedAudio);
							AutoCloseInputStream inputStream = null;
							try {
								String fileExtension = IOUtilities.getFileExtension(filePath);
								ParcelFileDescriptor descriptor = client.openFile(selectedAudio, "r");
								inputStream = new AutoCloseInputStream(descriptor);

								// copy to a temporary file so we can detect failure (i.e. connection)
								File tempFile = new File(audioMediaItem.getFile().getParent(),
										MediaPhoneProvider.getNewInternalId() + "." + fileExtension);
								IOUtilities.copyFile(inputStream, tempFile);

								if (tempFile.length() > 0) {
									audioMediaItem.setFileExtension(fileExtension);
									audioMediaItem.setType(MediaPhoneProvider.TYPE_AUDIO);
									audioMediaItem.setDurationMilliseconds(fileDuration);
									// TODO: will leave old item behind if the extension has changed - fix
									tempFile.renameTo(audioMediaItem.getFile());
									MediaManager.updateMedia(contentResolver, audioMediaItem);
									mImportSucceeded = true;
								}
							} catch (Throwable t) {
							} finally {
								IOUtilities.closeStream(inputStream);
								client.release();
							}
						}
					}
				});
				break;

			default:
				super.onActivityResult(requestCode, resultCode, resultIntent);
		}
	}
}
