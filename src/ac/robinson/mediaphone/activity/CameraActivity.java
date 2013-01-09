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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediaphone.MediaPhoneActivity;
import ac.robinson.mediaphone.R;
import ac.robinson.mediaphone.provider.FrameItem;
import ac.robinson.mediaphone.provider.MediaItem;
import ac.robinson.mediaphone.provider.MediaManager;
import ac.robinson.mediaphone.provider.MediaPhoneProvider;
import ac.robinson.mediaphone.view.CameraView;
import ac.robinson.util.BitmapUtilities;
import ac.robinson.util.CameraUtilities;
import ac.robinson.util.DebugUtilities;
import ac.robinson.util.IOUtilities;
import ac.robinson.util.OrientationManager;
import ac.robinson.util.UIUtilities;
import ac.robinson.view.CenteredImageTextButton;
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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.Camera;
import android.hardware.Camera.ErrorCallback;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.AutoCloseInputStream;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Display;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.RelativeLayout.LayoutParams;

public class CameraActivity extends MediaPhoneActivity implements OrientationManager.OrientationListener {

	private int mDisplayOrientation = 0;
	private int mScreenOrientation = 0;
	private int mPreviewIconRotation;

	// loaded properly from attrs file and preferences when camera is initialised
	private int mJpegSaveQuality = 80;
	private String mCameraShutterSoundPath = null;
	private boolean mCapturePreviewFrame = false;
	private boolean mAddToMediaLibrary = false;

	private CameraView mCameraView;
	private Camera mCamera;
	private CameraUtilities.CameraConfiguration mCameraConfiguration = new CameraUtilities.CameraConfiguration();
	private boolean mHasEditedPicture = false;
	private Boolean mSavingInProgress = false;
	private boolean mBackPressedDuringPhoto = false;

	private String mMediaItemInternalId = null;

	private enum DisplayMode {
		DISPLAY_PICTURE, TAKE_PICTURE, SWITCHING_FRAME
	};

	private DisplayMode mDisplayMode = DisplayMode.TAKE_PICTURE;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			setTheme(R.style.default_light_theme); // light looks *much* better beyond honeycomb
		}
		UIUtilities.configureActionBar(this, true, true, R.string.title_frame_editor, R.string.title_camera);
		setContentView(R.layout.camera_view);

		mMediaItemInternalId = null;
		mDisplayMode = DisplayMode.DISPLAY_PICTURE;

		// load previous id on screen rotation
		if (savedInstanceState != null) {
			mMediaItemInternalId = savedInstanceState.getString(getString(R.string.extra_internal_id));
			mHasEditedPicture = savedInstanceState.getBoolean(getString(R.string.extra_media_edited), true);
		}
	}

	@Override
	public void onSaveInstanceState(Bundle savedInstanceState) {
		// no need to save display mode as we don't allow rotation when actually taking a picture
		savedInstanceState.putString(getString(R.string.extra_internal_id), mMediaItemInternalId);
		savedInstanceState.putBoolean(getString(R.string.extra_media_edited), mHasEditedPicture);
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
	protected void onResume() {
		super.onResume();
		SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		if (OrientationManager.isSupported(sensorManager)) {
			OrientationManager.startListening(sensorManager, this);
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		OrientationManager.stopListening();
		releaseCamera();
	}

	@Override
	public void onBackPressed() {
		synchronized (mSavingInProgress) {
			if (mSavingInProgress) { // don't let them exit mid-way through saving
				mBackPressedDuringPhoto = true;
				return;
			}
		}

		// managed to press back before loading the media - wait
		if (mMediaItemInternalId == null) {
			return;
		}

		ContentResolver contentResolver = getContentResolver();
		MediaItem imageMediaItem = MediaManager.findMediaByInternalId(contentResolver, mMediaItemInternalId);
		if (mDisplayMode == DisplayMode.TAKE_PICTURE || mDisplayMode == DisplayMode.SWITCHING_FRAME) {
			// make sure we update if they took a picture and then went back to the camera view
			if (imageMediaItem != null) {
				if (imageMediaItem.getFile().length() > 0) {
					if (mDisplayMode == DisplayMode.TAKE_PICTURE) { // mode is changed after switchToPicture();
						switchToPicture();
						return;
					} else {
						switchToPicture();
					}
				} else {
					// so we don't leave an empty stub
					imageMediaItem.setDeleted(true);
					MediaManager.updateMedia(contentResolver, imageMediaItem);
				}
			}
		}
		saveLastEditedFrame(imageMediaItem != null ? imageMediaItem.getParentId() : null);
		setResult(mHasEditedPicture ? Activity.RESULT_OK : Activity.RESULT_CANCELED);
		finish();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		setupMenuNavigationButtonsFromMedia(inflater, menu, getContentResolver(), mMediaItemInternalId);
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

			case R.id.menu_finished_editing:
				onBackPressed();
				return true;

			default:
				return super.onOptionsItemSelected(item);
		}
	}

	@Override
	protected void loadPreferences(SharedPreferences mediaPhoneSettings) {
		// the soft done/back button
		int newVisibility = View.VISIBLE;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB
				|| !mediaPhoneSettings.getBoolean(getString(R.string.key_show_back_button),
						getResources().getBoolean(R.bool.default_show_back_button))) {
			newVisibility = View.GONE;
		}
		findViewById(R.id.button_cancel_camera).setVisibility(newVisibility);
		findViewById(R.id.button_finished_picture).setVisibility(newVisibility);

		// capture preview frame low quality rear camera pictures (for front camera, this is always the case)
		mCapturePreviewFrame = !mediaPhoneSettings.getBoolean(getString(R.string.key_high_quality_pictures),
				getResources().getBoolean(R.bool.default_high_quality_pictures));

		mAddToMediaLibrary = mediaPhoneSettings.getBoolean(getString(R.string.key_pictures_to_media), getResources()
				.getBoolean(R.bool.default_pictures_to_media));
	}

	private void loadMediaContainer() {

		// first launch
		ContentResolver contentResolver = getContentResolver();
		boolean showOptionsMenu = false;
		boolean firstLaunch = mMediaItemInternalId == null;
		if (firstLaunch) {

			// editing an existing frame
			String parentInternalId = null;
			final Intent intent = getIntent();
			if (intent != null) {
				parentInternalId = intent.getStringExtra(getString(R.string.extra_parent_id));
				showOptionsMenu = intent.getBooleanExtra(getString(R.string.extra_show_options_menu), false);
				if (showOptionsMenu) {
					firstLaunch = false;
				}
			}
			if (parentInternalId == null) {
				UIUtilities.showToast(CameraActivity.this, R.string.error_loading_image_editor);
				mMediaItemInternalId = "-1"; // so we exit
				onBackPressed();
				return;
			}

			// get existing content if it exists
			mMediaItemInternalId = FrameItem.getImageContentId(contentResolver, parentInternalId);

			// add a new media item if it doesn't already exist
			if (mMediaItemInternalId == null) {
				MediaItem imageMediaItem = new MediaItem(parentInternalId, MediaPhone.EXTENSION_PHOTO_FILE,
						MediaPhoneProvider.TYPE_IMAGE_BACK);
				mMediaItemInternalId = imageMediaItem.getInternalId();
				MediaManager.addMedia(contentResolver, imageMediaItem);
			}
		}

		// load the existing image
		MediaItem imageMediaItem = MediaManager.findMediaByInternalId(contentResolver, mMediaItemInternalId);
		if (imageMediaItem != null) {
			// don't switch to the image on resume if we were using the camera
			if (mDisplayMode != DisplayMode.TAKE_PICTURE && imageMediaItem.getFile().exists()) {
				if (imageMediaItem.getType() == MediaPhoneProvider.TYPE_IMAGE_FRONT) {
					mCameraConfiguration.usingFrontCamera = true;
				}
				switchToPicture(firstLaunch);
			} else {
				switchToCamera(mCameraConfiguration.usingFrontCamera, firstLaunch);
			}
		} else {
			UIUtilities.showToast(CameraActivity.this, R.string.error_loading_image_editor);
			onBackPressed();
			return;
		}

		// TODO: display mode here is a hack because if we show the options menu the camera controls don't show up
		if (showOptionsMenu && mDisplayMode != DisplayMode.TAKE_PICTURE
				&& Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
			openOptionsMenu();
		}
		registerForSwipeEvents(); // here to avoid crashing due to double-swiping
	}

	private void releaseCamera() {

		if (mCameraView != null) {
			mCameraView.setCamera(null, 0, 0, 0, 0, null);
		}

		// camera object is a shared resource - must be released
		if (mCamera != null) {
			mCamera.release();
			mCamera = null;
		}

		// must be done after releasing the camera (otherwise we release the active surface...)
		if (mCameraView != null) {
			mCameraView.removeAllViews();
			RelativeLayout viewRoot = (RelativeLayout) findViewById(R.id.camera_view_root);
			if (viewRoot != null) {
				viewRoot.removeView(mCameraView);
			}
			mCameraView = null;
		}
	}

	private void switchToCamera(boolean preferFront) {
		switchToCamera(preferFront, false);
	}

	private void switchToCamera(boolean preferFront, boolean showFocusHint) {
		releaseCamera();
		mDisplayMode = DisplayMode.TAKE_PICTURE;

		// disable screen rotation while in the camera
		UIUtilities.setScreenOrientationFixed(this, true);
		UIUtilities.actionBarVisibility(this, false);
		UIUtilities.setFullScreen(getWindow());

		// update buttons and create the camera view if necessary
		findViewById(R.id.layout_image_controls).setVisibility(View.GONE);
		findViewById(R.id.layout_camera_top_controls).setVisibility(View.VISIBLE);
		findViewById(R.id.layout_camera_bottom_controls).setVisibility(View.VISIBLE);
		if (mCameraView == null) {
			mCameraView = new CameraView(this);
			LayoutParams layoutParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
			layoutParams.setMargins(0, 0, 0, 0); // getResources().getDimensionPixelSize(R.dimen.navigation_button_height)
			((RelativeLayout) findViewById(R.id.camera_view_root)).addView(mCameraView, layoutParams);
			findViewById(R.id.layout_camera_top_controls).bringToFront();
			findViewById(R.id.layout_camera_bottom_controls).bringToFront();
		}

		mCamera = CameraUtilities.initialiseCamera(preferFront, mCameraConfiguration);
		if (mCameraConfiguration.numberOfCameras > 0 && mCamera != null) {
			mCamera.setErrorCallback(new ErrorCallback() {
				@Override
				public void onError(int error, Camera camera) {
					UIUtilities.showToast(CameraActivity.this, R.string.error_camera_failed);
					synchronized (mSavingInProgress) {
						mSavingInProgress = false; // so we don't get stuck in the camera activity
					}
					onBackPressed();
				}
			});
		} else {
			UIUtilities.showToast(CameraActivity.this, R.string.error_camera_failed);
			finish(); // no camera found - TODO: allow picture loading
			return;
		}

		Resources res = getResources();
		mJpegSaveQuality = res.getInteger(R.integer.camera_jpeg_save_quality);
		mCameraShutterSoundPath = res.getString(R.string.camera_shutter_sound_path);
		int autofocusInterval = res.getInteger(R.integer.camera_autofocus_interval);

		if (!mCameraConfiguration.hasFrontCamera || mCameraConfiguration.numberOfCameras <= 1) {
			findViewById(R.id.button_switch_camera).setVisibility(View.GONE);
		} else {
			findViewById(R.id.button_switch_camera).setEnabled(true);
		}

		mDisplayOrientation = CameraUtilities.getPreviewOrientationDegrees(
				UIUtilities.getScreenRotationDegrees(getWindowManager()),
				mCameraConfiguration.cameraOrientationDegrees, mCameraConfiguration.usingFrontCamera);
		SharedPreferences flashSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME, Context.MODE_PRIVATE);
		String flashMode = flashSettings.getString(getString(R.string.key_camera_flash_mode), null);
		mCameraView.setCamera(mCamera, mDisplayOrientation, mDisplayOrientation, mJpegSaveQuality, autofocusInterval,
				flashMode);

		if (mCameraView.canChangeFlashMode()) {
			setFlashButtonIcon(flashMode);
			findViewById(R.id.button_toggle_flash).setVisibility(View.VISIBLE);
		} else {
			findViewById(R.id.button_toggle_flash).setVisibility(View.GONE);
		}

		findViewById(R.id.button_take_picture).setEnabled(true);
		if (showFocusHint && mCameraView.canAutoFocus()) {
			UIUtilities.showToast(CameraActivity.this, R.string.focus_camera_hint);
		}
	}

	// only used when taking a picture rather than capturing a preview frame
	private Camera.PictureCallback mPictureJpegCallback = new Camera.PictureCallback() {
		public void onPictureTaken(byte[] imageData, Camera c) {
			new SavePreviewFrameTask().execute(imageData);
		}
	};

	// see: http://stackoverflow.com/questions/6469019/
	private Camera.PreviewCallback mPreviewFrameCallback = new Camera.PreviewCallback() {
		@Override
		public void onPreviewFrame(byte[] imageData, Camera camera) {
			new SavePreviewFrameTask().execute(imageData);
		}
	};

	private class SavePreviewFrameTask extends AsyncTask<byte[], Void, Boolean> {

		@Override
		protected Boolean doInBackground(byte[]... params) {
			byte[] data = params[0];
			if (data == null) {
				UIUtilities.showToast(CameraActivity.this, R.string.save_picture_failed);
				if (MediaPhone.DEBUG)
					Log.d(DebugUtilities.getLogTag(this), "SavePreviewFrameTask: data is null");
				return false;
			}

			ContentResolver contentResolver = getContentResolver();
			MediaItem imageMediaItem = MediaManager.findMediaByInternalId(contentResolver, mMediaItemInternalId);
			if (imageMediaItem != null) {
				// TODO: if replacing an imported non-jpeg with a photo, this will leave the old file in place - delete
				imageMediaItem.setFileExtension(MediaPhone.EXTENSION_PHOTO_FILE);
				imageMediaItem.setType(mCameraConfiguration.usingFrontCamera ? MediaPhoneProvider.TYPE_IMAGE_FRONT
						: MediaPhoneProvider.TYPE_IMAGE_BACK);
				MediaManager.updateMedia(contentResolver, imageMediaItem);

				CameraView.CameraImageConfiguration pictureConfig;
				if (mCapturePreviewFrame || mCameraConfiguration.usingFrontCamera) {
					pictureConfig = mCameraView.getPreviewConfiguration();
				} else {
					pictureConfig = mCameraView.getPictureConfiguration();
				}

				if ((pictureConfig.imageFormat == ImageFormat.NV21) || (pictureConfig.imageFormat == ImageFormat.YUY2)) {
					if (MediaPhone.DEBUG)
						Log.d(DebugUtilities.getLogTag(this), "Saving NV21/YUY2 to JPEG");

					// correct for screen and camera display rotation
					// TODO: this is still not right all the time
					int rotation = CameraUtilities.getPreviewOrientationDegrees(mScreenOrientation,
							mDisplayOrientation, mCameraConfiguration.usingFrontCamera);
					rotation = (rotation + mScreenOrientation) % 360;

					if (!BitmapUtilities.saveYUYToJPEG(data, imageMediaItem.getFile(), pictureConfig.imageFormat,
							mJpegSaveQuality, pictureConfig.width, pictureConfig.height, rotation,
							mCameraConfiguration.usingFrontCamera)) {
						UIUtilities.showToast(CameraActivity.this, R.string.save_picture_failed);
					}

				} else if (pictureConfig.imageFormat == ImageFormat.JPEG) {
					if (MediaPhone.DEBUG)
						Log.d(DebugUtilities.getLogTag(this), "Directly writing JPEG to storage");

					if (!BitmapUtilities.saveJPEGToJPEG(data, imageMediaItem.getFile(),
							mCameraConfiguration.usingFrontCamera)) {
						UIUtilities.showToast(CameraActivity.this, R.string.save_picture_failed);
					}

				} else {
					// TODO: rotate this accordingly
					FileOutputStream fileOutputStream = null;
					try {
						fileOutputStream = new FileOutputStream(imageMediaItem.getFile());
						if (MediaPhone.DEBUG)
							Log.d(DebugUtilities.getLogTag(this), "Trying decoding to byte array");
						Bitmap rawBitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
						if (rawBitmap != null) {
							rawBitmap.compress(Bitmap.CompressFormat.JPEG, mJpegSaveQuality, fileOutputStream);
							rawBitmap.recycle();
						} else {
							UIUtilities.showToast(CameraActivity.this, R.string.save_picture_failed);
							if (MediaPhone.DEBUG)
								Log.d(DebugUtilities.getLogTag(this), "DecodeByteArray failed: no decoded data");
							return false;
						}
					} catch (FileNotFoundException e) {
						UIUtilities.showToast(CameraActivity.this, R.string.save_picture_failed);
					} finally {
						IOUtilities.closeStream(fileOutputStream);
					}
				}

				if (mAddToMediaLibrary) {
					runBackgroundTask(getMediaLibraryAdderRunnable(imageMediaItem.getFile().getAbsolutePath(),
							Environment.DIRECTORY_DCIM));
				}
			} else {
				UIUtilities.showToast(CameraActivity.this, R.string.save_picture_failed);
				if (MediaPhone.DEBUG)
					Log.d(DebugUtilities.getLogTag(this), "Save image failed: no MediaItem to save to");
				return false;
			}
			return true;
		}

		protected void onPostExecute(Boolean saveSucceeded) {
			if (saveSucceeded) {
				if (mCapturePreviewFrame || mCameraConfiguration.usingFrontCamera) {
					// have to play the shutter sound manually here, as we're just capturing a preview frame
					// use media player (rather than sound pool) so we can access the system media files
					MediaPlayer shutterSoundPlayer = new MediaPlayer(); // so that we can set the stream type
					shutterSoundPlayer.setAudioStreamType(AudioManager.STREAM_NOTIFICATION);
					// volume is a percentage of *current*, rather than maximum, so this is unnecessary
					// shutterSoundPlayer.setVolume(volume, volume);
					try {
						shutterSoundPlayer.setDataSource(CameraActivity.this, Uri.parse(mCameraShutterSoundPath));
					} catch (IllegalArgumentException e) {
					} catch (SecurityException e) {
					} catch (IllegalStateException e) {
					} catch (IOException e) {
					}
					shutterSoundPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
						@Override
						public void onCompletion(MediaPlayer mp) {
							mp.release();
						}
					});
					try {
						shutterSoundPlayer.prepare();
					} catch (IllegalStateException e) {
					} catch (IOException e) {
					}
					shutterSoundPlayer.start();
				}
				switchToPicture(true); // show the hint after they take a picture
				synchronized (mSavingInProgress) {
					mSavingInProgress = false;
					if (mBackPressedDuringPhoto) {
						mBackPressedDuringPhoto = false;
						onBackPressed();
					}
				}
			}
		}
	}

	private void switchToPicture() {
		switchToPicture(false);
	}

	private void switchToPicture(boolean showPictureHint) {
		mDisplayMode = DisplayMode.DISPLAY_PICTURE;

		MediaItem imageMediaItem = MediaManager.findMediaByInternalId(getContentResolver(), mMediaItemInternalId);
		if (imageMediaItem != null && imageMediaItem.getFile().exists()) { // TODO: should we switch to camera if false?
			Point screenSize = UIUtilities.getScreenSize(getWindowManager());
			Bitmap scaledBitmap = BitmapUtilities.loadAndCreateScaledBitmap(imageMediaItem.getFile().getAbsolutePath(),
					screenSize.x, screenSize.y, BitmapUtilities.ScalingLogic.FIT, true);
			((ImageView) findViewById(R.id.camera_result)).setImageBitmap(scaledBitmap);
		}

		findViewById(R.id.layout_camera_top_controls).setVisibility(View.GONE);
		findViewById(R.id.layout_camera_bottom_controls).setVisibility(View.GONE);
		findViewById(R.id.layout_image_controls).setVisibility(View.VISIBLE);

		// set visibility rather than stop so that we don't break autofocus
		if (mCameraView != null) {
			mCameraView.setVisibility(View.GONE);
		}

		UIUtilities.setNonFullScreen(getWindow());
		UIUtilities.actionBarVisibility(this, true);
		UIUtilities.setScreenOrientationFixed(this, false);

		// show the hint (but only if we're opening for the first time)
		if (showPictureHint) {
			UIUtilities.showToast(CameraActivity.this, R.string.retake_picture_hint);
		}
	}

	private void retakePicture() {
		if (mCameraView != null) {
			// *must* do this here - normally done in switchToCamera()
			UIUtilities.setScreenOrientationFixed(this, true);
			UIUtilities.actionBarVisibility(this, false);
			UIUtilities.setFullScreen(getWindow());
			findViewById(R.id.layout_image_controls).setVisibility(View.GONE);
			findViewById(R.id.layout_camera_top_controls).setVisibility(View.VISIBLE);
			findViewById(R.id.layout_camera_bottom_controls).setVisibility(View.VISIBLE);
			mDisplayMode = DisplayMode.TAKE_PICTURE;
			mCameraView.setVisibility(View.VISIBLE);
			findViewById(R.id.button_take_picture).setEnabled(true);
		} else {
			switchToCamera(mCameraConfiguration.usingFrontCamera);
		}
	}

	private void importImage() {
		Intent intent = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
		intent.setType("image/*");
		try {
			startActivityForResult(intent, R.id.intent_picture_import);
		} catch (ActivityNotFoundException e) {
			UIUtilities.showToast(CameraActivity.this, R.string.import_picture_unavailable);
		}
	}

	@Override
	protected void onBackgroundTaskProgressUpdate(int taskId) {
		if (taskId == Math.abs(R.id.split_frame_task_complete)) {
			mHasEditedPicture = false;
		} else if (taskId == Math.abs(R.id.import_external_media_failed)) {
			UIUtilities.showToast(CameraActivity.this, R.string.import_picture_failed);
		}
		super.onBackgroundTaskProgressUpdate(taskId); // *must* be after other tasks
	}

	private boolean performSwitchFrames(int itemId, boolean showOptionsMenu) {
		if (mMediaItemInternalId != null) {
			mHasEditedPicture = true; // so we return Activity.RESULT_OK
			if (mDisplayMode == DisplayMode.TAKE_PICTURE) { // so we exit properly
				mDisplayMode = DisplayMode.SWITCHING_FRAME;
			}
			MediaItem imageMediaItem = MediaManager.findMediaByInternalId(getContentResolver(), mMediaItemInternalId);
			return switchFrames(imageMediaItem.getParentId(), itemId, R.string.extra_parent_id, showOptionsMenu,
					CameraActivity.class);
		}
		return false;
	}

	@Override
	protected boolean swipeNext() {
		return performSwitchFrames(R.id.menu_next_frame, false);
	}

	@Override
	protected boolean swipePrevious() {
		return performSwitchFrames(R.id.menu_previous_frame, false);
	}

	private void setFlashButtonIcon(String flashMode) {
		int currentDrawable = R.drawable.ic_flash_auto; // auto mode is the default
		if (Camera.Parameters.FLASH_MODE_OFF.equals(flashMode)) {
			currentDrawable = R.drawable.ic_flash_off;
		} else if (Camera.Parameters.FLASH_MODE_ON.equals(flashMode)) {
			currentDrawable = R.drawable.ic_flash_on;
		} else if (Camera.Parameters.FLASH_MODE_RED_EYE.equals(flashMode)) {
			currentDrawable = R.drawable.ic_flash_red_eye;
		}

		Resources res = getResources();
		Bitmap currentBitmap = BitmapFactory.decodeResource(res, currentDrawable);
		CenteredImageTextButton imageButton = (CenteredImageTextButton) findViewById(R.id.button_toggle_flash);
		imageButton.setCompoundDrawablesWithIntrinsicBounds(
				null,
				new BitmapDrawable(res, BitmapUtilities.rotate(currentBitmap, mPreviewIconRotation,
						currentBitmap.getWidth() / 2, currentBitmap.getHeight() / 2)), null, null);
		imageButton.setTag(currentDrawable);
	}

	public void handleButtonClicks(View currentButton) {

		final int buttonId = currentButton.getId();

		switch (buttonId) {
			case R.id.button_cancel_camera:
			case R.id.button_finished_picture:
				onBackPressed();
				break;

			case R.id.button_switch_camera:
				if (mCameraConfiguration.hasFrontCamera && mCameraConfiguration.numberOfCameras > 1) {
					currentButton.setEnabled(false); // don't let them press twice
					switchToCamera(!mCameraConfiguration.usingFrontCamera);
				}
				break;

			case R.id.button_toggle_flash:
				String newFlashMode = mCameraView.toggleFlashMode();
				SharedPreferences frameIdSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME,
						Context.MODE_PRIVATE);
				SharedPreferences.Editor prefsEditor = frameIdSettings.edit();
				prefsEditor.putString(getString(R.string.key_camera_flash_mode), newFlashMode);
				prefsEditor.commit(); // apply is better, but only in API > 8
				setFlashButtonIcon(newFlashMode);
				break;

			case R.id.button_take_picture:
				synchronized (mSavingInProgress) {
					mBackPressedDuringPhoto = false;
					mSavingInProgress = true;
				}
				currentButton.setEnabled(false); // don't let them press twice
				mHasEditedPicture = true;
				// use preview frame capturing for quicker and smaller images (also avoids some corruption issues)
				if (mCapturePreviewFrame || mCameraConfiguration.usingFrontCamera) {
					mCameraView.capturePreviewFrame(mPreviewFrameCallback);
				} else {
					mCameraView.takePicture(null, null, mPictureJpegCallback);
				}
				break;

			case R.id.camera_view_root:
				if (mDisplayMode != DisplayMode.TAKE_PICTURE) {
					break;
				} // fine to follow through if we're not in picture mode
			case R.id.camera_result:
				retakePicture();
				break;

			case R.id.button_delete_picture:
				AlertDialog.Builder builder = new AlertDialog.Builder(CameraActivity.this);
				builder.setTitle(R.string.delete_image_confirmation);
				builder.setMessage(R.string.delete_image_hint);
				builder.setIcon(android.R.drawable.ic_dialog_alert);
				builder.setNegativeButton(android.R.string.cancel, null);
				builder.setPositiveButton(R.string.button_delete, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int whichButton) {
						ContentResolver contentResolver = getContentResolver();
						mHasEditedPicture = true;
						MediaItem imageToDelete = MediaManager.findMediaByInternalId(contentResolver,
								mMediaItemInternalId);
						imageToDelete.setDeleted(true);
						MediaManager.updateMedia(contentResolver, imageToDelete);
						UIUtilities.showToast(CameraActivity.this, R.string.delete_image_succeeded);
						onBackPressed();
					}
				});
				AlertDialog alert = builder.create();
				alert.show();
				break;

			case R.id.button_add_frame_camera:
				// no need to check an image exists - can only be pressed in photo view
				runBackgroundTask(getFrameSplitterRunnable(mMediaItemInternalId));
				break;

			case R.id.button_import_image:
				importImage();
				break;
		}
	}

	@Override
	public void onOrientationChanged(int newScreenOrientationDegrees) {
		if (newScreenOrientationDegrees == OrientationEventListener.ORIENTATION_UNKNOWN) {
			return;
		}

		mScreenOrientation = newScreenOrientationDegrees;

		// correct for initial display orientation
		Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
		int displayRotation = OrientationManager.getDisplayRotationDegrees(display.getRotation());
		int correctedRotation = ((newScreenOrientationDegrees - displayRotation + 360) % 360);

		// force to normal orientation to allow taking upside down pictures
		// TODO: do we want to do this, or keep the previous sideways orientation?
		if (correctedRotation == 180) {
			newScreenOrientationDegrees = displayRotation;
			correctedRotation = 0; // so the images appear upside down as a cue
		}

		mDisplayOrientation = CameraUtilities.getPreviewOrientationDegrees(newScreenOrientationDegrees,
				mCameraConfiguration.cameraOrientationDegrees, mCameraConfiguration.usingFrontCamera);
		if (mCameraView != null) {
			mCameraView.setRotation(mDisplayOrientation, mDisplayOrientation);
		}

		Resources res = getResources();
		Bitmap currentBitmap = BitmapFactory.decodeResource(res, android.R.drawable.ic_menu_camera);
		CenteredImageTextButton imageButton = (CenteredImageTextButton) findViewById(R.id.button_take_picture);
		imageButton.setCompoundDrawablesWithIntrinsicBounds(
				null,
				new BitmapDrawable(res, BitmapUtilities.rotate(currentBitmap, correctedRotation,
						currentBitmap.getWidth() / 2, currentBitmap.getHeight() / 2)), null, null);

		currentBitmap = BitmapFactory.decodeResource(res, android.R.drawable.ic_menu_gallery);
		imageButton = (CenteredImageTextButton) findViewById(R.id.button_import_image);
		imageButton.setCompoundDrawablesWithIntrinsicBounds(
				null,
				new BitmapDrawable(res, BitmapUtilities.rotate(currentBitmap, correctedRotation,
						currentBitmap.getWidth() / 2, currentBitmap.getHeight() / 2)), null, null);

		imageButton = (CenteredImageTextButton) findViewById(R.id.button_cancel_camera);
		if (imageButton.getVisibility() == View.VISIBLE) {
			currentBitmap = BitmapFactory.decodeResource(res, R.drawable.ic_finished_editing);
			imageButton.setCompoundDrawablesWithIntrinsicBounds(
					null,
					new BitmapDrawable(res, BitmapUtilities.rotate(currentBitmap, correctedRotation,
							currentBitmap.getWidth() / 2, currentBitmap.getHeight() / 2)), null, null);
		}

		imageButton = (CenteredImageTextButton) findViewById(R.id.button_switch_camera);
		if (imageButton.getVisibility() == View.VISIBLE) {
			currentBitmap = BitmapFactory.decodeResource(res, R.drawable.ic_switch_camera);
			imageButton.setCompoundDrawablesWithIntrinsicBounds(
					null,
					new BitmapDrawable(res, BitmapUtilities.rotate(currentBitmap, correctedRotation,
							currentBitmap.getWidth() / 2, currentBitmap.getHeight() / 2)), null, null);
		}

		// done differently as the icon changes each time it is pressed
		imageButton = (CenteredImageTextButton) findViewById(R.id.button_toggle_flash);
		if (imageButton.getVisibility() == View.VISIBLE) {
			Integer imageTag = (Integer) imageButton.getTag();
			if (imageTag != null) {
				currentBitmap = BitmapFactory.decodeResource(res, imageTag);
				imageButton.setCompoundDrawablesWithIntrinsicBounds(
						null,
						new BitmapDrawable(res, BitmapUtilities.rotate(currentBitmap, correctedRotation,
								currentBitmap.getWidth() / 2, currentBitmap.getHeight() / 2)), null, null);
			}
		}

		mPreviewIconRotation = correctedRotation;
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent resultIntent) {
		switch (requestCode) {
			case R.id.intent_picture_import:
				if (resultCode == RESULT_OK) {
					final Uri selectedImage = resultIntent.getData();
					if (selectedImage != null) {

						final String filePath;
						Cursor c = getContentResolver().query(selectedImage,
								new String[] { MediaStore.Images.Media.DATA }, null, null, null);
						if (c != null) {
							if (c.moveToFirst()) {
								filePath = c.getString(c.getColumnIndex(MediaStore.Images.Media.DATA));
							} else {
								UIUtilities.showToast(CameraActivity.this, R.string.import_picture_failed);
								filePath = null;
							}
							c.close();
						} else {
							UIUtilities.showToast(CameraActivity.this, R.string.import_picture_failed);
							filePath = null;
						}

						if (filePath != null) {
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
									MediaItem imageMediaItem = MediaManager.findMediaByInternalId(contentResolver,
											mMediaItemInternalId);
									if (imageMediaItem != null) {
										ContentProviderClient client = contentResolver
												.acquireContentProviderClient(selectedImage);
										AutoCloseInputStream inputStream = null;
										try {
											String fileExtension = IOUtilities.getFileExtension(filePath);
											ParcelFileDescriptor descriptor = client.openFile(selectedImage, "r");
											inputStream = new AutoCloseInputStream(descriptor);

											// copy to a temporary file so we can detect failure (i.e. connection)
											File tempFile = new File(imageMediaItem.getFile().getParent(),
													MediaPhoneProvider.getNewInternalId() + "." + fileExtension);
											IOUtilities.copyFile(inputStream, tempFile);

											if (tempFile.length() > 0) {
												imageMediaItem.setFileExtension(fileExtension);
												imageMediaItem.setType(MediaPhoneProvider.TYPE_IMAGE_BACK);
												// note: will leave old item behind if the extension has changed
												tempFile.renameTo(imageMediaItem.getFile());
												MediaManager.updateMedia(contentResolver, imageMediaItem);
												mHasEditedPicture = true; // for Activity.RESULT_OK & icon update
												mDisplayMode = DisplayMode.DISPLAY_PICTURE;
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
							UIUtilities.showToast(CameraActivity.this, R.string.import_picture_failed);
						}
					}
				}
				break;

			default:
				super.onActivityResult(requestCode, resultCode, resultIntent);
		}
	}
}
