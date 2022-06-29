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

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
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
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.ringdroid.soundfile.CheapSoundFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import ac.robinson.mediaphone.BuildConfig;
import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediaphone.MediaPhoneActivity;
import ac.robinson.mediaphone.R;
import ac.robinson.mediaphone.provider.MediaItem;
import ac.robinson.mediaphone.provider.MediaManager;
import ac.robinson.mediaphone.provider.MediaPhoneProvider;
import ac.robinson.mediaphone.view.VUMeter;
import ac.robinson.mediaphone.view.VUMeter.RecordingStartedListener;
import ac.robinson.mov.MP3toPCMConverter;
import ac.robinson.util.AndroidUtilities;
import ac.robinson.util.DebugUtilities;
import ac.robinson.util.IOUtilities;
import ac.robinson.util.StringUtilities;
import ac.robinson.util.UIUtilities;
import ac.robinson.view.CenteredImageTextButton;
import ac.robinson.view.CustomMediaController;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;

public class AudioActivity extends MediaPhoneActivity {

	private String mMediaItemInternalId;
	private boolean mHasEditedMedia = false;
	private boolean mAudioPickerShown = false;

	private boolean mRecordingIsAllowed; // TODO: currently extension-based, but we can't actually process all AAC files
	private boolean mFrameSpanningPrevented;
	private boolean mDoesNotHaveMicrophone;
	private PathAndStateSavingMediaRecorder mMediaRecorder;
	private MediaPlayer mMediaPlayer;
	private CustomMediaController mMediaController;
	private TextView mRecordingDurationText;
	private Handler mTextUpdateHandler = new TextUpdateHandler();
	private ScheduledThreadPoolExecutor mAudioTextScheduler;
	private boolean mAudioRecordingInProgress = false;
	private long mTimeRecordingStarted = 0;
	private long mAudioDuration = 0;
	private Handler mButtonIconBlinkHandler = new ButtonIconBlinkHandler();
	private ScheduledThreadPoolExecutor mButtonIconBlinkScheduler;
	private int mNextBlinkMode = R.id.msg_blink_icon_off;

	private boolean mContinueRecordingAfterSplit; // for tracking recording state when adding a frame after

	// loaded properly from preferences on initialisation
	private boolean mAddToMediaLibrary = false;
	private int mAudioBitrate = 8000;

	private enum DisplayMode {
		PLAY_AUDIO, RECORD_AUDIO
	}

	private enum AfterRecordingMode {
		DO_NOTHING, SWITCH_TO_PLAYBACK, ADD_FRAME_AFTER
	}

	private DisplayMode mDisplayMode;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.audio_view);

		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayShowTitleEnabled(true);
			actionBar.setDisplayHomeAsUpEnabled(true);
		}

		// so that the volume controls always control media volume (rather than ringtone etc.)
		setVolumeControlStream(AudioManager.STREAM_MUSIC);

		mDoesNotHaveMicrophone = !getPackageManager().hasSystemFeature(PackageManager.FEATURE_MICROPHONE);

		mRecordingDurationText = findViewById(R.id.audio_recording_progress);
		mDisplayMode = DisplayMode.PLAY_AUDIO;
		mMediaItemInternalId = null;

		// load previous id on screen rotation
		if (savedInstanceState != null) {
			mMediaItemInternalId = savedInstanceState.getString(getString(R.string.extra_internal_id));
			mHasEditedMedia = savedInstanceState.getBoolean(getString(R.string.extra_media_edited), true);
			mAudioPickerShown = savedInstanceState.getBoolean(getString(R.string.extra_external_chooser_shown), false);
			if (mHasEditedMedia) {
				setBackButtonIcons(AudioActivity.this, R.id.button_finished_audio, R.id.button_cancel_recording, true);
			}
		}

		// prevent spanning if appropriate
		Intent launchIntent = getIntent();
		if (launchIntent != null) {
			// hide the spanning button if we're not allowed to span from this item
			mFrameSpanningPrevented = launchIntent.getBooleanExtra(getString(R.string.extra_prevent_frame_spanning), false);
			if (mFrameSpanningPrevented) {
				findViewById(R.id.button_toggle_mode_audio).setVisibility(View.GONE);
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
		savedInstanceState.putBoolean(getString(R.string.extra_external_chooser_shown), mAudioPickerShown);
		super.onSaveInstanceState(savedInstanceState);
	}

	// @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) is for callOnClick()
	@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1)
	@Override
	protected void onPause() {
		try {
			if (!mAudioRecordingInProgress && mMediaPlayer != null && mMediaPlayer.isPlaying()) {
				// call the click method so we update the interface - hacky but it works
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
					findViewById(R.id.pause).callOnClick();
				} else {
					findViewById(R.id.pause).performClick();
				}
			}
		} catch (Throwable t) {
			// if the media player has stopped/crashed/been destroyed while we're trying to pause, then this could
			// happen - better to have the button show the wrong icon than crash the activity
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
					if (audioMediaItem.getDeleted()) {
						// we've been deleted - propagate changes to our parent frame and any following frames
						inheritMediaAndDeleteItemLinks(audioMediaItem.getParentId(), audioMediaItem, null);
					} else if (mHasEditedMedia) {
						// imported or otherwise edited the audio - update icons
						// TODO: we could just update when the audio is added or removed; audio edits don't change icons
						updateMediaFrameIcons(audioMediaItem, null);
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
								// update this frame's icon to show audio; propagate to following frames if applicable
								// TODO: we could just update when the audio is added or removed; edits not needed
								updateMediaFrameIcons(audioMediaItem, null);
								setBackButtonIcons(AudioActivity.this, R.id.button_finished_audio, R.id.button_cancel_recording,
										true);

								// if we do this then we can't tell whether to change icons on screen rotation; disabled
								// mHasEditedMedia = false; // we've saved the icon, so are no longer in edit mode
							}

							// show hint after recording
							switchToPlayback(true);
							return; // we're not exiting yet, but playing the just-recorded audio

						} else {
							// so we don't leave an empty stub
							audioMediaItem.setDeleted(true);
							MediaManager.updateMedia(contentResolver, audioMediaItem);

							// we've been deleted - propagate changes to our parent frame and any following frames
							inheritMediaAndDeleteItemLinks(audioMediaItem.getParentId(), audioMediaItem, null);
						}
					}
					break;
			}

			// save the id of the frame we're part of so that the frame editor gets notified
			saveLastEditedFrame(audioMediaItem.getParentId());
		}

		setResult(mHasEditedMedia ? Activity.RESULT_OK : Activity.RESULT_CANCELED);
		super.onBackPressed();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		createMediaMenuNavigationButtons(inflater, menu, mHasEditedMedia);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		prepareMediaMenuNavigationButtons(menu, mMediaItemInternalId);
		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// TODO: much of this is identical between media types. Combine?
		switch (item.getItemId()) {
			case R.id.menu_add_frame:
				if (mAudioRecordingInProgress) {
					findViewById(R.id.button_record_audio).setEnabled(false);
					mContinueRecordingAfterSplit = true;
					stopRecording(AfterRecordingMode.ADD_FRAME_AFTER);
				} else {
					mContinueRecordingAfterSplit = false;
					addFrameAfter();
				}
				return true;

			case R.id.menu_copy_media:
				final MediaItem copiedMediaItem = MediaManager.findMediaByInternalId(getContentResolver(), mMediaItemInternalId);
				if (copiedMediaItem != null && copiedMediaItem.getFile().exists()) {
					SharedPreferences copyFrameSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME,
							Context.MODE_PRIVATE);
					SharedPreferences.Editor prefsEditor = copyFrameSettings.edit();
					prefsEditor.putString(getString(R.string.key_copied_frame), mMediaItemInternalId);
					prefsEditor.apply();
					UIUtilities.showToast(AudioActivity.this, R.string.copy_media_succeeded);
				}
				return true;

			case R.id.menu_paste_media:
				SharedPreferences copyFrameSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME, Context.MODE_PRIVATE);
				String copiedFrameId = copyFrameSettings.getString(getString(R.string.key_copied_frame), null);
				if (!TextUtils.isEmpty(copiedFrameId)) {
					runQueuedBackgroundTask(getMediaCopyRunnable(copiedFrameId, mMediaItemInternalId));
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
				MediaItem audioMediaItem = MediaManager.findMediaByInternalId(getContentResolver(), mMediaItemInternalId);
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
		// TODO: remove this to fit with new styling (Toolbar etc)
		int newVisibility = View.VISIBLE;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB ||
				!mediaPhoneSettings.getBoolean(getString(R.string.key_show_back_button),
						getResources().getBoolean(R.bool.default_show_back_button))) {
			newVisibility = View.GONE;
		}
		findViewById(R.id.button_finished_audio).setVisibility(newVisibility);
		findViewById(R.id.button_cancel_recording).setVisibility(newVisibility);

		// to enable or disable spanning, all we do is show/hide the interface - eg., items that already span will not be removed
		findViewById(R.id.button_toggle_mode_audio).setVisibility(
				mediaPhoneSettings.getBoolean(getString(R.string.key_spanning_media),
						getResources().getBoolean(R.bool.default_spanning_media)) ?
						(mFrameSpanningPrevented ? View.GONE : View.VISIBLE) : View.GONE);
	}

	private void loadMediaContainer() {

		// first launch
		ContentResolver contentResolver = getContentResolver();
		boolean firstLaunch = mMediaItemInternalId == null;
		boolean startRecording = false;
		if (firstLaunch) {

			// editing an existing frame
			String parentInternalId = null;
			String mediaInternalId = null;
			final Intent intent = getIntent();
			if (intent != null) {
				parentInternalId = intent.getStringExtra(getString(R.string.extra_parent_id));
				mediaInternalId = intent.getStringExtra(getString(R.string.extra_internal_id));
				startRecording = intent.getBooleanExtra(getString(R.string.extra_start_recording_audio), false);
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
			updateSpanFramesButtonIcon(R.id.button_toggle_mode_audio, audioMediaItem.getSpanFrames(), false);

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
				boolean initialised = switchToRecording(currentFile);
				if (initialised && startRecording) {
					startRecording();
				}
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
			} catch (Throwable ignored) {
			}
			mMediaRecorder.release();
		}
		mMediaRecorder = null;
	}

	private boolean recordingIsAllowed(File currentFile) {
		String currentFileExtension = IOUtilities.getFileExtension(currentFile.getAbsolutePath());
		return AndroidUtilities.arrayContains(MediaPhone.EDITABLE_AUDIO_EXTENSIONS, currentFileExtension);
	}

	private boolean switchToRecording(File currentFile) {
		if (!mRecordingIsAllowed) { // can only edit m4a
			// could switch to import automatically here, but it's probably confusing UI-wise to do that
			UIUtilities.showToast(AudioActivity.this, R.string.retake_audio_forbidden, true);
			return false;
		}

		releasePlayer();
		releaseRecorder();

		mDisplayMode = DisplayMode.RECORD_AUDIO;

		// can't record without a microphone present - import only (notification has already been shown)
		if (mDoesNotHaveMicrophone) {
			findViewById(R.id.audio_recording).setVisibility(View.GONE);
			findViewById(R.id.audio_recording_controls).setVisibility(View.GONE);
			if (!mAudioPickerShown) {
				// if the screen rotates while the audio picker is being displayed, we end up showing it again
				// - this is probably a rare issue, but very frustrating when it does happen
				importAudio();
			}
			return false;
		}

		// disable screen rotation and screen sleeping while in the recorder
		UIUtilities.setScreenOrientationFixed(AudioActivity.this, true);

		mTimeRecordingStarted = System.currentTimeMillis(); // hack - make sure scheduled updates are correct
		updateAudioRecordingText(mAudioDuration);

		mMediaRecorder = new PathAndStateSavingMediaRecorder();

		// always record into a temporary file, then combine later
		boolean initialised = initialiseAudioRecording(currentFile);
		if (initialised) {
			findViewById(R.id.audio_preview_container).setVisibility(View.GONE);
			findViewById(R.id.audio_preview_controls).setVisibility(View.GONE);
			findViewById(R.id.audio_recording).setVisibility(View.VISIBLE);
			findViewById(R.id.audio_recording_controls).setVisibility(View.VISIBLE);
		}
		return initialised;
	}

	// @TargetApi(Build.VERSION_CODES.GINGERBREAD_MR1) is for AAC/HE_AAC audio recording
	@TargetApi(Build.VERSION_CODES.GINGERBREAD_MR1)
	private boolean initialiseAudioRecording(File currentFile) {

		// where to record the new audio
		File parentDirectory = currentFile.getParentFile();

		// we must always record at the same sample rate within a single file - read the existing file to check
		int enforcedSampleRate = -1;
		if (currentFile.length() > 0) {
			// blink the button as a hint that we can continue recording
			mButtonIconBlinkScheduler = new ScheduledThreadPoolExecutor(2);
			scheduleNextButtonIconBlinkUpdate(0);

			try {
				CheapSoundFile existingFile = CheapSoundFile.create(currentFile.getAbsolutePath(), true, null);
				enforcedSampleRate = existingFile.getSampleRate();
			} catch (Exception e) {
				enforcedSampleRate = -1;
			}
		}

		mMediaRecorder.reset();
		try {
			mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
		} catch (Exception e) {
			// TODO: new permissions model means we need to request access before doing this
			releaseRecorder();
			UIUtilities.showToast(AudioActivity.this, R.string.error_loading_audio_editor);
			onBackPressed();
			return false;
		}

		// TODO: was this only for AMR? (would need significant testing of export if switching to two channels)
		mMediaRecorder.setAudioChannels(1); // 2 channels breaks recording

		// prefer mpeg4
		mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
		mMediaRecorder.setOutputFile(new File(parentDirectory,
				MediaPhoneProvider.getNewInternalId() + "." + MediaPhone.EXTENSION_AUDIO_FILE).getAbsolutePath());

		// use AAC - see: http://developer.android.com/guide/appendix/media-formats.html
		mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); // because HE_AAC doesn't export properly
		mMediaRecorder.setAudioEncodingBitRate(96000); // hardcoded so we don't accidentally change via globals
		if (enforcedSampleRate > 0) {
			mMediaRecorder.setAudioSamplingRate(enforcedSampleRate);
		} else {
			mMediaRecorder.setAudioSamplingRate(mAudioBitrate);
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

		// TODO: the most common crash on Google Play is a NPE when setting listeners - somehow mMediaRecorder is null
		try {
			mMediaRecorder.setOnErrorListener(new OnErrorListener() {
				@Override
				public void onError(MediaRecorder mr, int what, int extra) {
					UIUtilities.showToast(AudioActivity.this, R.string.error_recording_audio);
					if (MediaPhone.DEBUG) {
						Log.d(DebugUtilities.getLogTag(this), "Recording error - what: " + what + ", extra: " + extra);
					}
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
			mMediaRecorder.start();
			setBackButtonIcons(AudioActivity.this, R.id.button_finished_audio, R.id.button_cancel_recording, true);
		} catch (Throwable t) {
			UIUtilities.showToast(AudioActivity.this, R.string.error_recording_audio);
			if (MediaPhone.DEBUG) {
				Log.d(DebugUtilities.getLogTag(this), "Recording error: " + t.getLocalizedMessage());
			}
			stopRecordingTrackers();
			resetRecordingInterface();
			return;
		}

		mTimeRecordingStarted = System.currentTimeMillis();
		VUMeter vumeter = findViewById(R.id.vu_meter);
		vumeter.setRecorder(mMediaRecorder, new RecordingStartedListener() {
			@Override
			public void recordingStarted() {
				scheduleNextAudioTextUpdate(getResources().getInteger(R.integer.audio_timer_update_interval));
				CenteredImageTextButton recordButton = findViewById(R.id.button_record_audio);
				recordButton.setEnabled(true);
				recordButton.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_audio_pause, 0, 0);
			}
		});
	}

	private void stopRecordingTrackers() {
		stopTextScheduler();
		((VUMeter) findViewById(R.id.vu_meter)).setRecorder(null, null);
	}

	private void resetRecordingInterface() {
		mAudioRecordingInProgress = false;
		CenteredImageTextButton recordButton = findViewById(R.id.button_record_audio);
		recordButton.setEnabled(true);
		recordButton.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_audio_record, 0, 0);
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

		// note that this value may not exactly match the final audio file duration due to the way it is updated with the current
		// device time rather than actual recorded time; however, any mismatch is only in the interface, not in the stored data
		mAudioDuration += audioDuration;
		updateAudioRecordingText(mAudioDuration);

		final File newAudioFile = new File(mMediaRecorder.getOutputFile());
		ContentResolver contentResolver = getContentResolver();
		MediaItem audioMediaItem = MediaManager.findMediaByInternalId(contentResolver, mMediaItemInternalId);
		if (audioMediaItem == null) {
			UIUtilities.showToast(AudioActivity.this, R.string.error_recording_audio);
			if (MediaPhone.DEBUG) {
				Log.d(DebugUtilities.getLogTag(this), "Recording error: couldn't load media item");
			}
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
				if (MediaPhone.DEBUG) {
					Log.d(DebugUtilities.getLogTag(this), "Warning: setting approximate audio duration");
				}
				audioMediaItem.setDurationMilliseconds((int) audioDuration);
			}
			MediaManager.updateMedia(contentResolver, audioMediaItem);

			// prepare to continue recording - done after creating the file so we can examine its bitrate
			initialiseAudioRecording(currentFile);

			if (mAddToMediaLibrary) {
				runImmediateBackgroundTask(
						getMediaLibraryAdderRunnable(currentFile.getAbsolutePath(), Environment.DIRECTORY_MUSIC));
			}

			if (afterRecordingMode == AfterRecordingMode.SWITCH_TO_PLAYBACK) {
				onBackPressed();
				return;
			} else if (afterRecordingMode == AfterRecordingMode.ADD_FRAME_AFTER) {
				switchToPlayback(false); // first switch to playback, so we exit from addFrameAfter()
				addFrameAfter();
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
			runQueuedBackgroundTask(new BackgroundRunnable() {
				@Override
				public int getTaskId() {
					switch (afterRecordingMode) {
						case SWITCH_TO_PLAYBACK:
							return R.id.audio_switch_to_playback_task_complete;
						case ADD_FRAME_AFTER:
							return R.id.audio_add_frame_after_task_complete;
						case DO_NOTHING:
							if (!mRecordingIsAllowed) {
								return R.id.audio_switch_to_playback_task_complete;
							}
					}
					return 0;
				}

				@Override
				public boolean getShowDialog() {
					switch (afterRecordingMode) {
						case SWITCH_TO_PLAYBACK:
						case ADD_FRAME_AFTER:
							return true;
						case DO_NOTHING:
							if (!mRecordingIsAllowed) {
								return true;
							}
					}
					return false;
				}

				@Override
				public void run() {
					// join the audio files
					try {
						// so we can write directly to the media file
						// TODO: this is only necessary because we write the entire file - could have an alternative
						//  method that only writes the new data (and the header/atoms for m4a - remember can be at end)
						File tempOriginalInput = new File(
								currentFile.getAbsolutePath() + "-temp." + MediaPhone.EXTENSION_AUDIO_FILE);
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
								runImmediateBackgroundTask(
										getMediaLibraryAdderRunnable(newAudioMediaItem.getFile().getAbsolutePath(),
												Environment.DIRECTORY_MUSIC));
							}
							MediaManager.updateMedia(contentResolver, newAudioMediaItem);
						}
					} catch (FileNotFoundException e) {
						// TODO: notify the user when joining files fails
						if (MediaPhone.DEBUG) {
							Log.d(DebugUtilities.getLogTag(this), "Append audio: file not found");
						}
					} catch (IOException e) {
						// TODO: notify the user when joining files fails
						if (MediaPhone.DEBUG) {
							Log.d(DebugUtilities.getLogTag(this), "Append audio: IOException");
						}
					} catch (Throwable t) {
						// TODO: notify the user when joining files fails
						if (MediaPhone.DEBUG) {
							Log.d(DebugUtilities.getLogTag(this), "Append audio: Throwable");
						}
					}
				}
			});
		}
	}

	private void importAudio() {
		releaseAll(); // so we're not locking the file we want to copy to
		Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.setType("audio/*");
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
			intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); // on later devices we can select more than one item
		}
		try {
			startActivityForResult(intent, MediaPhone.R_id_intent_audio_import);
			mAudioPickerShown = true;
		} catch (ActivityNotFoundException e) {
			UIUtilities.showToast(AudioActivity.this, R.string.import_audio_unavailable);
			if (mDoesNotHaveMicrophone) {
				onBackPressed(); // we can't do anything else here
			} else {
				MediaItem audioMediaItem = MediaManager.findMediaByInternalId(getContentResolver(), mMediaItemInternalId);
				if (audioMediaItem != null) {
					switchToRecording(audioMediaItem.getFile()); // released recorder, so switch back
				}
			}
		}
	}

	@Override
	protected void onBackgroundTaskCompleted(int taskId) {
		switch (taskId) {
			case R.id.audio_switch_to_playback_task_complete:
				onBackPressed();
				break;
			case R.id.audio_add_frame_after_task_complete:
				switchToPlayback(false); // first switch to playback, so we exit from addFrameAfter()
				addFrameAfter();
				break;
			case R.id.import_external_media_succeeded:
			case R.id.import_multiple_external_media_succeeded:
				if (taskId == R.id.import_multiple_external_media_succeeded) {
					UIUtilities.showToast(AudioActivity.this, R.string.import_multiple_items_succeeded);
				}
				mHasEditedMedia = true; // to force an icon update
				MediaItem audioMediaItem = MediaManager.findMediaByInternalId(getContentResolver(), mMediaItemInternalId);
				if (audioMediaItem != null) {
					mRecordingIsAllowed = recordingIsAllowed(audioMediaItem.getFile());
					mAudioDuration = audioMediaItem.getDurationMilliseconds();
				}
				onBackPressed(); // to start playback
				break;
			case R.id.import_multiple_external_media_failed:
				UIUtilities.showToast(AudioActivity.this, R.string.import_multiple_items_failed);
				mHasEditedMedia = true; // to force an icon update
				onBackPressed(); // to start playback
				break;
			case R.id.import_external_media_failed:
			case R.id.import_external_media_cancelled:
				if (taskId == R.id.import_external_media_failed) {
					UIUtilities.showToast(AudioActivity.this, R.string.import_audio_failed);
				}
				if (mDoesNotHaveMicrophone) {
					onBackPressed(); // we can't do anything else here
				} else {
					MediaItem updatedMediaItem = MediaManager.findMediaByInternalId(getContentResolver(), mMediaItemInternalId);
					if (updatedMediaItem != null) {
						switchToRecording(updatedMediaItem.getFile()); // released recorder, so switch back
					}
				}
				break;

			case R.id.copy_paste_media_task_empty:
				UIUtilities.showToast(AudioActivity.this, R.string.paste_media_empty, true);
				break;

			case R.id.copy_paste_media_task_failed: // note: copy_paste_media_task_partial is impossible for media items
				UIUtilities.showToast(AudioActivity.this, R.string.paste_media_failed, true);
				break;

			case R.id.copy_paste_media_task_complete:
				loadMediaContainer();
				mHasEditedMedia = true;
				break;
			default:
				break;
		}
	}

	private void addFrameAfter() {
		final MediaItem audioMediaItem = MediaManager.findMediaByInternalId(getContentResolver(), mMediaItemInternalId);
		if (audioMediaItem != null && audioMediaItem.getFile().length() > 0) {
			final String newFrameId = insertFrameAfterMedia(audioMediaItem);
			final Intent addAudioIntent = new Intent(AudioActivity.this, AudioActivity.class);
			addAudioIntent.putExtra(getString(R.string.extra_parent_id), newFrameId);
			if (mContinueRecordingAfterSplit) {
				addAudioIntent.putExtra(getString(R.string.extra_start_recording_audio), mContinueRecordingAfterSplit);
			}
			if (mFrameSpanningPrevented) {
				addAudioIntent.putExtra(getString(R.string.extra_prevent_frame_spanning), true);
			}
			startActivity(addAudioIntent);

			onBackPressed();
		} else {
			UIUtilities.showToast(AudioActivity.this, R.string.split_audio_add_content);
		}
	}

	private void releasePlayer() {
		stopTextScheduler();
		if (mMediaPlayer != null) {
			try {
				mMediaPlayer.stop();
			} catch (Throwable ignored) {
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
				mMediaController = new CustomMediaController(AudioActivity.this);

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
						// as in PlaybackActivity we need to check whether we're in tests (currently just used for automatically
						// capturing screenshots) because anything reliant on regular UI updates adds large delays and
						// significant uncertainty to UI automation
						if (!BuildConfig.IS_TESTING.get()) {
							mp.start();
						}
						mMediaController.setMediaPlayer(mMediaPlayerController);

						// set up the media controller interface elements
						RelativeLayout parentLayout = findViewById(R.id.audio_preview_container);
						RelativeLayout.LayoutParams controllerLayout = new RelativeLayout.LayoutParams(
								RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
						controllerLayout.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
						controllerLayout.setMargins(0, 0, 0, getResources().getDimensionPixelSize(R.dimen.button_padding));
						parentLayout.addView(mMediaController, controllerLayout);
						mMediaController.setAnchorView(findViewById(R.id.audio_preview_icon));
						mMediaController.setOnClickListener(null); // don't edit when clicking this view
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

		UIUtilities.setScreenOrientationFixed(AudioActivity.this, false);
		if (playerError) {
			onBackPressed();
		} else if (showAudioHint && mRecordingIsAllowed) { // can only edit m4a
			UIUtilities.showToast(AudioActivity.this,
					mDoesNotHaveMicrophone ? R.string.retake_audio_hint_no_recording : R.string.retake_audio_hint);
		}
	}

	private CustomMediaController.MediaPlayerControl mMediaPlayerController = new CustomMediaController.MediaPlayerControl() {
		@Override
		public void start() {
			if (mMediaPlayer != null) {
				mMediaPlayer.start();
			}
		}

		@Override
		public void pause() {
			if (mMediaPlayer != null) {
				mMediaPlayer.pause();
			}
		}

		@Override
		public int getDuration() {
			return mMediaPlayer != null ? mMediaPlayer.getDuration() : 0;
		}

		@Override
		public int getCurrentPosition() {
			return mMediaPlayer != null ? mMediaPlayer.getCurrentPosition() : 0;
		}

		@Override
		public void seekTo(int pos) {
			if (mMediaPlayer != null) {
				if (pos >= 0 && pos < mMediaPlayer.getDuration()) {
					mMediaPlayer.seekTo(pos);
					if (!mMediaPlayer.isPlaying()) {
						mMediaPlayer.start();
					}
				}
			}
		}

		@Override
		public boolean isPlaying() {
			return mMediaPlayer != null ? mMediaPlayer.isPlaying() : false;
		}

		@Override
		public boolean isLoading() {
			return mMediaPlayer != null ? mMediaPlayer.isPlaying() : false;
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
				MediaItem audioMediaItem = MediaManager.findMediaByInternalId(getContentResolver(), mMediaItemInternalId);
				if (audioMediaItem != null) {
					switchToRecording(audioMediaItem.getFile());
				}
				break;

			case R.id.button_toggle_mode_audio:
				final MediaItem spanningAudioMediaItem = MediaManager.findMediaByInternalId(getContentResolver(),
						mMediaItemInternalId);
				if (spanningAudioMediaItem != null && spanningAudioMediaItem.getFile().length() > 0) {
					mHasEditedMedia = true; // so we update/inherit on exit and show the media edited icon
					setBackButtonIcons(AudioActivity.this, R.id.button_finished_audio, R.id.button_cancel_recording, true);
					boolean frameSpanning = toggleFrameSpanningMedia(spanningAudioMediaItem);
					updateSpanFramesButtonIcon(R.id.button_toggle_mode_audio, frameSpanning, true);
					UIUtilities.showToast(AudioActivity.this,
							frameSpanning ? R.string.span_audio_multiple_frames : R.string.span_audio_single_frame);
				} else {
					UIUtilities.showToast(AudioActivity.this, R.string.span_audio_add_content);
				}
				break;

			case R.id.button_delete_audio:
				AlertDialog.Builder builder = new AlertDialog.Builder(AudioActivity.this);
				builder.setTitle(R.string.delete_audio_confirmation);
				builder.setMessage(R.string.delete_audio_hint);
				builder.setNegativeButton(R.string.button_cancel, null);
				builder.setPositiveButton(R.string.button_delete, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int whichButton) {
						ContentResolver contentResolver = getContentResolver();
						MediaItem audioToDelete = MediaManager.findMediaByInternalId(contentResolver, mMediaItemInternalId);
						if (audioToDelete != null) {
							mHasEditedMedia = true; // so the frame editor updates its display
							audioToDelete.setDeleted(true);
							MediaManager.updateMedia(contentResolver, audioToDelete);
							UIUtilities.showToast(AudioActivity.this, R.string.delete_audio_succeeded);
							onBackPressed();
						}
					}
				});
				builder.show();
				break;

			case R.id.button_import_audio:
				importAudio();
				break;

			default:
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
				default:
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
				R.drawable.ic_audio_record, 0, 0); // reset the button icon
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
			CenteredImageTextButton recordButton = findViewById(R.id.button_record_audio);
			if (currentBlinkMode == R.id.msg_blink_icon_on) {
				recordButton.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_audio_resume, 0, 0);
			} else {
				recordButton.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_audio_record, 0, 0);
			}
			mNextBlinkMode = (currentBlinkMode == R.id.msg_blink_icon_on ? R.id.msg_blink_icon_off : R.id.msg_blink_icon_on);
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
				default:
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
			case MediaPhone.R_id_intent_audio_import:
				mAudioPickerShown = false;

				handleMediaImport(resultCode, resultIntent, mMediaItemInternalId, new ImportMediaCallback() {
					@Override
					public boolean importMedia(MediaItem mediaItem, Uri selectedItemUri) {
						ContentResolver contentResolver = getContentResolver();
						InputStream inputStream = null;
						try {
							String fileExtension = MimeTypeMap.getSingleton()
									.getExtensionFromMimeType(contentResolver.getType(selectedItemUri));
							if (TextUtils.isEmpty(fileExtension)) {
								fileExtension = "m4a"; // no match in the mime type map - guess at most common file extension
							}

							// copy to a temporary file so we can detect failure (i.e. connection)
							inputStream = contentResolver.openInputStream(selectedItemUri);
							File tempFile = new File(mediaItem.getFile().getParent(),
									MediaPhoneProvider.getNewInternalId() + "." + fileExtension);
							IOUtilities.copyFile(inputStream, tempFile);

							if (tempFile.length() > 0) {
								// the forced transition to the Storage Access Framework means we need to rely on MIME types
								// rather than file extensions; however, to Android, the MIME type for m4a is the same as mp3
								// (and MimeTypeMap defaults to mp3), so we need this workaround to fix the problem
								if ("mp3".equals(fileExtension)) {
									try {
										MP3toPCMConverter.MP3Configuration mp3Config = new MP3toPCMConverter.MP3Configuration();
										MP3toPCMConverter.getFileConfig(tempFile, mp3Config);
										if (mp3Config.sampleFrequency == 0) {
											fileExtension = "m4a"; // invalid mp3; assume m4a
										}
									} catch (Exception ignored) {
									}
								}

								mediaItem.setFileExtension(fileExtension);
								mediaItem.setType(MediaPhoneProvider.TYPE_AUDIO);

								int preciseDuration = IOUtilities.getAudioFileLength(tempFile);
								if (preciseDuration > 0) {
									mediaItem.setDurationMilliseconds(preciseDuration);
								} else {
									return false; // if we can't get the duration we can't realistically use this file
								}

								// TODO: will leave old item behind if the extension has changed - fix
								tempFile.renameTo(mediaItem.getFile());
								return true;
							}
						} catch (Throwable ignored) {
						} finally {
							IOUtilities.closeStream(inputStream);
						}
						return false;
					}
				});
				break;

			default:
				super.onActivityResult(requestCode, resultCode, resultIntent);
		}
	}
}
