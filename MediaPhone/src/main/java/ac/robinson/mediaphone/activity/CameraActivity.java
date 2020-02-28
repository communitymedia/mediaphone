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

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
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
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.view.animation.AnimationUtils;
import android.webkit.MimeTypeMap;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.RelativeLayout.LayoutParams;

import java.io.File;
import java.io.InputStream;
import java.util.concurrent.Semaphore;

import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediaphone.MediaPhoneActivity;
import ac.robinson.mediaphone.R;
import ac.robinson.mediaphone.provider.FrameItem;
import ac.robinson.mediaphone.provider.MediaItem;
import ac.robinson.mediaphone.provider.MediaManager;
import ac.robinson.mediaphone.provider.MediaPhoneProvider;
import ac.robinson.mediaphone.view.CameraView;
import ac.robinson.mediaphone.view.SystemUiHider;
import ac.robinson.util.BitmapUtilities;
import ac.robinson.util.CameraUtilities;
import ac.robinson.util.DebugUtilities;
import ac.robinson.util.IOUtilities;
import ac.robinson.util.OrientationManager;
import ac.robinson.util.UIUtilities;
import ac.robinson.view.AnimateDrawable;
import ac.robinson.view.CenteredImageTextButton;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;

public class CameraActivity extends MediaPhoneActivity implements OrientationManager.OrientationListener {

	private String mMediaItemInternalId = null;
	private boolean mHasEditedMedia = false;
	private boolean mImagePickerShown = false;
	private int mSwitchToLandscape = -1;

	private boolean mDoesNotHaveCamera;
	private CameraView mCameraView;
	private Camera mCamera;
	private CameraUtilities.CameraConfiguration mCameraConfiguration = new CameraUtilities.CameraConfiguration();
	private boolean mCameraErrorOccurred = false;
	private Boolean mSavingInProgress = false; // Boolean for synchronization
	private boolean mBackPressedDuringPhoto = false;

	private boolean mStopImageRotationAnimation;
	private int mDisplayOrientation = 0;
	private int mScreenOrientation = 0;
	private int mIconRotation = 0;

	// loaded properly from attrs and preferences when camera is initialised
	private int mJpegSaveQuality = 80;
	private String mCameraShutterSoundPath = null;
	private boolean mCapturePreviewFrame = false;
	private boolean mAddToMediaLibrary = false;

	private enum DisplayMode {
		DISPLAY_PICTURE, TAKE_PICTURE
	}

	private DisplayMode mDisplayMode;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.camera_view);

		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayShowTitleEnabled(true);
			actionBar.setDisplayHomeAsUpEnabled(true);
		}

		mDoesNotHaveCamera = !CameraUtilities.deviceHasCamera(getPackageManager());

		mDisplayMode = DisplayMode.TAKE_PICTURE;
		mMediaItemInternalId = null;

		// load previous id on screen rotation
		if (savedInstanceState != null) {
			mMediaItemInternalId = savedInstanceState.getString(getString(R.string.extra_internal_id));
			mHasEditedMedia = savedInstanceState.getBoolean(getString(R.string.extra_media_edited), true);
			mSwitchToLandscape = savedInstanceState.getInt(getString(R.string.extra_switch_to_landscape_camera), -1);
			mImagePickerShown = savedInstanceState.getBoolean(getString(R.string.extra_external_chooser_shown), false);
			if (mHasEditedMedia) {
				setBackButtonIcons(CameraActivity.this, R.id.button_finished_picture, 0, true);
			}
		}

		// better fullscreen - see: https://stackoverflow.com/a/50775459/1993220
		// before v20 we don't know the size of the android navigation bar, so it is very difficult to properly position
		// the activity's navigation buttons when we want true fullscreen (for camera preview) but a visible navigation bar
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
			findViewById(R.id.camera_view_root).setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
				@Override
				public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
					int[] controlsViews = new int[]{
							R.id.layout_camera_bottom_controls, R.id.layout_image_bottom_controls, R.id.layout_image_top_controls
					};

					for (int view : controlsViews) {
						View controlsView = findViewById(view);
						if (controlsView.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
							ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) controlsView.getLayoutParams();
							p.setMargins(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
									insets.getSystemWindowInsetRight(), insets
									.getSystemWindowInsetBottom());
							controlsView.requestLayout();
						}
					}
					return insets.consumeSystemWindowInsets();
				}
			});

			// note - we use this only to set the window dimensions accurately for padding (above); setFullScreen and
			// setNonFullScreen are still better elsewhere as they don't shift the content (would require refactoring to fix)
			SystemUiHider systemUiHider = new SystemUiHider(CameraActivity.this, findViewById(R.id.camera_view_root),
					SystemUiHider.FLAG_HIDE_NAVIGATION);
			systemUiHider.setup();
			systemUiHider.hide(); // TODO: this is a slightly hacky way to ensure the initial screen size doesn't jump on hide
			systemUiHider.show(); // (undo the above hide command so we still have controls visible on start)
		}

		// load the media itself
		loadMediaContainer();
	}

	@Override
	public void onSaveInstanceState(Bundle savedInstanceState) {
		// no need to save display mode as we don't allow rotation when actually taking a picture
		savedInstanceState.putString(getString(R.string.extra_internal_id), mMediaItemInternalId);
		savedInstanceState.putBoolean(getString(R.string.extra_media_edited), mHasEditedMedia);
		savedInstanceState.putInt(getString(R.string.extra_switch_to_landscape_camera), mSwitchToLandscape);
		savedInstanceState.putBoolean(getString(R.string.extra_external_chooser_shown), mImagePickerShown);
		super.onSaveInstanceState(savedInstanceState);
	}

	@Override
	protected void onResume() {
		super.onResume();
		SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		if (OrientationManager.isSupported(sensorManager)) {
			OrientationManager.startListening(sensorManager, this);
		}
		if (!mDoesNotHaveCamera && mDisplayMode == DisplayMode.TAKE_PICTURE) {
			// resume the camera (was released in onPause), or initiate on first launch
			switchToCamera(mCameraConfiguration.usingFrontCamera, false);
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
		final MediaItem imageMediaItem = MediaManager.findMediaByInternalId(contentResolver, mMediaItemInternalId);
		if (imageMediaItem != null) {
			switch (mDisplayMode) {
				case DISPLAY_PICTURE:
					if (imageMediaItem.getDeleted()) {
						// we've been deleted - propagate changes to our parent frame and any following frames
						inheritMediaAndDeleteItemLinks(imageMediaItem.getParentId(), imageMediaItem, null);
					} else if (mHasEditedMedia) {
						// imported, rotated or otherwise edited the image - update icons
						updateMediaFrameIcons(imageMediaItem, null);
					}
					break;

				case TAKE_PICTURE:
					// display if they took a picture, exit otherwise
					if (imageMediaItem.getFile().length() > 0) {
						// took a new picture (rather than just cancelling the camera) - update the icon
						if (mHasEditedMedia) {
							// update this frame's icon with the new image; propagate to following frames if applicable
							updateMediaFrameIcons(imageMediaItem, null);
							setBackButtonIcons(CameraActivity.this, R.id.button_finished_picture, 0, true);

							// if we do this then we can't tell whether to change icons on screen rotation; disabled
							// mHasEditedMedia = false; // we've saved the icon, so are no longer in edit mode
						}

						switchToPicture(true); // will change the display mode to DISPLAY_PICTURE
						return; // we're not exiting yet, but displaying the just-taken picture

					} else {
						// so we don't leave an empty stub
						imageMediaItem.setDeleted(true);
						MediaManager.updateMedia(contentResolver, imageMediaItem);

						// we've been deleted - propagate changes to our parent frame and any following frames
						inheritMediaAndDeleteItemLinks(imageMediaItem.getParentId(), imageMediaItem, null);
					}
					break;
			}

			// save the id of the frame we're part of so that the frame editor gets notified
			saveLastEditedFrame(imageMediaItem.getParentId());
		}

		setResult(mHasEditedMedia ? Activity.RESULT_OK : Activity.RESULT_CANCELED);
		super.onBackPressed();
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) { // TODO: onKeyDown instead?
		if (mDisplayMode == DisplayMode.TAKE_PICTURE) {
			switch (event.getKeyCode()) {
				case KeyEvent.KEYCODE_VOLUME_DOWN:
				case KeyEvent.KEYCODE_VOLUME_UP:
					View takePicture = findViewById(R.id.button_take_picture);
					if (takePicture.isEnabled()) {
						takePicture.performClick();
					}
					return true;
				case KeyEvent.KEYCODE_FOCUS:
				case KeyEvent.KEYCODE_CAMERA:
					return true; // handle these events so they don't launch the Camera app
			}
		}
		return super.dispatchKeyEvent(event);
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
		switch (item.getItemId()) {
			case R.id.menu_add_frame:
				final MediaItem imageMediaItem = MediaManager.findMediaByInternalId(getContentResolver(), mMediaItemInternalId);
				if (imageMediaItem != null && imageMediaItem.getFile().length() > 0) {
					final String newFrameId = insertFrameAfterMedia(imageMediaItem);
					final Intent addImageIntent = new Intent(CameraActivity.this, CameraActivity.class);
					addImageIntent.putExtra(getString(R.string.extra_parent_id), newFrameId);
					startActivity(addImageIntent);

					onBackPressed();
				} else {
					UIUtilities.showToast(CameraActivity.this, R.string.split_image_add_content);
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
		// capture preview frame low quality rear camera pictures (for front camera, this is always the case)
		mCapturePreviewFrame = !mediaPhoneSettings.getBoolean(getString(R.string.key_high_quality_pictures),
				getResources().getBoolean(R.bool.default_high_quality_pictures));

		mAddToMediaLibrary = mediaPhoneSettings.getBoolean(getString(R.string.key_pictures_to_media),
				getResources().getBoolean(R.bool.default_pictures_to_media));
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
		findViewById(R.id.button_cancel_camera).setVisibility(newVisibility);
		findViewById(R.id.button_finished_picture).setVisibility(newVisibility);

		// to enable or disable spanning, all we do is show/hide the interface - eg., items that already span will not be removed
		findViewById(R.id.button_toggle_mode_picture).setVisibility(mediaPhoneSettings.getBoolean(getString(R.string.key_spanning_media), getResources()
				.getBoolean(R.bool.default_spanning_media)) ? View.VISIBLE : View.GONE);
	}

	private void loadMediaContainer() {

		// first launch
		ContentResolver contentResolver = getContentResolver();
		boolean firstLaunch = mMediaItemInternalId == null;
		if (firstLaunch) {

			// editing an existing frame
			String parentInternalId = null;
			final Intent intent = getIntent();
			if (intent != null) {
				parentInternalId = intent.getStringExtra(getString(R.string.extra_parent_id));
			}
			if (parentInternalId == null) {
				UIUtilities.showToast(CameraActivity.this, R.string.error_loading_image_editor);
				mMediaItemInternalId = "-1"; // so we exit
				onBackPressed();
				return;
			}

			// get existing content if it exists (ignores links)
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
		final MediaItem imageMediaItem = MediaManager.findMediaByInternalId(contentResolver, mMediaItemInternalId);
		if (imageMediaItem != null) {
			updateSpanFramesButtonIcon(R.id.button_toggle_mode_picture, imageMediaItem.getSpanFrames(), false);

			if (mSwitchToLandscape < 0 && imageMediaItem.getFile().length() > 0) {
				if (imageMediaItem.getType() == MediaPhoneProvider.TYPE_IMAGE_FRONT) {
					mCameraConfiguration.usingFrontCamera = true;
				}
				switchToPicture(firstLaunch);
			} else {
				if (mDoesNotHaveCamera) {
					if (firstLaunch) {
						UIUtilities.showToast(CameraActivity.this, R.string.error_taking_picture_no_camera, true);
					}
					switchToCamera(false, false);
				}
				// normal camera is now handled in onResume() so that we only load once
				// switchToCamera(false, mSwitchToLandscape < 0 ? firstLaunch : false); // prefer back by default
			}
		} else {
			UIUtilities.showToast(CameraActivity.this, R.string.error_loading_image_editor);
			onBackPressed();
			return;
		}
	}

	private void releaseCamera() {
		if (mCameraView != null) {
			mCameraView.setCamera(null, 0, 0, 0, 0, null, null);
		}

		// camera object is a shared resource - must be released
		if (mCamera != null) {
			mCamera.release();
			mCamera = null;
		}

		// must be done after releasing the camera (otherwise we release the active surface...)
		if (mCameraView != null) {
			mCameraView.removeAllViews();
			RelativeLayout viewRoot = findViewById(R.id.camera_view_root);
			if (viewRoot != null) {
				viewRoot.removeView(mCameraView);
			}
			mCameraView = null;
		}

		mCameraErrorOccurred = false;
	}

	private boolean configurePreCameraView() {
		mDisplayMode = DisplayMode.TAKE_PICTURE;

		// disable screen rotation while in the camera, and cope with devices that only support landscape pictures
		UIUtilities.setScreenOrientationFixed(this, true); // before landscape check so we don't switch back
		UIUtilities.setFullScreen(getWindow()); // before landscape so we know the full size of the display
		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.hide(); // before landscape so we know the full size of the display
		}
		if (DebugUtilities.supportsLandscapeCameraOnly()) {
			WindowManager windowManager = getWindowManager();
			int newRotation = -1;
			if (mSwitchToLandscape < 0) {
				mSwitchToLandscape = UIUtilities.getScreenRotationDegrees(windowManager);
			} else {
				newRotation = UIUtilities.getScreenRotationDegrees(windowManager);
			}

			boolean naturallyPortrait =
					UIUtilities.getNaturalScreenOrientation(windowManager) == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
			if ((newRotation == 0 && !naturallyPortrait) || (newRotation == 90 && naturallyPortrait)) {

				// coming from a portrait orientation - switch the parent layout to vertical and align right
				final int matchParent = LayoutParams.MATCH_PARENT;
				final int buttonHeight = getResources().getDimensionPixelSize(R.dimen.navigation_button_height);
				LinearLayout controlsLayout = findViewById(R.id.layout_camera_bottom_controls);
				RelativeLayout.LayoutParams controlsLayoutParams = new RelativeLayout.LayoutParams(buttonHeight, matchParent);
				controlsLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
				controlsLayout.setLayoutParams(controlsLayoutParams);
				controlsLayout.setOrientation(LinearLayout.VERTICAL);

				// need to reverse the order of the buttons so they still appear left to right properly
				View pictureButton = findViewById(R.id.button_take_picture);
				pictureButton.setLayoutParams(new LinearLayout.LayoutParams(matchParent, matchParent, 1));

				View importButton = findViewById(R.id.button_import_image);
				controlsLayout.removeView(importButton);
				importButton.setLayoutParams(new LinearLayout.LayoutParams(matchParent, matchParent, 1));
				controlsLayout.addView(importButton);

				View cancelButton = findViewById(R.id.button_cancel_camera);
				controlsLayout.removeView(cancelButton);
				cancelButton.setLayoutParams(new LinearLayout.LayoutParams(matchParent, matchParent, 1));
				controlsLayout.addView(cancelButton);

				// move the flash and switch camera buttons to the right places
				RelativeLayout.LayoutParams flashLayoutParams = new RelativeLayout.LayoutParams(buttonHeight,
						LayoutParams.WRAP_CONTENT);
				flashLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
				flashLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
				findViewById(R.id.button_toggle_flash).setLayoutParams(flashLayoutParams);

				RelativeLayout.LayoutParams switchCameraLayoutParams = new RelativeLayout.LayoutParams(buttonHeight,
						LayoutParams.WRAP_CONTENT);
				switchCameraLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
				switchCameraLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
				findViewById(R.id.button_switch_camera).setLayoutParams(switchCameraLayoutParams);

				if (MediaPhone.DEBUG) {
					Log.d(DebugUtilities.getLogTag(this),
							"Camera supports landscape only - starting in simulated portrait " + "mode");
				}

			} else if ((mSwitchToLandscape == 180 && !naturallyPortrait) || (mSwitchToLandscape == 270 && naturallyPortrait)) {

				// already in reverse landscape mode - switch to normal mode
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE); // shouldn't cause onCreate

				// align top to fake reverse landscape
				final int matchParent = LayoutParams.MATCH_PARENT;
				final int buttonHeight = getResources().getDimensionPixelSize(R.dimen.navigation_button_height);
				LinearLayout controlsLayout = findViewById(R.id.layout_camera_bottom_controls);
				RelativeLayout.LayoutParams controlsLayoutParams = new RelativeLayout.LayoutParams(matchParent, buttonHeight);
				controlsLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
				controlsLayout.setLayoutParams(controlsLayoutParams);

				// reverse button order
				View importButton = findViewById(R.id.button_import_image);
				controlsLayout.removeView(importButton);
				controlsLayout.addView(importButton);

				View cancelButton = findViewById(R.id.button_cancel_camera);
				controlsLayout.removeView(cancelButton);
				controlsLayout.addView(cancelButton);

				// move the flash and switch camera buttons to the right places
				RelativeLayout.LayoutParams flashLayoutParams = new RelativeLayout.LayoutParams(buttonHeight,
						LayoutParams.WRAP_CONTENT);
				flashLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
				flashLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
				findViewById(R.id.button_toggle_flash).setLayoutParams(flashLayoutParams);

				RelativeLayout.LayoutParams switchCameraLayoutParams = new RelativeLayout.LayoutParams(buttonHeight,
						LayoutParams.WRAP_CONTENT);
				switchCameraLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
				switchCameraLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
				findViewById(R.id.button_switch_camera).setLayoutParams(switchCameraLayoutParams);

				if (MediaPhone.DEBUG) {
					Log.d(DebugUtilities.getLogTag(this),
							"Camera supports landscape only - starting in simulated reverse " + "landscape mode");
				}

			} else if ((mSwitchToLandscape == 0 && !naturallyPortrait) || (mSwitchToLandscape == 90 && naturallyPortrait)) {

				// already in normal landscape mode - fine unless we've previously been in portrait, so reset to normal
				final int matchParent = LayoutParams.MATCH_PARENT;
				final int buttonHeight = getResources().getDimensionPixelSize(R.dimen.navigation_button_height);
				LinearLayout controlsLayout = findViewById(R.id.layout_camera_bottom_controls);
				RelativeLayout.LayoutParams controlsLayoutParams = new RelativeLayout.LayoutParams(matchParent, buttonHeight);
				controlsLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
				controlsLayout.setLayoutParams(controlsLayoutParams);
				controlsLayout.setOrientation(LinearLayout.HORIZONTAL);

				// re-order the buttons
				View cancelButton = findViewById(R.id.button_cancel_camera);
				controlsLayout.removeView(cancelButton);
				cancelButton.setLayoutParams(new LinearLayout.LayoutParams(matchParent, matchParent, 1));
				controlsLayout.addView(cancelButton);

				View importButton = findViewById(R.id.button_import_image);
				controlsLayout.removeView(importButton);
				importButton.setLayoutParams(new LinearLayout.LayoutParams(matchParent, matchParent, 1));
				controlsLayout.addView(importButton);

				View pictureButton = findViewById(R.id.button_take_picture);
				controlsLayout.removeView(pictureButton);
				pictureButton.setLayoutParams(new LinearLayout.LayoutParams(matchParent, matchParent, 1));
				controlsLayout.addView(pictureButton);

				// move the flash and switch camera buttons to the right places
				RelativeLayout.LayoutParams flashLayoutParams = new RelativeLayout.LayoutParams(LayoutParams.WRAP_CONTENT,
						buttonHeight);
				flashLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
				flashLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
				findViewById(R.id.button_toggle_flash).setLayoutParams(flashLayoutParams);

				RelativeLayout.LayoutParams switchCameraLayoutParams = new RelativeLayout.LayoutParams(buttonHeight,
						LayoutParams.WRAP_CONTENT);
				switchCameraLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
				switchCameraLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
				findViewById(R.id.button_switch_camera).setLayoutParams(switchCameraLayoutParams);

				if (MediaPhone.DEBUG) {
					Log.d(DebugUtilities.getLogTag(this), "Camera supports landscape only - already in landscape mode");
				}

			} else {
				// need to change the orientation from portrait - we will get here again after re-creating the activity
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE); // causes onCreate
				if (MediaPhone.DEBUG) {
					Log.d(DebugUtilities.getLogTag(this), "Camera supports landscape only - changing orientation");
				}
				return false;
			}
		} else {
			mSwitchToLandscape = -1;
		}
		return true;
	}

	private void configurePostCameraView() {
		findViewById(R.id.layout_image_top_controls).setVisibility(View.GONE);
		findViewById(R.id.layout_image_bottom_controls).setVisibility(View.GONE);
		View cameraTopControls = findViewById(R.id.layout_camera_top_controls);
		cameraTopControls.setVisibility(View.VISIBLE);
		cameraTopControls.bringToFront(); // to try to cope with a display bug on devices with a trackball
		View cameraBottomControls;
		cameraBottomControls = findViewById(R.id.layout_camera_bottom_controls);
		cameraBottomControls.setVisibility(View.VISIBLE);
		cameraBottomControls.bringToFront();// to try to cope with a display bug on devices with a trackball
	}

	private void switchToCamera(boolean preferFront, boolean showFocusHint) {
		releaseCamera();

		// can't take pictures without a camera present - import only (notification has already been shown)
		if (mDoesNotHaveCamera) {
			mDisplayMode = DisplayMode.TAKE_PICTURE;
			findViewById(R.id.layout_camera_top_controls).setVisibility(View.GONE);
			findViewById(R.id.layout_camera_bottom_controls).setVisibility(View.GONE);
			if (!mImagePickerShown) {
				// if the screen rotates while the image picker is being displayed, we end up showing it again
				// - this is probably a rare issue, but very frustrating when it does happen
				importImage();
			}
			return;
		}

		// configurePreCameraView() sets mDisplayMode = DisplayMode.TAKE_PICTURE;
		if (!configurePreCameraView()) {
			return; // will be calling onCreate, so don't continue
		}

		// create the camera view if necessary (if releaseCamera() has been called, camera view will be null)
		if (mCameraView == null) {
			mCameraView = new CameraView(this);
			LayoutParams layoutParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
			layoutParams.setMargins(0, 0, 0, 0);
			((RelativeLayout) findViewById(R.id.camera_view_root)).addView(mCameraView, layoutParams);
		}

		configurePostCameraView();

		// TODO: new permissions model means we need to request access before doing this
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
			onBackPressed(); // camera failed (but it should exist, so cancel instead of loading images) TODO: load?
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

		int screenRotation = UIUtilities.getScreenRotationDegrees(getWindowManager());
		mDisplayOrientation = CameraUtilities.getPreviewOrientationDegrees(screenRotation,
				mCameraConfiguration.cameraOrientationDegrees, mCameraConfiguration.usingFrontCamera);
		SharedPreferences flashSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME, Context.MODE_PRIVATE);
		String flashMode = flashSettings.getString(getString(R.string.key_camera_flash_mode), null);
		mCameraView.setCamera(mCamera, mDisplayOrientation, mDisplayOrientation, mJpegSaveQuality, autofocusInterval, flashMode,
				mCameraErrorCallback);

		if (!mCameraView.canChangeFlashMode()) {
			findViewById(R.id.button_toggle_flash).setVisibility(View.GONE);
		} else {
			setFlashButtonIcon(flashMode);
			findViewById(R.id.button_toggle_flash).setVisibility(View.VISIBLE);
		}

		// must fix the buttons if we're in forced-landscape mode - use the saved screen rotation
		if (mSwitchToLandscape >= 0) {
			// TODO: this doesn't work for upside down portrait mode; non-forced-landscape still has orientation issues
			onOrientationChanged(mSwitchToLandscape);
		}

		findViewById(R.id.button_take_picture).setEnabled(true);
		if (showFocusHint && mCameraView.canAutoFocus()) {
			UIUtilities.showToast(CameraActivity.this, R.string.focus_camera_hint);
		}
	}

	// if startPreview fails in the camera view, we don't get a camera error - need to handle it separately
	private CameraView.ErrorCallback mCameraErrorCallback = new CameraView.ErrorCallback() {
		@Override
		public void onError(int error) {
			switch (error) {
				case CameraView.ErrorCallback.PREVIEW_FAILED:
					// TODO: can we do anything else here? could be called by switching to a declared but non-existent
					// front camera (e.g., in BlueStacks), or just a buggy driver - could switch cameras?
					UIUtilities.showToast(CameraActivity.this, R.string.error_camera_failed);

					// to forget the previous (possibly unsupported on this device) configuration
					mCameraConfiguration = new CameraUtilities.CameraConfiguration();
					mCameraErrorOccurred = true; // in case they try to re-take the picture

					onBackPressed();
					break;
				default:
					break;
			}
		}
	};

	// only used when taking a picture rather than capturing a preview frame
	private Camera.PictureCallback mPictureJpegCallback = new Camera.PictureCallback() {
		public void onPictureTaken(byte[] imageData, Camera c) {
			new SavePreviewFrameTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, imageData);
		}
	};

	// see: http://stackoverflow.com/questions/6469019/
	private Camera.PreviewCallback mPreviewFrameCallback = new Camera.PreviewCallback() {
		@Override
		public void onPreviewFrame(byte[] imageData, Camera camera) {
			new SavePreviewFrameTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, imageData);
		}
	};

	// TODO: move to normal queued/immediate background task?
	private class SavePreviewFrameTask extends AsyncTask<byte[], Void, Boolean> {

		@Override
		protected Boolean doInBackground(byte[]... params) {
			byte[] data = params[0];
			if (data == null) {
				if (MediaPhone.DEBUG) {
					Log.d(DebugUtilities.getLogTag(this), "SavePreviewFrameTask: data is null");
				}
				return false;
			}

			ContentResolver contentResolver = getContentResolver();
			MediaItem imageMediaItem = MediaManager.findMediaByInternalId(contentResolver, mMediaItemInternalId);
			if (imageMediaItem != null) {
				// TODO: if replacing an imported non-jpeg with a photo, this will leave the old file in place - delete
				imageMediaItem.setFileExtension(MediaPhone.EXTENSION_PHOTO_FILE);
				imageMediaItem.setType(mCameraConfiguration.usingFrontCamera ? MediaPhoneProvider.TYPE_IMAGE_FRONT :
						MediaPhoneProvider.TYPE_IMAGE_BACK);
				MediaManager.updateMedia(contentResolver, imageMediaItem);

				final CameraView.CameraImageConfiguration[] pictureConfig = new CameraView.CameraImageConfiguration[1];
				// if we fail below, most likely still saving during onDestroy - default to JPEG format
				pictureConfig[0] = new CameraView.CameraImageConfiguration();
				pictureConfig[0].imageFormat = ImageFormat.JPEG;
				if (mCameraView != null) {
					final Semaphore mutex = new Semaphore(0);
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							try {
								if (mCapturePreviewFrame || mCameraConfiguration.usingFrontCamera) {
									pictureConfig[0] = mCameraView.getPreviewConfiguration();
								} else {
									pictureConfig[0] = mCameraView.getPictureConfiguration();
								}
							} catch (Exception ignored) {
							}
							mutex.release();
						}
					});
					try {
						mutex.acquire();
					} catch (InterruptedException ignored) {
					}
				}

				if ((pictureConfig[0].imageFormat == ImageFormat.NV21) || (pictureConfig[0].imageFormat == ImageFormat.YUY2)) {
					if (MediaPhone.DEBUG) {
						Log.d(DebugUtilities.getLogTag(this), "Saving NV21/YUY2 to JPEG");
					}

					// correct for screen and camera display rotation
					// TODO: this is still not right all the time (though slightly mitigated by new rotation UI option)
					int rotation = CameraUtilities.getPreviewOrientationDegrees(mScreenOrientation, mDisplayOrientation,
							mCameraConfiguration.usingFrontCamera);
					rotation = (rotation + mScreenOrientation) % 360;

					if (!BitmapUtilities.saveYUYToJPEG(data, imageMediaItem.getFile(), pictureConfig[0].imageFormat,
							mJpegSaveQuality, pictureConfig[0].width, pictureConfig[0].height, rotation,
							mCameraConfiguration.usingFrontCamera)) {
						return false;
					}

				} else if (pictureConfig[0].imageFormat == ImageFormat.JPEG) {
					if (MediaPhone.DEBUG) {
						Log.d(DebugUtilities.getLogTag(this), "Directly writing JPEG to storage");
					}

					if (!BitmapUtilities.saveJPEGToJPEG(data, imageMediaItem.getFile(), mCameraConfiguration.usingFrontCamera)) {
						return false;
					}

				} else {
					// TODO: do we need to rotate this image?
					Bitmap rawBitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
					boolean success = false;
					if (rawBitmap != null) {
						success = BitmapUtilities.saveBitmap(rawBitmap, Bitmap.CompressFormat.JPEG, mJpegSaveQuality,
								imageMediaItem
								.getFile());
						rawBitmap.recycle();
					}
					if (rawBitmap == null || !success) {
						return false;
					}
				}

				mHasEditedMedia = true;

				if (mAddToMediaLibrary) {
					runImmediateBackgroundTask(getMediaLibraryAdderRunnable(imageMediaItem.getFile()
							.getAbsolutePath(), Environment.DIRECTORY_DCIM));
				}
				return true;

			} else {
				if (MediaPhone.DEBUG) {
					Log.d(DebugUtilities.getLogTag(this), "Save image failed: no MediaItem to save to");
				}
				return false;
			}
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
					shutterSoundPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
						@Override
						public void onCompletion(MediaPlayer mp) {
							mp.release();
						}
					});
					try {
						shutterSoundPlayer.setDataSource(CameraActivity.this, Uri.parse(mCameraShutterSoundPath));
						shutterSoundPlayer.prepare();
						shutterSoundPlayer.start();
					} catch (Throwable ignored) {
					}
				}

			} else {
				UIUtilities.showToast(CameraActivity.this, R.string.save_picture_failed);
			}

			onBackPressed();

			synchronized (mSavingInProgress) {
				mSavingInProgress = false;
				if (mBackPressedDuringPhoto) {
					mBackPressedDuringPhoto = false;
					onBackPressed(); // second press doesn't really work, but don't want pressing back while saving
				}
			}
		}
	}

	private void switchToPicture(boolean showPictureHint) {
		mDisplayMode = DisplayMode.DISPLAY_PICTURE;
		mSwitchToLandscape = -1; // don't switch back to landscape on rotation

		MediaItem imageMediaItem = MediaManager.findMediaByInternalId(getContentResolver(), mMediaItemInternalId);
		if (imageMediaItem != null && imageMediaItem.getFile().length() > 0) { // TODO: switch to camera if false?
			Point screenSize = UIUtilities.getScreenSize(getWindowManager());
			Bitmap scaledBitmap = BitmapUtilities.loadAndCreateScaledBitmap(imageMediaItem.getFile()
					.getAbsolutePath(), screenSize.x, screenSize.y, BitmapUtilities.ScalingLogic.FIT, true);
			((ImageView) findViewById(R.id.camera_result)).setImageBitmap(scaledBitmap);
		}

		findViewById(R.id.layout_camera_top_controls).setVisibility(View.GONE);
		findViewById(R.id.layout_camera_bottom_controls).setVisibility(View.GONE);
		findViewById(R.id.layout_image_top_controls).setVisibility(View.VISIBLE);
		findViewById(R.id.layout_image_bottom_controls).setVisibility(View.VISIBLE);

		// set visibility rather than stop so that we don't break autofocus
		if (mCameraView != null) {
			mCameraView.setVisibility(View.GONE);
		}

		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.show();
		}
		UIUtilities.setNonFullScreen(getWindow());
		UIUtilities.setScreenOrientationFixed(this, false);

		// show the hint (but only if we're opening for the first time)
		if (showPictureHint) {
			UIUtilities.showToast(CameraActivity.this, mDoesNotHaveCamera ? R.string.retake_picture_hint_no_camera :
					R.string.retake_picture_hint);
		}
	}

	private void retakePicture() {
		if (mCameraView != null) {
			// only display without re-creating if the orientation hasn't changed (otherwise we get incorrect rotation)
			int displayOrientation =
					CameraUtilities.getPreviewOrientationDegrees(UIUtilities.getScreenRotationDegrees(getWindowManager()),
							mCameraConfiguration.cameraOrientationDegrees, mCameraConfiguration.usingFrontCamera);
			if (mCameraView.getDisplayRotation() == displayOrientation && !mCameraErrorOccurred) {
				if (!configurePreCameraView()) {
					return; // will be calling onCreate
				}
				mCameraView.refreshCameraState();
				configurePostCameraView();
				mCameraView.setVisibility(View.VISIBLE);
				findViewById(R.id.button_take_picture).setEnabled(true);
			} else {
				switchToCamera(mCameraConfiguration.usingFrontCamera, false);
			}
		} else {
			switchToCamera(mCameraConfiguration.usingFrontCamera, false);
		}
	}

	private void importImage() {
		// Intent intent = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
		Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.setType("image/*");
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
			intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); // on later devices we can select more than one item
		}
		try {
			startActivityForResult(intent, MediaPhone.R_id_intent_picture_import);
			mImagePickerShown = true;
		} catch (ActivityNotFoundException e) {
			UIUtilities.showToast(CameraActivity.this, R.string.import_picture_unavailable);
			if (mDoesNotHaveCamera) {
				onBackPressed(); // we can't do anything else here
			}
		}
	}

	@Override
	protected void onBackgroundTaskCompleted(int taskId) {
		switch (taskId) {
			case R.id.import_external_media_succeeded:
				mHasEditedMedia = true; // to force an icon update
				onBackPressed();
				break;
			case R.id.import_external_media_failed:
			case R.id.import_external_media_cancelled:
				if (taskId == R.id.import_external_media_failed) {
					UIUtilities.showToast(CameraActivity.this, R.string.import_picture_failed);
				}
				if (mDoesNotHaveCamera) {
					onBackPressed(); // we can't do anything else here
				}
				break;
			case R.id.import_multiple_external_media_succeeded:
				UIUtilities.showToast(CameraActivity.this, R.string.import_multiple_items_succeeded);
				mHasEditedMedia = true; // to force an icon update
				break;
			case R.id.import_multiple_external_media_failed:
				UIUtilities.showToast(CameraActivity.this, R.string.import_multiple_items_failed);
				mHasEditedMedia = true; // to force an icon update
				break;
			case R.id.image_rotate_completed:
				mStopImageRotationAnimation = true;
				mHasEditedMedia = true; // to force an icon update
				setBackButtonIcons(CameraActivity.this, R.id.button_finished_picture, 0, true); // changed the image
				MediaItem imageMediaItem = MediaManager.findMediaByInternalId(getContentResolver(), mMediaItemInternalId);
				if (imageMediaItem != null) {
					loadScreenSizedImageInBackground((ImageView) findViewById(R.id.camera_result), imageMediaItem.getFile()
							.getAbsolutePath(), true, MediaPhoneActivity.FadeType.FADEIN); // reload image
				}
				findViewById(R.id.button_rotate_clockwise).setEnabled(true);
				findViewById(R.id.button_rotate_anticlockwise).setEnabled(true);
				break;
			default:
				break;
		}
	}

	private void setFlashButtonIcon(String flashMode) {
		int currentDrawable = R.drawable.ic_image_flash_auto; // auto mode is the default
		if (Camera.Parameters.FLASH_MODE_OFF.equals(flashMode)) {
			currentDrawable = R.drawable.ic_image_flash_off;
		} else if (Camera.Parameters.FLASH_MODE_ON.equals(flashMode)) {
			currentDrawable = R.drawable.ic_image_flash_on;
		} else if (Camera.Parameters.FLASH_MODE_RED_EYE.equals(flashMode)) {
			currentDrawable = R.drawable.ic_image_flash_redeye;
		}

		Resources res = getResources();
		Bitmap currentBitmap = BitmapFactory.decodeResource(res, currentDrawable);
		CenteredImageTextButton imageButton = findViewById(R.id.button_toggle_flash);
		imageButton.setCompoundDrawablesWithIntrinsicBounds(null, new BitmapDrawable(res, BitmapUtilities.rotate(currentBitmap,
				mIconRotation,
				currentBitmap.getWidth() / 2, currentBitmap.getHeight() / 2)), null, null);
		imageButton.setTag(currentDrawable);
	}

	public void handleButtonClicks(View currentButton) {
		if (!verifyButtonClick(currentButton)) {
			return;
		}

		switch (currentButton.getId()) {
			case R.id.button_cancel_camera:
			case R.id.button_finished_picture:
				onBackPressed();
				break;

			case R.id.button_switch_camera:
				if (mCameraConfiguration.hasFrontCamera && mCameraConfiguration.numberOfCameras > 1) {
					currentButton.setEnabled(false); // don't let them press twice
					switchToCamera(!mCameraConfiguration.usingFrontCamera, false);
				}
				break;

			case R.id.button_toggle_flash:
				if (mCameraView != null) {
					String newFlashMode = mCameraView.toggleFlashMode();
					SharedPreferences flashSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME, Context.MODE_PRIVATE);
					SharedPreferences.Editor prefsEditor = flashSettings.edit();
					prefsEditor.putString(getString(R.string.key_camera_flash_mode), newFlashMode);
					prefsEditor.apply();
					setFlashButtonIcon(newFlashMode);
				} else {
					// TODO: second most common crash on Google Play is NPE here: relaunch camera? restart activity?
				}
				break;

			case R.id.button_rotate_clockwise:
			case R.id.button_rotate_anticlockwise:
				handleRotateImageClick(currentButton);
				break;

			case R.id.button_take_picture:
				if (mCameraView != null) {
					currentButton.setEnabled(false); // don't let them press twice
					synchronized (mSavingInProgress) {
						mBackPressedDuringPhoto = false;
						mSavingInProgress = true;
					}

					// use preview frame capturing for quicker and smaller images (also avoids some corruption issues)
					if (mCapturePreviewFrame || mCameraConfiguration.usingFrontCamera) {
						mCameraView.capturePreviewFrame(mPreviewFrameCallback);
					} else {
						mCameraView.takePicture(null, null, mPictureJpegCallback);
					}
				} else {
					// TODO: second most common crash on Google Play is NPE here: relaunch camera? restart activity?
				}
				break;

			case R.id.camera_view_root: // (no-longer clickable, but may be in future, so left here)
				if (mDisplayMode != DisplayMode.TAKE_PICTURE) {
					break;
				} // fine to follow through if we're not in picture mode
			case R.id.camera_result:
				retakePicture();
				break;

			case R.id.button_toggle_mode_picture:
				final MediaItem imageMediaItem = MediaManager.findMediaByInternalId(getContentResolver(), mMediaItemInternalId);
				if (imageMediaItem != null && imageMediaItem.getFile().length() > 0) {
					mHasEditedMedia = true; // so we update/inherit on exit and show the media edited icon
					setBackButtonIcons(CameraActivity.this, R.id.button_finished_picture, 0, true);
					boolean frameSpanning = toggleFrameSpanningMedia(imageMediaItem);
					updateSpanFramesButtonIcon(R.id.button_toggle_mode_picture, frameSpanning, true);
					UIUtilities.showToast(CameraActivity.this, frameSpanning ? R.string.span_image_multiple_frames :
							R.string.span_image_single_frame);
				} else {
					UIUtilities.showToast(CameraActivity.this, R.string.span_image_add_content);
				}
				break;

			case R.id.button_delete_picture:
				AlertDialog.Builder builder = new AlertDialog.Builder(CameraActivity.this);
				builder.setTitle(R.string.delete_image_confirmation);
				builder.setMessage(R.string.delete_image_hint);
				builder.setNegativeButton(R.string.button_cancel, null);
				builder.setPositiveButton(R.string.button_delete, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int whichButton) {
						ContentResolver contentResolver = getContentResolver();
						MediaItem imageToDelete = MediaManager.findMediaByInternalId(contentResolver, mMediaItemInternalId);
						if (imageToDelete != null) {
							mHasEditedMedia = true; // so the frame editor updates its display
							imageToDelete.setDeleted(true);
							MediaManager.updateMedia(contentResolver, imageToDelete);
							UIUtilities.showToast(CameraActivity.this, R.string.delete_image_succeeded);
							onBackPressed();
						}
					}
				});
				builder.show();
				break;

			case R.id.button_import_image:
				importImage();
				break;

			default:
				break;
		}
	}

	private void handleRotateImageClick(View currentButton) {
		currentButton.setEnabled(false); // don't let them press twice
		int currentButtonId = currentButton.getId();
		int otherButtonId =
				currentButtonId == R.id.button_rotate_clockwise ? R.id.button_rotate_anticlockwise :
						R.id.button_rotate_clockwise;
		findViewById(otherButtonId).setEnabled(false); // don't let them press the other button

		MediaItem imageMediaItem = MediaManager.findMediaByInternalId(getContentResolver(), mMediaItemInternalId);
		if (imageMediaItem != null) {
			final String imagePath = imageMediaItem.getFile().getAbsolutePath();
			final boolean rotateAntiClockwise = currentButtonId == R.id.button_rotate_anticlockwise;
			mHasEditedMedia = true; // so we regenerate the frame's icon

			// animate the button - use a listener to repeat until done, then stop gracefully after a last pass
			mStopImageRotationAnimation = false;
			final Animation rotationAnimation = AnimationUtils.loadAnimation(this, R.anim.rotate_clockwise_360);
			rotationAnimation.setAnimationListener(new AnimationListener() {
				@Override
				public void onAnimationEnd(Animation arg0) {
					if (!mStopImageRotationAnimation) {
						rotationAnimation.startNow();
					}
				}

				@Override
				public void onAnimationRepeat(Animation arg0) {
				}

				@Override
				public void onAnimationStart(Animation arg0) {
				}
			});
			currentButton.startAnimation(rotationAnimation);

			// do the actual rotation in a background thread
			runQueuedBackgroundTask(new BackgroundRunnable() {
				@Override
				public int getTaskId() {
					return R.id.image_rotate_completed;
				}

				@Override
				public boolean getShowDialog() {
					return false;
				}

				@Override
				public void run() {
					BitmapUtilities.rotateImage(imagePath, rotateAntiClockwise);
				}
			});
		} else {
			// we don't run the task, so won't re-enable otherwise
			findViewById(currentButtonId).setEnabled(true);
			findViewById(otherButtonId).setEnabled(true);
		}
	}

	private void animateButtonRotation(Resources res, int animation, int button, int icon, int previousRotation) {
		CenteredImageTextButton imageButton = findViewById(button);
		Bitmap currentBitmap = BitmapFactory.decodeResource(res, icon);
		if (currentBitmap == null) { // the take picture icon is an xml drawable - it must be loaded as such
			Drawable bitmapDrawable = res.getDrawable(icon);
			if (bitmapDrawable instanceof BitmapDrawable) {
				currentBitmap = ((BitmapDrawable) bitmapDrawable).getBitmap();
			}
		}
		if (currentBitmap != null) {
			Drawable buttonIcon = new BitmapDrawable(res, BitmapUtilities.rotate(currentBitmap, previousRotation,
					currentBitmap.getWidth() / 2, currentBitmap.getHeight() / 2));
			buttonIcon.setBounds(0, 0, buttonIcon.getIntrinsicWidth(), buttonIcon.getIntrinsicHeight());
			Animation rotationAnimation = AnimationUtils.loadAnimation(this, animation);
			rotationAnimation.initialize(buttonIcon.getIntrinsicWidth(), buttonIcon.getIntrinsicHeight(), imageButton.getWidth()
					, imageButton
					.getHeight());
			rotationAnimation.start();
			imageButton.setCompoundDrawablesWithIntrinsicBounds(null, new AnimateDrawable(buttonIcon, rotationAnimation), null,
					null);
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

		// disabled for now as it is incorrect for forced landscape (also, debatable as to whether it makes any sense)
		// force to normal orientation to allow taking upside down pictures
		// if (correctedRotation == 180) {
		// newScreenOrientationDegrees = displayRotation;
		// correctedRotation = 0; // so the images appear upside down as a cue
		// }

		mDisplayOrientation = CameraUtilities.getPreviewOrientationDegrees(newScreenOrientationDegrees,
				mCameraConfiguration.cameraOrientationDegrees, mCameraConfiguration.usingFrontCamera);
		if (mCameraView != null) {
			mCameraView.setRotation(mDisplayOrientation, mDisplayOrientation);
		}

		// we only need to animate rotations if we're in camera mode
		if (mDisplayMode == DisplayMode.TAKE_PICTURE) {

			// get the difference between the current and previous orientations
			int animation = 0;
			int rotationDifference = ((mIconRotation - correctedRotation + 360) % 360);
			switch (rotationDifference) {
				case 270:
					animation = R.anim.rotate_clockwise_90;
					break;
				case 90:
					animation = R.anim.rotate_anticlockwise_90;
					break;
				case 180:
					animation = R.anim.rotate_clockwise_180;
					break;
				default:
					break;
			}

			if (animation == 0) {
				return; // no need to change icons - no difference in orientation
			}

			// animate rotating the button icons
			Resources res = getResources();
			animateButtonRotation(res, animation, R.id.button_take_picture, R.drawable.ic_menu_take_picture, mIconRotation);
			animateButtonRotation(res, animation, R.id.button_import_image, R.drawable.ic_menu_import_picture, mIconRotation);

			if (findViewById(R.id.button_cancel_camera).getVisibility() == View.VISIBLE) {
				animateButtonRotation(res, animation, R.id.button_cancel_camera, R.drawable.ic_menu_back, mIconRotation);
			}

			if (findViewById(R.id.button_switch_camera).getVisibility() == View.VISIBLE) {
				animateButtonRotation(res, animation, R.id.button_switch_camera, R.drawable.ic_image_switch_camera,
						mIconRotation);
			}

			// the flash button is done differently as its icon changes each time it is pressed
			View flashButton = findViewById(R.id.button_toggle_flash);
			if (flashButton.getVisibility() == View.VISIBLE) {
				Integer imageTag = (Integer) flashButton.getTag();
				if (imageTag != null) {
					animateButtonRotation(res, animation, R.id.button_toggle_flash, imageTag, mIconRotation);
				}
			}
		}

		// need the current rotation for comparison next time we rotate
		mIconRotation = correctedRotation;
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent resultIntent) {
		switch (requestCode) {
			case MediaPhone.R_id_intent_picture_import:
				mImagePickerShown = false;

				handleMediaImport(resultCode, resultIntent, mMediaItemInternalId, new ImportMediaCallback() {
					@Override
					public boolean importMedia(MediaItem mediaItem, Uri selectedItemUri) {
						ContentResolver contentResolver = getContentResolver();
						InputStream inputStream = null;
						try {
							String fileExtension = MimeTypeMap.getSingleton()
									.getExtensionFromMimeType(contentResolver.getType(selectedItemUri));
							if (TextUtils.isEmpty(fileExtension)) {
								fileExtension = "jpg"; // no match in the mime type map - guess at most common file extension
							}

							// copy to a temporary file so we can detect failure (i.e. connection)
							inputStream = contentResolver.openInputStream(selectedItemUri);
							File tempFile = new File(mediaItem.getFile().getParent(),
									MediaPhoneProvider.getNewInternalId() + "." + fileExtension);
							IOUtilities.copyFile(inputStream, tempFile);

							if (tempFile.length() > 0) {
								mediaItem.setFileExtension(fileExtension);
								mediaItem.setType(MediaPhoneProvider.TYPE_IMAGE_BACK);

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
