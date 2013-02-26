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

package ac.robinson.mediaphone;

import java.io.File;

import ac.robinson.util.DebugUtilities;
import android.graphics.Bitmap;
import android.os.Build;

public class MediaPhone {

	public static final String APPLICATION_NAME = "mediaphone"; // *must* match provider in AndroidManifest.xml
	public static final boolean DEBUG = false; // note: must add android.permission.INTERNET for ViewServer debugging

	// file extensions for media items - *not* including the dot
	// note: these are for our own creations only - imported media may well have different extensions
	// older versions and some devices can't record aac (m4a) audio, so use amr instead, which all platforms support
	public static final String EXTENSION_PHOTO_FILE = "jpg"; // TODO: check Camera.Parameters for proper file format?
	public static final String EXTENSION_AUDIO_FILE = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD_MR1
			&& !DebugUtilities.supportsAMRAudioRecordingOnly() ? "m4a" : "amr");
	public static final String EXTENSION_TEXT_FILE = "txt";

	// the audio file *must* be aac (m4a) for movie creation and editing (validated at export/edit time)
	public static final String EXPORT_EDIT_REQUIRED_AUDIO_EXTENSION = "m4a";

	// default to jpeg for smaller file sizes (will be overridden to png for frames that do not contain image media)
	public static final Bitmap.CompressFormat ICON_CACHE_TYPE = Bitmap.CompressFormat.JPEG;
	public static final int ICON_CACHE_QUALITY = 80; // jpeg only

	// -----------------------------------------------------------------------------------------------------------------
	// The following are globals for cases where we can't get a context (or it's not worth it) - all of these are
	// overridden at startup with values that are either detected automatically (e.g., paths), or loaded from attrs.xml
	// -----------------------------------------------------------------------------------------------------------------

	// storage, cache and temp directories
	// TODO: check (ie. if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {) each time we r/w
	public static File DIRECTORY_STORAGE; // to store user content
	public static File DIRECTORY_THUMBS; // for the frame thumbnails
	public static File DIRECTORY_TEMP; // currently used for outgoing files - must be world readable

	// the directory to watch for bluetooth imports - devices vary (see: http://stackoverflow.com/questions/6125993)
	public static String IMPORT_DIRECTORY;
	static {
		String possibleImportDirectory = File.separator + "mnt" + File.separator + "sdcard" + File.separator
				+ "downloads" + File.separator + "bluetooth";
		if (new File(possibleImportDirectory).exists()) {
			IMPORT_DIRECTORY = File.separator + "mnt" + File.separator + "sdcard" + File.separator + "downloads"
					+ File.separator + "bluetooth";
		} else {
			IMPORT_DIRECTORY = File.separator + "mnt" + File.separator + "sdcard" + File.separator + "bluetooth";
		}
	}
	public static boolean IMPORT_CONFIRM_IMPORTING = false;
	public static boolean IMPORT_DELETE_AFTER_IMPORTING = true;

	// in milliseconds: duration of the frame icon fade in; time to wait after finishing scrolling before showing icons
	public static int ANIMATION_FADE_TRANSITION_DURATION = 175;
	public static int ANIMATION_ICON_SHOW_DELAY = 350;
	public static int ANIMATION_ICON_REFRESH_DELAY = 250;
	public static int ANIMATION_POPUP_SHOW_DELAY = 250;
	public static int ANIMATION_POPUP_HIDE_DELAY = 600;

	// for swiping between activities
	public static int SWIPE_MIN_DISTANCE = 285;
	public static int SWIPE_MAX_OFF_PATH = 300;
	public static int SWIPE_THRESHOLD_VELOCITY = 390;

	// for our custom touch listener on the horizontal list view (milliseconds)
	public static int TWO_FINGER_PRESS_INTERVAL = 150;

	// for quick fling to start/end of lists - velocity above width * this will fling to the end
	public static float FLING_TO_END_MINIMUM_RATIO = 8.5f;

	// in milliseconds, the length of time to show an image (if audio is not longer), and the minimum text duration
	public static int PLAYBACK_EXPORT_MINIMUM_FRAME_DURATION = 2500;
	public static int PLAYBACK_EXPORT_WORD_DURATION = 200;

	// -----------------------------------------------------------------------------------------------------------------
	// The following are globals that should eventually be moved to preferences, detected, or overridden at startup
	// -----------------------------------------------------------------------------------------------------------------

	// for audio recording (8 / 8000 or 16 / 11025 give the best balance of export speed and audio quality)
	public static final int AUDIO_RECORDING_BIT_RATE = 8; // bits per second
	public static final int AUDIO_RECORDING_SAMPLING_RATE = 8000; // samples per second

	public static final int AUDIO_RECORDING_HIGHER_BIT_RATE = 16; // bits per second
	public static final int AUDIO_RECORDING_HIGHER_SAMPLING_RATE = 11025; // samples per second

	// camera preview configuration - used to select the best preview size
	public static final int CAMERA_MIN_PREVIEW_PIXELS = 470 * 320; // normal screen
	public static final int CAMERA_MAX_PREVIEW_PIXELS = 1280 * 720;
	public static final float CAMERA_ASPECT_RATIO_TOLERANCE = 0.05f;
}
