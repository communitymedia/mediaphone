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

import ac.robinson.mediautilities.MediaUtilities;
import ac.robinson.util.DebugUtilities;
import android.graphics.Bitmap;

public class MediaPhone {

	public static final String APPLICATION_NAME = "mediaphone"; // *must* match provider in AndroidManifest.xml
	public static final boolean DEBUG = false; // note: must add android.permission.INTERNET for ViewServer debugging

	// file extensions for our own media items (imported media may differ) - *not* including the dot
	// older versions and some devices can't record AAC (M4A) audio, so use AMR instead, which all platforms support
	public static final String EXTENSION_PHOTO_FILE = "jpg"; // TODO: check Camera.Parameters for proper file format?
	public static final String EXTENSION_AUDIO_FILE = (DebugUtilities.supportsAMRAudioRecordingOnly() ? "3gp" : "m4a");
	public static final String EXTENSION_TEXT_FILE = "txt";
	
	//the number of audio items to allow per frame - note that if this is changed, layouts need updating too
	public static final int MAX_AUDIO_ITEMS = 3;

	// we can pause/resume recording in either AAC (M4A) or AMR (3GP) formats - get extensions from MediaUtilities
	public static String[] EDITABLE_AUDIO_EXTENSIONS = {};
	static {
		int totalLength = MediaUtilities.M4A_FILE_EXTENSIONS.length + MediaUtilities.AMR_FILE_EXTENSIONS.length;
		String[] tempExtensions = new String[totalLength];
		for (int i = 0; i < MediaUtilities.M4A_FILE_EXTENSIONS.length; i++) {
			tempExtensions[i] = MediaUtilities.M4A_FILE_EXTENSIONS[i];
		}
		for (int i = MediaUtilities.M4A_FILE_EXTENSIONS.length; i < totalLength; i++) {
			tempExtensions[i] = MediaUtilities.AMR_FILE_EXTENSIONS[i - MediaUtilities.M4A_FILE_EXTENSIONS.length];
		}
		EDITABLE_AUDIO_EXTENSIONS = tempExtensions;
	}

	// default to JPEG for smaller file sizes (will be overridden to PNG for frames that do not contain image media)
	public static final Bitmap.CompressFormat ICON_CACHE_TYPE = Bitmap.CompressFormat.JPEG;
	public static final int ICON_CACHE_QUALITY = 80; // applies to JPEG only

	// using the support library means that we can't use generated startActivityForResult ids any more (!)
	public static final int R_id_intent_preferences = 1;
	public static final int R_id_intent_template_browser = 2;
	public static final int R_id_intent_frame_editor = 3;
	public static final int R_id_intent_narrative_player = 4;
	public static final int R_id_intent_picture_editor = 5;
	public static final int R_id_intent_audio_editor = 6;
	public static final int R_id_intent_text_editor = 7;
	public static final int R_id_intent_directory_chooser = 8;
	public static final int R_id_intent_picture_import = 9;
	public static final int R_id_intent_audio_import = 10;

	// -----------------------------------------------------------------------------------------------------------------
	// The following are globals for cases where we can't get a context (or it's not worth it) - all of these are
	// overridden at startup with values that are either detected automatically (e.g., paths), or loaded from attrs.xml
	// -----------------------------------------------------------------------------------------------------------------

	// storage, cache and temp directories
	public static File DIRECTORY_STORAGE; // to store user content
	public static File DIRECTORY_THUMBS; // for the frame thumbnails
	public static File DIRECTORY_TEMP; // currently used for outgoing files - must be world readable

	// the directory to watch for bluetooth imports - devices vary (see: http://stackoverflow.com/questions/6125993)
	public static String IMPORT_DIRECTORY;
	static {
		final String possibleImportDirectory = File.separator + "mnt" + File.separator + "sdcard" + File.separator
				+ "downloads" + File.separator + "bluetooth";
		if (new File(possibleImportDirectory).exists()) {
			IMPORT_DIRECTORY = possibleImportDirectory;
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

	// camera preview configuration - used to select the best preview size
	public static final int CAMERA_MIN_PREVIEW_PIXELS = 470 * 320; // normal screen
	public static final int CAMERA_MAX_PREVIEW_PIXELS = 1280 * 720;
	public static final float CAMERA_ASPECT_RATIO_TOLERANCE = 0.05f;
}
