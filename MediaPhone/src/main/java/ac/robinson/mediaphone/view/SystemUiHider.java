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

package ac.robinson.mediaphone.view;

import android.annotation.SuppressLint;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Based on the Android training utility class that helps with showing and hiding system UI such as the status bar and
 * navigation/system bar as described in
 * <a href="http://developer.android.com/training/backward-compatible-ui/index.html">Creating Backward-Compatible UIs</a>.
 * Updated to only use APIs available in Honeycomb and later (specifically {@link View#setSystemUiVisibility(int)}) to show and
 * hide the system UI.
 * <p>
 * For more on system bars, see <a href="http://developer.android.com/design/get-started/ui-overview.html#system-bars">
 * System Bars</a>.
 *
 * @see android.view.View#setSystemUiVisibility(int)
 * @see android.view.WindowManager.LayoutParams#FLAG_FULLSCREEN
 */
public class SystemUiHider {
	/**
	 * When this flag is set, {@link #show()} and {@link #hide()} will toggle the visibility of the status bar. If there
	 * is a navigation bar, show and hide will toggle low profile mode.
	 */
	public static final int FLAG_FULLSCREEN = 0x1;

	/**
	 * When this flag is set, {@link #show()} and {@link #hide()} will toggle the visibility of the navigation bar, if
	 * it's present on the device and the device allows hiding it. In cases where the navigation bar is present but
	 * cannot be hidden, show and hide will toggle low profile mode.
	 */
	public static final int FLAG_HIDE_NAVIGATION = FLAG_FULLSCREEN | 0x2;

	/**
	 * The activity associated with this UI hider object.
	 */
	private AppCompatActivity mActivity;

	/**
	 * The view on which {@link View#setSystemUiVisibility(int)} will be called.
	 */
	private View mAnchorView;

	/**
	 * The current UI hider flags.
	 *
	 * @see #FLAG_FULLSCREEN
	 * @see #FLAG_HIDE_NAVIGATION
	 */
	private int mFlags;

	/**
	 * Flags for {@link View#setSystemUiVisibility(int)} to use when showing the system UI.
	 */
	private int mShowFlags;

	/**
	 * Flags for {@link View#setSystemUiVisibility(int)} to use when hiding the system UI.
	 */
	private int mHideFlags;

	/**
	 * Flags to test against the first parameter in
	 * {@link android.view.View.OnSystemUiVisibilityChangeListener#onSystemUiVisibilityChange(int)} to determine the
	 * system UI visibility state.
	 */
	private int mTestFlags;

	/**
	 * Whether or not the system UI is currently visible. This is cached from
	 * {@link android.view.View.OnSystemUiVisibilityChangeListener}.
	 */
	private boolean mVisible = true;


	/**
	 * The current visibility callback.
	 */
	private OnVisibilityChangeListener mOnVisibilityChangeListener = sDummyListener;

	/**
	 * Creates an instance of {@link SystemUiHider}.
	 *
	 * @param activity   The activity whose window's system UI should be controlled by this class.
	 * @param anchorView The view on which {@link View#setSystemUiVisibility(int)} will be called.
	 * @param flags      Either 0 or any combination of {@link #FLAG_FULLSCREEN} and {@link #FLAG_HIDE_NAVIGATION}.
	 */
	@SuppressLint("InlinedApi") // for API 16+ inlined constants
	public SystemUiHider(AppCompatActivity activity, View anchorView, int flags) {
		mActivity = activity;
		mAnchorView = anchorView;
		mFlags = flags;

		mShowFlags = View.SYSTEM_UI_FLAG_VISIBLE;
		mHideFlags = View.SYSTEM_UI_FLAG_LOW_PROFILE;
		mTestFlags = View.SYSTEM_UI_FLAG_LOW_PROFILE;

		if ((mFlags & FLAG_FULLSCREEN) != 0) {
			// If the client requested fullscreen, add flags relevant to hiding the status bar. Note that some of these
			// constants are new as of API 16 (Jelly Bean). It is safe to use them, as they are inlined at compile-time
			// and do nothing on pre-Jelly Bean devices.
			mShowFlags |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
			mHideFlags |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_FULLSCREEN;
		}

		if ((mFlags & FLAG_HIDE_NAVIGATION) != 0) {
			// If the client requested hiding navigation, add relevant flags.
			mShowFlags |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
			mHideFlags |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
			mTestFlags |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
		}
	}

	/**
	 * Sets up the system UI hider. Should be called from AppCompatActivity.onCreate().
	 */
	public void setup() {
		mAnchorView.setOnSystemUiVisibilityChangeListener(mSystemUiVisibilityChangeListener);
	}

	/**
	 * Returns whether or not the system UI is visible.
	 */
	public boolean isVisible() {
		return mVisible;
	}

	/**
	 * Hide the system UI.
	 */
	public void hide() {
		mAnchorView.setSystemUiVisibility(mHideFlags);
	}

	/**
	 * Show the system UI.
	 */
	public void show() {
		mAnchorView.setSystemUiVisibility(mShowFlags);
	}

	/**
	 * Toggle the visibility of the system UI.
	 */
	public void toggle() {
		if (isVisible()) {
			hide();
		} else {
			show();
		}
	}

	/**
	 * Registers a callback, to be triggered when the system UI visibility changes.
	 */
	public void setOnVisibilityChangeListener(OnVisibilityChangeListener listener) {
		if (listener == null) {
			listener = sDummyListener;
		}

		mOnVisibilityChangeListener = listener;
	}

	/**
	 * A dummy no-op callback for use when there is no other listener set.
	 */
	private static OnVisibilityChangeListener sDummyListener = new OnVisibilityChangeListener() {
		@Override
		public void onVisibilityChange(boolean visible) {
		}
	};

	/**
	 * A callback interface used to listen for system UI visibility changes.
	 */
	public interface OnVisibilityChangeListener {
		/**
		 * Called when the system UI visibility has changed.
		 *
		 * @param visible True if the system UI is visible.
		 */
		void onVisibilityChange(boolean visible);
	}

	private View.OnSystemUiVisibilityChangeListener mSystemUiVisibilityChangeListener =
			new View.OnSystemUiVisibilityChangeListener() {
		@Override
		public void onSystemUiVisibilityChange(int vis) {
			// Test against mTestFlags to see if the system UI is visible.
			if ((vis & mTestFlags) != 0) {
				if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
					// Pre-Jelly Bean, we must use the old window flags API.
					mActivity.getWindow()
							.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
				}

				// Trigger the registered listener and cache the visibility state.
				mOnVisibilityChangeListener.onVisibilityChange(false);
				mVisible = false;

			} else {
				mAnchorView.setSystemUiVisibility(mShowFlags);
				if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
					// Pre-Jelly Bean, we must use the old window flags API.
					mActivity.getWindow().setFlags(0, WindowManager.LayoutParams.FLAG_FULLSCREEN);
				}

				// Trigger the registered listener and cache the visibility state.
				mOnVisibilityChangeListener.onVisibilityChange(true);
				mVisible = true;
			}
		}
	};
}
