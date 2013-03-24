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

package ac.robinson.mediaphone.view;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediaphone.R;
import ac.robinson.util.DebugUtilities;
import ac.robinson.util.UIUtilities;
import android.content.Context;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.media.AudioManager;
import android.media.SoundPool;
import android.media.SoundPool.OnLoadCompleteListener;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

//see: http://developer.android.com/resources/samples/ApiDemos/src/com/example/android/apis/graphics/CameraPreview.html
public class CameraView extends ViewGroup implements SurfaceHolder.Callback {

	private SurfaceView mSurfaceView;
	private SurfaceHolder mHolder;
	private Size mPreviewSize;
	private List<Size> mSupportedPreviewSizes;
	private Size mDefaultPreviewSize;
	private Size mPictureSize;
	private List<Size> mSupportedPictureSizes;
	private Size mDefaultPictureSize;
	private Camera mCamera;

	private int mDisplayRotation;
	private int mCameraRotation;
	private boolean mTakePicture;
	private boolean mStopPreview;
	private boolean mCanAutoFocus;
	private boolean mCanChangeFlashMode;
	private boolean mIsAutoFocusing;
	private boolean mPreviewStarted;
	private int mAutoFocusInterval;
	private Point mScreenSize;
	private boolean mLandscapeCameraOnly;

	private AutoFocusHandler mAutoFocusHandler;
	private AutoFocusCallback mAutoFocusCallback;
	private ErrorCallback mErrorCallback;

	private SoundPool mFocusSoundPlayer;
	private int mFocusSoundId;

	public static class CameraImageConfiguration {
		public int imageFormat;
		public int width;
		public int height;
	}

	public interface ErrorCallback {
		static final int PREVIEW_FAILED = -1;

		void onError(int error);
	}

	public CameraView(Context context) {
		super(context);
		mScreenSize = UIUtilities.getScreenSize((WindowManager) context.getSystemService(Context.WINDOW_SERVICE));

		setBackgroundColor(Color.BLACK);

		mSurfaceView = new SurfaceView(context);
		addView(mSurfaceView);

		// install callback so we're notified when surface is created/destroyed
		mHolder = mSurfaceView.getHolder();
		mHolder.addCallback(this);
		UIUtilities.setPushBuffers(mHolder);

		mIsAutoFocusing = false;
		mTakePicture = false;
		mStopPreview = false;
		mCanAutoFocus = false;
		mPreviewStarted = false;

		// need to cope with old, landscape-only devices (both in display, and buggy picture taking)
		mLandscapeCameraOnly = DebugUtilities.supportsLandscapeCameraOnly();

		mAutoFocusHandler = new AutoFocusHandler();
		mAutoFocusCallback = new AutoFocusCallback();
	}

	public void surfaceCreated(SurfaceHolder holder) {
		// surface has been created, acquire camera and tell it where to draw
		if (mCamera != null) {
			try {
				mCamera.setPreviewDisplay(holder);
			} catch (Exception e) {
				if (MediaPhone.DEBUG)
					Log.d(DebugUtilities.getLogTag(this), "surfaceCreated() -> setPreviewDisplay()", e);
			}
		}
		mFocusSoundId = -1;
		mFocusSoundPlayer = new SoundPool(1, AudioManager.STREAM_NOTIFICATION, 0);
		mFocusSoundPlayer.setOnLoadCompleteListener(new OnLoadCompleteListener() {
			@Override
			public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
				mFocusSoundId = sampleId;
			}
		});
		mFocusSoundPlayer.load(getContext(), R.raw.af_success, 1);
	}

	public void surfaceDestroyed(SurfaceHolder holder) {
		// surface will be destroyed when we return, so stop focus and preview
		mAutoFocusCallback.setHandler(null);
		if (mCamera != null) {
			mCamera.stopPreview();
			try {
				mCamera.setPreviewDisplay(null);
			} catch (Exception e) {
				if (MediaPhone.DEBUG)
					Log.d(DebugUtilities.getLogTag(this), "surfaceDestroyed() -> setPreviewDisplay()", e);
			}
		}
		if (mFocusSoundPlayer != null) {
			mFocusSoundPlayer.unload(mFocusSoundId);
			mFocusSoundPlayer.release();
			mFocusSoundPlayer = null;
		}
	}

	public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
		// we now know the surface size - set up the display and begin the preview.
		requestLayout();

		if (mCamera != null && !mPreviewStarted) {
			Camera.Parameters parameters = mCamera.getParameters();

			// supported preview and picture sizes checked earlier
			// TODO: do we need to worry about swapping these to account for screen rotation, or will setRotation be ok?
			parameters.setPreviewSize(mPreviewSize.width, mPreviewSize.height);
			parameters.setPictureSize(mPictureSize.width, mPictureSize.height);

			parameters.setRotation(mCameraRotation);
			mCamera.setDisplayOrientation(mDisplayRotation);

			mCamera.setParameters(parameters);

			startCameraPreview();
		}
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		// purposely disregard child measurements - we want to centre the camera preview instead of stretching it
		final int width = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
		final int height = resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec);
		setMeasuredDimension(width, height);

		// we go fullscreen, so calculate the final size, rather than resizing the camera after initial launch
		int actualWidth, actualHeight;
		if (width == Math.max(width, height)) {
			actualWidth = Math.max(mScreenSize.x, mScreenSize.y);
			actualHeight = Math.min(mScreenSize.x, mScreenSize.y);
		} else {
			actualWidth = Math.min(mScreenSize.x, mScreenSize.y);
			actualHeight = Math.max(mScreenSize.x, mScreenSize.y);
		}

		if (mSupportedPreviewSizes != null) {
			mPreviewSize = getBestPreviewSize(mSupportedPreviewSizes, mDefaultPreviewSize, actualWidth, actualHeight);
		}
		if (mSupportedPictureSizes != null) {
			mPictureSize = getBestPictureSize(mSupportedPictureSizes, mDefaultPictureSize, actualWidth, actualHeight);
		}
	}

	@Override
	protected void onLayout(boolean changed, int l, int t, int r, int b) {
		if (changed && getChildCount() > 0) {
			final View child = getChildAt(0);

			final int width = r - l;
			final int height = b - t;

			int previewWidth = width;
			int previewHeight = height;
			if (mPreviewSize != null) {
				// TODO: cope with other rotations (i.e. devices that are not in portrait by default?)
				if ((mDisplayRotation == 90 || mDisplayRotation == 270) && !mLandscapeCameraOnly) {
					previewWidth = mPreviewSize.height;
					previewHeight = mPreviewSize.width;
				} else {
					previewWidth = mPreviewSize.width;
					previewHeight = mPreviewSize.height;
				}
			}

			// centre the child SurfaceView within the parent.
			if (width * previewHeight > height * previewWidth) {
				final int scaledChildWidth = previewWidth * height / previewHeight;
				child.layout((width - scaledChildWidth) / 2, 0, (width + scaledChildWidth) / 2, height);
			} else {
				final int scaledChildHeight = previewHeight * width / previewWidth;
				child.layout(0, (height - scaledChildHeight) / 2, width, (height + scaledChildHeight) / 2);
				// child.layout(0, 0, width, scaledChildHeight); // for horizontal centering only
			}
		}
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		switch (event.getAction()) {
			case MotionEvent.ACTION_DOWN:
				requestAutoFocus(mAutoFocusHandler);
				return true; // processed
			default:
				return super.onTouchEvent(event);
		}
	}

	/**
	 * 
	 * 
	 * @param camera
	 * @param rotation
	 * @param jpegQuality
	 * @param autoFocusInterval Set to 0 to disable automatic refocusing
	 */
	public void setCamera(Camera camera, int displayRotation, int cameraRotation, int jpegQuality,
			int autoFocusInterval, String flashMode, ErrorCallback errorCallback) {
		mErrorCallback = errorCallback;
		mCamera = camera;
		mDisplayRotation = displayRotation;
		mCameraRotation = cameraRotation;
		mAutoFocusInterval = autoFocusInterval;
		if (mCamera != null) {
			Camera.Parameters parameters = mCamera.getParameters();

			mSupportedPreviewSizes = parameters.getSupportedPreviewSizes();
			mDefaultPreviewSize = parameters.getPreviewSize();
			mSupportedPictureSizes = parameters.getSupportedPictureSizes();
			mDefaultPictureSize = parameters.getPictureSize();
			// parameters.setJpegThumbnailSize(0, 0); // smaller files, but enabling thumbs speeds up normal gallery

			// List<Integer> previewFormats = parameters.getSupportedPreviewFormats();
			// if (previewFormats != null) { // the documentation lies
			// if (previewFormats.contains(ImageFormat.YUY2)) {
			// parameters.setPreviewFormat(ImageFormat.YUY2);
			// }
			// }

			List<Integer> imageFormats = null;
			try {
				imageFormats = parameters.getSupportedPictureFormats();
			} catch (NullPointerException e) {
				// on some devices without cameras (perhaps emulator only?)
			}
			if (imageFormats != null) { // the documentation lies
				if (imageFormats.contains(ImageFormat.JPEG)) {
					parameters.setPictureFormat(ImageFormat.JPEG);
					if (jpegQuality > 0) {
						parameters.setJpegQuality(jpegQuality);
					}
				}
			}

			List<String> modes = parameters.getSupportedFlashModes();
			mCanChangeFlashMode = false;
			try {
				if (modes != null) {
					modes.remove(Camera.Parameters.FLASH_MODE_TORCH);
					int offMode = modes.indexOf(Camera.Parameters.FLASH_MODE_OFF);
					if (modes.size() > (offMode >= 0 ? 1 : 0)) {
						mCanChangeFlashMode = true;
						if (modes.contains(flashMode)) {
							parameters.setFlashMode(flashMode);
						} else if (modes.contains(Camera.Parameters.FLASH_MODE_AUTO)) { // default to auto flash
							parameters.setFlashMode(Camera.Parameters.FLASH_MODE_AUTO);
						}
					}
				}
			} catch (Exception e) {
			}

			// TODO: use Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE where supported
			modes = parameters.getSupportedFocusModes();
			mCanAutoFocus = false;
			if (modes != null) { // the documentation lies
				if (modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
					mCanAutoFocus = true;
				}
			}

			mCamera.setParameters(parameters);
			requestLayout();
		}
	}

	public void setRotation(int displayRotation, int cameraRotation) {
		mCameraRotation = cameraRotation;
		Camera.Parameters parameters = mCamera.getParameters();
		parameters.setRotation(mCameraRotation);
		mCamera.setParameters(parameters);
		requestLayout();
	}

	public int getDisplayRotation() {
		return mDisplayRotation;
	}

	private List<Size> sortSizes(List<Size> allSizes) {
		// sort sizes in descending order
		Collections.sort(allSizes, new Comparator<Size>() {
			@Override
			public int compare(Size s1, Size s2) {
				int s1Resolution = s1.width * s1.height;
				int s2Resolution = s2.width * s2.height;
				if (s2Resolution < s1Resolution) {
					return -1;
				}
				return (s2Resolution > s1Resolution) ? 1 : 0;
			}
		});
		return allSizes;
	}

	private Size getBestPreviewSize(List<Size> allSizes, Size defaultSize, int screenWidth, int screenHeight) {
		if (allSizes == null) {
			return defaultSize;
		}
		List<Size> sortedSizes = sortSizes(allSizes);

		Size bestSize = null;
		float screenAspectRatio = Math.min(screenWidth, screenHeight) / (float) Math.max(screenWidth, screenHeight);
		float difference = Float.MAX_VALUE;
		for (Size s : sortedSizes) {
			int sizePixels = s.width * s.height;
			if (sizePixels < MediaPhone.CAMERA_MIN_PREVIEW_PIXELS || sizePixels > MediaPhone.CAMERA_MAX_PREVIEW_PIXELS) {
				continue;
			}
			boolean sizePortrait = s.width < s.height;
			boolean screenPortrait = screenWidth < screenHeight;
			int sizeWidth = (sizePortrait ^ screenPortrait) ? s.height : s.width; // xor
			int sizeHeight = (sizePortrait ^ screenPortrait) ? s.width : s.height;

			if (sizeWidth == screenWidth && sizeHeight == screenHeight) {
				return s; // perfect: exactly matches screen size
			}
			float sizeAspectRatio = Math.min(sizeWidth, sizeHeight) / (float) Math.max(sizeWidth, sizeHeight);
			float newDiff = Math.abs(sizeAspectRatio - screenAspectRatio);
			if (newDiff < difference) {
				bestSize = s;
				difference = newDiff;
			}
		}
		return bestSize == null ? defaultSize : bestSize;
	}

	private Size getBestPictureSize(List<Size> allSizes, Size defaultSize, int screenWidth, int screenHeight) {
		if (allSizes == null) {
			return defaultSize;
		}
		List<Size> sortedSizes = sortSizes(allSizes);

		Size bestSize = null;
		float difference = Float.MAX_VALUE;
		float initialWidth = 0;
		for (Size s : sortedSizes) {
			boolean sizePortrait = s.width < s.height;
			boolean screenPortrait = screenWidth < screenHeight;
			int sizeWidth = (sizePortrait ^ screenPortrait) ? s.height : s.width; // xor
			int sizeHeight = (sizePortrait ^ screenPortrait) ? s.width : s.height;

			// return the best of the initial set (TODO: consider other sets?)
			if (initialWidth <= 0) {
				initialWidth = Math.max(sizeWidth, sizeHeight);
			} else if (Math.max(sizeWidth, sizeHeight) != initialWidth && bestSize != null) {
				break;
			}

			float xAspectRatio = Math.min(screenWidth, sizeWidth) / (float) Math.max(screenWidth, sizeWidth);
			float yAspectRatio = Math.min(screenHeight, sizeHeight) / (float) Math.max(screenHeight, sizeHeight);
			float newDiff = Math.abs(xAspectRatio - yAspectRatio);
			if (newDiff < MediaPhone.CAMERA_ASPECT_RATIO_TOLERANCE && newDiff < difference) {
				bestSize = s;
				difference = newDiff;
			}
		}
		return bestSize == null ? defaultSize : bestSize;
	}

	@Override
	public void setVisibility(int visibility) {
		if (visibility == View.VISIBLE) {
			startCameraPreview();
		} else {
			stopCameraPreview();
		}
		super.setVisibility(visibility);
	}

	private void startCameraPreview() {
		if (!mPreviewStarted) {
			mIsAutoFocusing = false;
			mTakePicture = false;
			mStopPreview = false;
			try {
				mCamera.startPreview();
				mPreviewStarted = true;
			} catch (Throwable t) {
				if (MediaPhone.DEBUG)
					Log.d(DebugUtilities.getLogTag(this), "startCameraPreview() -> startPreview() failed", t);
				if (mErrorCallback != null) {
					mErrorCallback.onError(ErrorCallback.PREVIEW_FAILED);
				}
			}
			if (mPreviewStarted && mAutoFocusInterval > 0) {
				requestAutoFocus(mAutoFocusHandler);
			}
		}
	}

	private void stopCameraPreview() {
		mStopPreview = true;
		// fine, because we will be setting exactly the same parameters; stop preview isn't always called before switch
		mPreviewStarted = false;
	}

	public boolean canAutoFocus() {
		return mCanAutoFocus;
	}

	public boolean canChangeFlashMode() {
		return mCanChangeFlashMode;
	}

	public String toggleFlashMode() {
		if (mCanChangeFlashMode) {
			Camera.Parameters parameters = mCamera.getParameters();
			String currentFlashMode = parameters.getFlashMode();
			List<String> modes = parameters.getSupportedFlashModes();
			// TODO: would torch mode be useful? (need an icon if so, and must reinstate, above)
			modes.remove(Camera.Parameters.FLASH_MODE_TORCH);
			int listPosition = modes.indexOf(currentFlashMode);
			if (listPosition >= 0) {
				currentFlashMode = modes.get((listPosition + 1) % modes.size());
			} else {
				currentFlashMode = Camera.Parameters.FLASH_MODE_AUTO; // if changing, remember default icon in activity
			}
			parameters.setFlashMode(currentFlashMode);
			mCamera.setParameters(parameters);
			return currentFlashMode;
		}
		return null;
	}

	public CameraImageConfiguration getPreviewConfiguration() {
		CameraImageConfiguration previewConfiguration = new CameraImageConfiguration();

		Camera.Parameters parameters = mCamera.getParameters();
		previewConfiguration.imageFormat = parameters.getPreviewFormat();
		previewConfiguration.width = mPreviewSize.width;
		previewConfiguration.height = mPreviewSize.height;

		return previewConfiguration;
	}

	public CameraImageConfiguration getPictureConfiguration() {
		CameraImageConfiguration pictureConfiguration = new CameraImageConfiguration();

		Camera.Parameters parameters = mCamera.getParameters();
		Size size = parameters.getPictureSize();
		pictureConfiguration.imageFormat = parameters.getPictureFormat();
		pictureConfiguration.width = size.width;
		pictureConfiguration.height = size.height;

		return pictureConfiguration;
	}

	public void takePicture(Camera.ShutterCallback shutterCallback, Camera.PictureCallback pictureCallback,
			Camera.PictureCallback pictureJpegCallback) {
		mTakePicture = true;
		mCamera.takePicture(shutterCallback, pictureCallback, pictureJpegCallback);

		// a bug with landscape camera devices means that the preview is corrupted when taking a picture
		// TODO: just these devices, or others too?
		if (mLandscapeCameraOnly) {
			mCamera.stopPreview();
			try {
				mCamera.setPreviewDisplay(null);
			} catch (Exception e) {
			}
		}
	}

	public void refreshCameraState() {
		// as a result of the issue above, we need to re-connect the surface when switching back without changing camera
		if (mLandscapeCameraOnly) {
			try {
				mCamera.setPreviewDisplay(mHolder);
			} catch (IOException e) {
				if (MediaPhone.DEBUG)
					Log.d(DebugUtilities.getLogTag(this), "refreshCameraState() -> setPreviewDisplay()", e);
			}
			mCamera.startPreview();
		}
	}

	public void capturePreviewFrame(Camera.PreviewCallback previewFrameCallback) {
		mCamera.setOneShotPreviewCallback(previewFrameCallback);
	}

	private void playAutoFocusSound() {
		if (mFocusSoundPlayer != null && mFocusSoundId >= 0) {
			// volume is a percentage of *current*, rather than maximum
			// TODO: on v16+, use MediaActionSound instead of this
			mFocusSoundPlayer.play(mFocusSoundId, 1, 1, 1, 0, 1);
		}
	}

	private void requestAutoFocus(Handler handler) {
		if (mCamera != null && !mTakePicture && mCanAutoFocus && !mIsAutoFocusing) {
			Camera.Parameters parameters = mCamera.getParameters();
			if (parameters.getFocusMode() != Camera.Parameters.FOCUS_MODE_AUTO) {
				parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
				mCamera.setParameters(parameters);
			}
			mIsAutoFocusing = true;
			mAutoFocusCallback.setHandler(handler);

			// TODO: at least one error was reported here, probably due to screen rotation
			try {
				mCamera.autoFocus(mAutoFocusCallback);
			} catch (Throwable t) {
				mIsAutoFocusing = false;
			}
		}
	}

	private static class AutoFocusHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case R.id.msg_auto_focus:
					((CameraView) msg.obj).requestAutoFocus(this);
					break;
				default:
					break;
			}
		}
	}

	// see:
	// http://code.google.com/p/zxing/source/browse/trunk/android/src/com/google/zxing/client/android/camera/AutoFocusCallback.java?r=1698
	private class AutoFocusCallback implements Camera.AutoFocusCallback {

		private Handler mAutoFocusHandler;

		void setHandler(Handler autoFocusHandler) {
			this.mAutoFocusHandler = autoFocusHandler;
		}

		public void onAutoFocus(boolean success, Camera camera) {
			mIsAutoFocusing = false;

			if (success) {
				playAutoFocusSound();
			}

			// always cancel - will crash if we autofocus while taking a picture
			if (mAutoFocusHandler != null) {
				mAutoFocusHandler.removeMessages(R.id.msg_auto_focus);
			}

			if (mStopPreview) {
				mCamera.stopPreview();
				mPreviewStarted = false;
				mAutoFocusHandler = null;
				return;
			}

			if (!mTakePicture) {
				// simulate continuous autofocus by sending a focus request every [interval] milliseconds
				if (mAutoFocusInterval > 0) {
					if (mAutoFocusHandler != null) {
						mAutoFocusHandler.sendMessageDelayed(
								mAutoFocusHandler.obtainMessage(R.id.msg_auto_focus, CameraView.this),
								mAutoFocusInterval);
						mAutoFocusHandler = null;
					} else {
						if (MediaPhone.DEBUG)
							Log.d(DebugUtilities.getLogTag(this), "Focus callback without handler");
					}
				}
			}
		}
	}
}
