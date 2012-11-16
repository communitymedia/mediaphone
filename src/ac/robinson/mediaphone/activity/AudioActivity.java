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
import ac.robinson.util.DebugUtilities;
import ac.robinson.util.IOUtilities;
import ac.robinson.util.StringUtilities;
import ac.robinson.util.UIUtilities;
import ac.robinson.view.CenteredImageTextButton;
import ac.robinson.view.CustomMediaController;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.view.View;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.larvalabs.svgandroid.SVG;
import com.larvalabs.svgandroid.SVGParser;
import com.ringdroid.soundfile.CheapSoundFile;

public class AudioActivity extends MediaPhoneActivity {

	private PathAndStateSavingMediaRecorder mMediaRecorder;
	private boolean mHasEditedAudio = false;
	private boolean mAudioRecording = false;
	private boolean mRecordingIsAllowed = false;
	private boolean mContinueRecordingAfterSplit = false;
	private long mTimeRecordingStarted = 0;
	private long mAudioDuration = 0;

	// loaded from preferences on initialisation
	private boolean mAddToMediaLibrary = false;

	private MediaPlayer mMediaPlayer;
	private CustomMediaController mMediaController;
	private TextView mRecordingDurationText;
	private final Handler mTextUpdateHandler = new TextUpdateHandler();
	private ScheduledThreadPoolExecutor mAudioTextScheduler;

	private String mMediaItemInternalId;

	private enum DisplayMode {
		PLAY_AUDIO, RECORD_AUDIO
	};

	private enum AfterRecordingMode {
		DO_NOTHING, SWITCH_TO_PLAYBACK, SPLIT_FRAME
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

		mMediaItemInternalId = null;
		mRecordingDurationText = ((TextView) findViewById(R.id.audio_recording_progress));
		mDisplayMode = DisplayMode.PLAY_AUDIO;

		SVG svg = SVGParser.getSVGFromResource(getResources(), R.raw.ic_audio_playback);
		((ImageView) findViewById(R.id.audio_preview_icon)).setImageDrawable(svg.createPictureDrawable());

		// load previous id on screen rotation
		if (savedInstanceState != null) {
			mMediaItemInternalId = savedInstanceState.getString(getString(R.string.extra_internal_id));
			mHasEditedAudio = savedInstanceState.getBoolean(getString(R.string.extra_media_edited), true);
		}
	}

	@Override
	public void onSaveInstanceState(Bundle savedInstanceState) {
		// no need to save display mode as we don't allow rotation when actually recording
		savedInstanceState.putString(getString(R.string.extra_internal_id), mMediaItemInternalId);
		savedInstanceState.putBoolean(getString(R.string.extra_media_edited), mHasEditedAudio);
		super.onSaveInstanceState(savedInstanceState);
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) {
			// do this here so that we know the layout's size for loading icons
			loadMediaContainer();
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		releaseAll();
	}

	@Override
	public void onBackPressed() {
		ContentResolver contentResolver = getContentResolver();
		MediaItem audioMediaItem = MediaManager.findMediaByInternalId(contentResolver, mMediaItemInternalId);
		if (mDisplayMode == DisplayMode.RECORD_AUDIO) {
			if (mAudioRecording) {
				stopRecording(AfterRecordingMode.SWITCH_TO_PLAYBACK); // switch to playback afterwards
				return;
			} else {
				// play if they have recorded, exit otherwise
				if (audioMediaItem != null && audioMediaItem.getFile().exists()) {
					switchToPlayback();
					return;
				} else {
					// so we don't leave an empty stub
					audioMediaItem.setDeleted(true);
					MediaManager.updateMedia(contentResolver, audioMediaItem);
				}
			}
		}
		saveLastEditedFrame(audioMediaItem != null ? audioMediaItem.getParentId() : null);
		setResult(mHasEditedAudio ? Activity.RESULT_OK : RESULT_CANCELED);
		finish();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.import_audio, menu);
		inflater.inflate(R.menu.add_frame, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		final int itemId = item.getItemId();
		switch (itemId) {
			case R.id.menu_add_frame:
				if (mAudioRecording) {
					findViewById(R.id.button_record_audio).setEnabled(false);
					mContinueRecordingAfterSplit = true;
					stopRecording(AfterRecordingMode.SPLIT_FRAME);
					return true;
				}

				MediaItem audioMediaItem = MediaManager.findMediaByInternalId(getContentResolver(),
						mMediaItemInternalId);
				if (audioMediaItem != null && audioMediaItem.getFile().exists()) {
					mContinueRecordingAfterSplit = false;
					runBackgroundTask(getFrameSplitterRunnable(mMediaItemInternalId));
				} else {
					UIUtilities.showToast(AudioActivity.this, R.string.split_audio_add_content);
				}
				return true;

			case R.id.menu_import_audio:
				importAudio();
				return true;

			default:
				return super.onOptionsItemSelected(item);
		}
	}

	protected void loadPreferences(SharedPreferences mediaPhoneSettings) {
		// the soft back button (necessary in some circumstances)
		int newVisibility = View.VISIBLE;
		int newAlignment = RelativeLayout.CENTER_HORIZONTAL;
		if (!mediaPhoneSettings.getBoolean(getString(R.string.key_show_back_button),
				getResources().getBoolean(R.bool.default_show_back_button))) {
			newVisibility = View.GONE;
			newAlignment = RelativeLayout.ALIGN_PARENT_LEFT;
		}
		RelativeLayout.LayoutParams newLayoutParams = new RelativeLayout.LayoutParams(0, 0);
		newLayoutParams.addRule(newAlignment, -1);
		findViewById(R.id.button_finished_audio).setVisibility(newVisibility);
		findViewById(R.id.button_cancel_recording).setVisibility(newVisibility);
		findViewById(R.id.audio_recording_view_nav_strut).setLayoutParams(newLayoutParams);

		mAddToMediaLibrary = mediaPhoneSettings.getBoolean(getString(R.string.key_audio_to_media), getResources()
				.getBoolean(R.bool.default_audio_to_media));
	}

	private void loadMediaContainer() {

		// first launch
		ContentResolver contentResolver = getContentResolver();
		if (mMediaItemInternalId == null) {

			// editing an existing frame
			String parentInternalId = null;
			String mediaInternalId = null;
			final Intent intent = getIntent();
			if (intent != null) {
				parentInternalId = intent.getStringExtra(getString(R.string.extra_parent_id));
				mediaInternalId = intent.getStringExtra(getString(R.string.extra_internal_id));
				if (intent.getBooleanExtra(getString(R.string.extra_show_options_menu), false)) {
					openOptionsMenu();
				}
			}
			if (parentInternalId == null) {
				UIUtilities.showToast(AudioActivity.this, R.string.error_loading_audio_editor);
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
		MediaItem audioMediaItem = MediaManager.findMediaByInternalId(contentResolver, mMediaItemInternalId);
		if (audioMediaItem != null) {
			if (audioMediaItem.getFile().getAbsolutePath().endsWith(MediaPhone.EXTENSION_AUDIO_FILE)) {
				mRecordingIsAllowed = true;
			}

			// don't switch to playback on resume if we were recording
			if (mDisplayMode != DisplayMode.RECORD_AUDIO && audioMediaItem.getFile().exists()) {
				mAudioDuration = audioMediaItem.getDurationMilliseconds();
				switchToPlayback();
			} else {
				mRecordingIsAllowed = true;
				switchToRecording(audioMediaItem.getFile().getParentFile());
			}
		} else {
			UIUtilities.showToast(AudioActivity.this, R.string.error_loading_audio_editor);
			onBackPressed();
		}
	}

	private void releaseAll() {
		if (mAudioRecording) {
			stopRecording(AfterRecordingMode.DO_NOTHING);
		}
		stopTextScheduler();
		releasePlayer();
		releaseRecorder();
	}

	private void releaseRecorder() {
		UIUtilities.releaseKeepScreenOn(getWindow());
		if (mMediaRecorder != null) {
			try {
				mMediaRecorder.stop();
			} catch (IllegalStateException e) {
			} catch (RuntimeException e) {
			}
			mMediaRecorder.release();
		}
		mMediaRecorder = null;
	}

	private void switchToRecording(File parentDirectory) {
		if (!mRecordingIsAllowed) { // can only edit m4a
			UIUtilities.showToast(AudioActivity.this, R.string.retake_media_forbidden, true);
			return;
		}

		mDisplayMode = DisplayMode.RECORD_AUDIO;

		// disable screen rotation and screen sleeping while in the recorder
		UIUtilities.setScreenOrientationFixed(this, true);

		// only initialise if we're not already recording (this is called on resume from dialogs, so need to check)
		if (!mAudioRecording) {
			releasePlayer();
			releaseRecorder();
			mTimeRecordingStarted = System.currentTimeMillis(); // hack - make sure scheduled updates are correct
			updateAudioRecordingText(mAudioDuration);

			// TODO: prevent pre-2.3.3 devices from using this - see HTC Desire original's behaviour
			// TODO: use reflection for better audio quality if possible? (bear in mind that this slows down MOV export)
			mMediaRecorder = new PathAndStateSavingMediaRecorder();

			// always record into a temporary file, then combine later
			initialiseAudioRecording(parentDirectory);
		}

		findViewById(R.id.audio_preview_container).setVisibility(View.GONE);
		findViewById(R.id.audio_preview_controls).setVisibility(View.GONE);
		findViewById(R.id.audio_recording).setVisibility(View.VISIBLE);
		findViewById(R.id.audio_recording_controls).setVisibility(View.VISIBLE);
	}

	private void initialiseAudioRecording(File parentDirectory) {
		mMediaRecorder.reset();
		mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
		mMediaRecorder.setAudioChannels(1); // breaks recording

		mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
		mMediaRecorder.setOutputFile(new File(parentDirectory, MediaPhoneProvider.getNewInternalId() + "."
				+ MediaPhone.EXTENSION_AUDIO_FILE).getAbsolutePath());

		mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
		mMediaRecorder.setAudioEncodingBitRate(MediaPhone.AUDIO_RECORDING_BIT_RATE);
		mMediaRecorder.setAudioSamplingRate(MediaPhone.AUDIO_RECORDING_SAMPLING_RATE);

		try {
			mMediaRecorder.prepare();
		} catch (IllegalStateException e) {
			releaseRecorder();
			UIUtilities.showToast(AudioActivity.this, R.string.error_loading_audio_editor);
			onBackPressed();
		} catch (IOException e) {
			releaseRecorder();
			UIUtilities.showToast(AudioActivity.this, R.string.error_loading_audio_editor);
			onBackPressed();
		}
	}

	private void startRecording() {
		mHasEditedAudio = true;
		mAudioRecording = true;
		UIUtilities.acquireKeepScreenOn(getWindow());
		mAudioTextScheduler = new ScheduledThreadPoolExecutor(2);

		mMediaRecorder.setOnErrorListener(new OnErrorListener() {
			@Override
			public void onError(MediaRecorder mr, int what, int extra) {
				UIUtilities.showToast(AudioActivity.this, R.string.error_recording_audio);
				if (MediaPhone.DEBUG)
					Log.d(DebugUtilities.getLogTag(this), "Recording error - what: " + what + ", extra: " + extra);
				cleanUpRecording();
				postStopRecording();
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
		} catch (Throwable t) {
			UIUtilities.showToast(AudioActivity.this, R.string.error_recording_audio);
			if (MediaPhone.DEBUG)
				Log.d(DebugUtilities.getLogTag(this), "Recording error: " + t.getLocalizedMessage());
			cleanUpRecording();
			postStopRecording();
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

	private void cleanUpRecording() {
		stopTextScheduler();
		((VUMeter) findViewById(R.id.vu_meter)).setRecorder(null, null);
		UIUtilities.releaseKeepScreenOn(getWindow());
	}

	private void postStopRecording() {
		mAudioRecording = false;
		CenteredImageTextButton recordButton = (CenteredImageTextButton) findViewById(R.id.button_record_audio);
		recordButton.setEnabled(true);
		recordButton.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_record, 0, 0);
	}

	private void stopRecording(final AfterRecordingMode afterRecordingMode) {
		cleanUpRecording();

		long audioDuration = System.currentTimeMillis() - mTimeRecordingStarted;
		try {
			if (mMediaRecorder.isRecording()) {
				mMediaRecorder.stop();
			}
		} catch (IllegalStateException e) { // not actually recording
			updateAudioRecordingText(0);
			return;
		} catch (RuntimeException e) { // no audio data received - pressed start/stop too quickly
			updateAudioRecordingText(0);
			return;
		} finally {
			postStopRecording();
		}

		mAudioDuration += audioDuration;
		updateAudioRecordingText(mAudioDuration);

		final File newAudioFile = new File(mMediaRecorder.getOutputFile());
		ContentResolver contentResolver = getContentResolver();
		MediaItem audioMediaItem = MediaManager.findMediaByInternalId(contentResolver, mMediaItemInternalId);

		// prepare to continue recording
		initialiseAudioRecording(audioMediaItem.getFile().getParentFile());

		if (audioMediaItem != null) {
			if (!audioMediaItem.getFile().exists()) {

				// this is the first audio recording, so just update the duration
				newAudioFile.renameTo(audioMediaItem.getFile());
				releasePlayer();
				mMediaPlayer = new MediaPlayer();
				try {
					// can't play from data directory (they're private; permissions don't work), must use input stream
					// mMediaPlayer = MediaPlayer.create(AudioActivity.this, Uri.fromFile(audioMediaItem.getFile()));
					FileInputStream playerInputStream = new FileInputStream(audioMediaItem.getFile());
					mMediaPlayer.setDataSource(playerInputStream.getFD());
					IOUtilities.closeStream(playerInputStream);
					mMediaPlayer.prepare();
				} catch (Exception e) {
					releasePlayer();
				}
				if (mMediaPlayer != null) {
					audioMediaItem.setDurationMilliseconds(mMediaPlayer.getDuration());
					releasePlayer();
				} else {
					// just in case (will break playback due to inaccuracy)
					audioMediaItem.setDurationMilliseconds((int) audioDuration);
				}
				if (mAddToMediaLibrary) {
					runBackgroundTask(getMediaLibraryAdderRunnable(audioMediaItem.getFile().getAbsolutePath(),
							Environment.DIRECTORY_MUSIC));
				}
				MediaManager.updateMedia(contentResolver, audioMediaItem);
				if (afterRecordingMode == AfterRecordingMode.SWITCH_TO_PLAYBACK) {
					switchToPlayback();
					return;
				} else if (afterRecordingMode == AfterRecordingMode.SPLIT_FRAME) {
					runBackgroundTask(getFrameSplitterRunnable(mMediaItemInternalId));
					return;
				}

			} else {

				// combine the two recordings in a new background task
				final File mediaFile = audioMediaItem.getFile();
				final CheapSoundFile.ProgressListener loadProgressListener = new CheapSoundFile.ProgressListener() {
					public boolean reportProgress(double fractionComplete) { // for debugging
						if (MediaPhone.DEBUG)
							Log.d(DebugUtilities.getLogTag(this), "Loading progress: " + fractionComplete);
						return true;
					}
				};

				runBackgroundTask(new BackgroundRunnable() {
					@Override
					public int getTaskId() {
						if (afterRecordingMode == AfterRecordingMode.SWITCH_TO_PLAYBACK) {
							return Math.abs(R.id.audio_switch_to_playback_task_complete); // positive to show dialog
						} else if (afterRecordingMode == AfterRecordingMode.SPLIT_FRAME) {
							return Math.abs(R.id.split_frame_task_complete); // positive to show dialog
						} else {
							return -1; // negative for no dialog
						}
					}

					@Override
					public void run() {
						// join the audio files
						try {
							// so we can write directly to the media file
							File tempOriginalInput = new File(mediaFile.getAbsolutePath() + "-temp."
									+ MediaPhone.EXTENSION_AUDIO_FILE);
							IOUtilities.copyFile(mediaFile, tempOriginalInput);

							// load the files to be combined
							CheapSoundFile firstSoundFile = CheapSoundFile.create(tempOriginalInput.getAbsolutePath(),
									loadProgressListener);
							CheapSoundFile secondSoundFile = CheapSoundFile.create(newAudioFile.getAbsolutePath(),
									loadProgressListener);

							if (firstSoundFile != null && secondSoundFile != null) {

								// combine the audio and delete temporary files
								long newDuration = firstSoundFile.addSoundFile(secondSoundFile);
								firstSoundFile.writeFile(mediaFile, 0, firstSoundFile.getNumFrames());
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
								Log.d(DebugUtilities.getLogTag(this), "File not found");
						} catch (IOException e) {
							if (MediaPhone.DEBUG)
								Log.d(DebugUtilities.getLogTag(this), "IOException");
						}

						// split the frame if necessary
						if (afterRecordingMode == AfterRecordingMode.SPLIT_FRAME) {
							getFrameSplitterRunnable(mMediaItemInternalId).run();
						}
					}
				});
			}
		}
	}

	@Override
	protected void onBackgroundTaskProgressUpdate(int taskId) {
		if (taskId == Math.abs(R.id.audio_switch_to_playback_task_complete)) {
			switchToPlayback();
		} else if (taskId == Math.abs(R.id.split_frame_task_complete)) {
			// resume recording state
			mHasEditedAudio = false;
			mAudioDuration = 0;
			updateAudioRecordingText(0);
			if (mContinueRecordingAfterSplit) {
				mContinueRecordingAfterSplit = false;
				startRecording();
			}
			findViewById(R.id.button_add_frame_audio).setEnabled(true);
		} else if (taskId == Math.abs(R.id.import_external_media_failed)) {
			UIUtilities.showToast(AudioActivity.this, R.string.import_audio_failed);
		}

		// *must* be after other tasks (so that we start recording before hiding the dialog)
		super.onBackgroundTaskProgressUpdate(taskId);
	}

	private void releasePlayer() {
		stopTextScheduler();
		if (mMediaPlayer != null) {
			try {
				mMediaPlayer.stop();
			} catch (IllegalStateException e) {
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

	private void switchToPlayback() {
		mDisplayMode = DisplayMode.PLAY_AUDIO;

		releaseRecorder();

		MediaItem audioMediaItem = MediaManager.findMediaByInternalId(getContentResolver(), mMediaItemInternalId);
		if (audioMediaItem != null && audioMediaItem.getFile().exists()) {
			try {
				releasePlayer();
				mMediaPlayer = new MediaPlayer();
				mMediaController = new CustomMediaController(AudioActivity.this);

				// can't play from data directory (they're private; permissions don't work), must use an input stream
				FileInputStream playerInputStream = new FileInputStream(audioMediaItem.getFile());
				mMediaPlayer.setDataSource(playerInputStream.getFD()); // audioMediaItem.getFile().getAbsolutePath()
				IOUtilities.closeStream(playerInputStream);

				// volume is a percentage of *current*, rather than maximum, so this is unnecessary
				// mMediaPlayer.setVolume(volume, volume);
				mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
				mMediaPlayer.setLooping(true);

				mMediaPlayer.setOnPreparedListener(new OnPreparedListener() {
					public void onPrepared(MediaPlayer mp) {
						mp.start();
						mMediaController.setMediaPlayer(new CustomMediaController.MediaPlayerControl() {

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
								}
							}

							@Override
							public boolean isPlaying() {
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

						});
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
			} catch (FileNotFoundException e) {
				UIUtilities.showToast(AudioActivity.this, R.string.error_playing_audio);
			} catch (IllegalArgumentException e) {
				UIUtilities.showToast(AudioActivity.this, R.string.error_playing_audio);
			} catch (IllegalStateException e) {
				UIUtilities.showToast(AudioActivity.this, R.string.error_playing_audio);
			} catch (IOException e) {
				UIUtilities.showToast(AudioActivity.this, R.string.error_playing_audio);
			}
		}

		findViewById(R.id.audio_recording).setVisibility(View.GONE);
		findViewById(R.id.audio_recording_controls).setVisibility(View.GONE);
		findViewById(R.id.audio_preview_container).setVisibility(View.VISIBLE);
		findViewById(R.id.audio_preview_controls).setVisibility(View.VISIBLE);

		UIUtilities.setScreenOrientationFixed(this, false);
		if (mRecordingIsAllowed) { // can only edit m4a
			UIUtilities.showToast(AudioActivity.this, R.string.retake_audio_hint);
		}
	}

	private void importAudio() {
		releaseAll(); // so we're not locking the file we want to copy to
		Intent intent = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI);
		startActivityForResult(intent, R.id.intent_audio_import);
	}

	public void handleButtonClicks(View currentButton) {

		final int buttonId = currentButton.getId();
		switch (buttonId) {
			case R.id.button_cancel_recording:
			case R.id.button_finished_audio:
				onBackPressed();
				break;

			case R.id.button_record_audio:
				currentButton.setEnabled(false); // don't let them press twice
				if (mAudioRecording) {
					stopRecording(AfterRecordingMode.DO_NOTHING); // don't switch to playback afterwards
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
					switchToRecording(audioMediaItem.getFile().getParentFile());
				}
				break;

			case R.id.button_delete_audio:
				AlertDialog.Builder builder = new AlertDialog.Builder(AudioActivity.this);
				builder.setTitle(R.string.delete_audio_confirmation);
				builder.setMessage(R.string.delete_audio_hint);
				builder.setIcon(android.R.drawable.ic_dialog_alert);
				builder.setNegativeButton(android.R.string.cancel, null);
				builder.setPositiveButton(R.string.button_delete, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int whichButton) {
						ContentResolver contentResolver = getContentResolver();
						mHasEditedAudio = true;
						MediaItem audioToDelete = MediaManager.findMediaByInternalId(contentResolver,
								mMediaItemInternalId);
						audioToDelete.setDeleted(true);
						MediaManager.updateMedia(contentResolver, audioToDelete);
						UIUtilities.showToast(AudioActivity.this, R.string.delete_audio_succeeded);
						onBackPressed();
					}
				});
				AlertDialog alert = builder.create();
				alert.show();
				break;

			case R.id.button_playback_import_audio:
				importAudio();
				break;

			case R.id.button_add_frame_audio:
				if (mAudioRecording) {
					currentButton.setEnabled(false); // don't let them press twice
					findViewById(R.id.button_record_audio).setEnabled(false);
					mContinueRecordingAfterSplit = true;
					stopRecording(AfterRecordingMode.SPLIT_FRAME);
				} else if (mHasEditedAudio) {
					currentButton.setEnabled(false); // don't let them press twice
					mContinueRecordingAfterSplit = false;
					runBackgroundTask(getFrameSplitterRunnable(mMediaItemInternalId));
				} else {
					UIUtilities.showToast(AudioActivity.this, R.string.split_audio_add_content);
				}
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
		mRecordingDurationText.setText(StringUtilities.millisecondsToTimeString(audioDuration, true));
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

	private class TextUpdateHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			if (mAudioTextScheduler != null && !mAudioTextScheduler.isShutdown()) {
				switch (msg.what) {
					case R.id.msg_update_audio_duration_text:
						updateAudioRecordingText(mAudioDuration + System.currentTimeMillis() - mTimeRecordingStarted);
						scheduleNextAudioTextUpdate(getResources().getInteger(R.integer.audio_timer_update_interval));
						break;
				}
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
				if (resultCode == RESULT_OK) {
					final Uri selectedAudio = resultIntent.getData();
					if (selectedAudio != null) {
						Cursor c = getContentResolver().query(selectedAudio,
								new String[] { MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION }, null,
								null, null);
						if (c != null && c.moveToFirst()) {
							final String filePath = c.getString(c.getColumnIndex(MediaStore.Audio.Media.DATA));
							final int fileDuration = (int) c.getLong(c.getColumnIndex(MediaStore.Audio.Media.DURATION));
							c.close();
							if (filePath != null && fileDuration > 0) {
								runBackgroundTask(new BackgroundRunnable() {
									boolean mImportSucceeded = false;

									@Override
									public int getTaskId() {
										// positive to show dialog
										return mImportSucceeded ? 0 : Math.abs(R.id.import_external_media_failed);
									}

									@Override
									public void run() {
										ContentResolver contentResolver = getContentResolver();
										MediaItem audioMediaItem = MediaManager.findMediaByInternalId(contentResolver,
												mMediaItemInternalId);
										if (audioMediaItem != null) {
											ContentProviderClient client = contentResolver
													.acquireContentProviderClient(selectedAudio);
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
													// note: will leave old item behind if the extension has changed
													tempFile.renameTo(audioMediaItem.getFile());
													MediaManager.updateMedia(contentResolver, audioMediaItem);
													mHasEditedAudio = true; // for Activity.RESULT_OK & icon update
													mRecordingIsAllowed = audioMediaItem.getFile().getAbsolutePath()
															.endsWith(MediaPhone.EXTENSION_AUDIO_FILE);
													mDisplayMode = DisplayMode.PLAY_AUDIO;
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
							} else {
								UIUtilities.showToast(AudioActivity.this, R.string.import_audio_failed);
							}
						} else {
							UIUtilities.showToast(AudioActivity.this, R.string.import_audio_failed);
						}
						c.close();
					}
				}
				break;

			default:
				super.onActivityResult(requestCode, resultCode, resultIntent);
		}
	}
}
