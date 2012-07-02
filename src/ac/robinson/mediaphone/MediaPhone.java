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

import android.graphics.Bitmap;

public class MediaPhone {

	public static final String APPLICATION_NAME = "mediaphone"; // *must* match provider in AndroidManifest.xml
	public static final boolean DEBUG = false;

	// storage, cache and temp directories
	// TODO: check (ie. if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {) each time we r/w
	// NOTE: automatically initialised on application start
	public static File DIRECTORY_STORAGE; // to store user content
	public static File DIRECTORY_THUMBS; // for the frame thumbnails
	public static File DIRECTORY_TEMP; // currently used for outgoing files - must be world readable

	// the directory to watch for bluetooth imports - devices vary (see: http://stackoverflow.com/questions/6125993)
	// NOTE: overridden with values loaded from attrs.xml at startup
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

	// to record whether we're on the external storage or not, and track when we're moved to the internal storage
	// NOTE: other preference keys are in strings.xml
	public static final String KEY_USE_EXTERNAL_STORAGE = "key_use_external_storage";

	// file extensions for media items
	// NOTE: *not* including the dot
	public static final String EXTENSION_PHOTO_FILE = "jpg"; // TODO: check Camera.Parameters for proper file format?
	public static final String EXTENSION_VIDEO_FILE = "mp4"; // TODO: fix if video is added
	public static final String EXTENSION_AUDIO_FILE = "m4a"; // must be m4a for MOV export
	public static final String EXTENSION_TEXT_FILE = "txt";

	// for audio recording (8 / 8000 or 16 / 11025 give the best balance of export speed and audio quality)
	public static final int AUDIO_RECORDING_BIT_RATE = 8; // bits per second
	public static final int AUDIO_RECORDING_SAMPLING_RATE = 8000; // samples per second

	// default to jpeg for smaller file sizes (will be overridden for frames that do not contain image media)
	public static final Bitmap.CompressFormat ICON_CACHE_TYPE = Bitmap.CompressFormat.JPEG;
	public static final int ICON_CACHE_QUALITY = 80;

	// in milliseconds: duration of the frame icon fade in; time to wait after finishing scrolling before showing icons
	public static final int ANIMATION_FADE_TRANSITION_DURATION = 175;
	public static final int ANIMATION_ICON_SHOW_DELAY = 350;
	public static final int ANIMATION_POPUP_SHOW_DELAY = 200;
	public static final int ANIMATION_POPUP_HIDE_DELAY = 600;

	// for swiping between activities
	public static final int SWIPE_MIN_DISTANCE = 120;
	public static final int SWIPE_MAX_OFF_PATH = 250;
	public static final int SWIPE_THRESHOLD_VELOCITY = 200;

	// for quick fling to start/end of lists
	public static final float FLING_TO_END_SPEED = 80f; // higher than this pixels/second rate will fling to the end

	// in milliseconds, the length of time to show an image (if audio is not longer), and the minimum text duration
	// NOTE: overridden with values loaded from attrs.xml at startup
	public static int PLAYBACK_EXPORT_MINIMUM_FRAME_DURATION = 2500;
	public static int PLAYBACK_EXPORT_WORD_DURATION = 200;
}
