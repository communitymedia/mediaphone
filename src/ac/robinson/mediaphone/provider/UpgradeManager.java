package ac.robinson.mediaphone.provider;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;

import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediaphone.R;
import ac.robinson.util.BitmapUtilities;
import ac.robinson.util.DebugUtilities;
import ac.robinson.util.IOUtilities;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;

public class UpgradeManager {
	public static void upgradeApplication(Context context) {
		SharedPreferences applicationVersionSettings = context.getSharedPreferences(MediaPhone.APPLICATION_NAME,
				Context.MODE_PRIVATE);
		final String versionKey = context.getString(R.string.key_application_version);
		final int currentVersion = applicationVersionSettings.getInt(versionKey, 0);

		// this is only ever for things like deleting caches and showing changes, so it doesn't really matter if we fail
		final int newVersion;
		try {
			PackageManager manager = context.getPackageManager();
			PackageInfo info = manager.getPackageInfo(context.getPackageName(), 0);
			newVersion = info.versionCode;
		} catch (Exception e) {
			Log.d(DebugUtilities.getLogTag(context),
					"Unable to find version code - not upgrading (will try again on next launch)");
			return;
		}
		if (newVersion > currentVersion) {
			SharedPreferences.Editor prefsEditor = applicationVersionSettings.edit();
			prefsEditor.putInt(versionKey, newVersion);
			prefsEditor.commit(); // apply() is better, but only in SDK >= 9
		} else {
			return; // no need to upgrade - version number has not changed
		}

		// now we get the actual settings (i.e. the user's preferences) to update/query where necessary
		SharedPreferences mediaPhoneSettings = PreferenceManager.getDefaultSharedPreferences(context);
		if (currentVersion == 0) {
			// before version 15 the version code wasn't stored (and default preference values weren't set) - instead,
			// we use the number of narratives as a rough guess as to whether this is the first install or not; upgrades
			// after v15 have a version number, so will still be processed even if no narratives exist
			// TODO: one side effect of this is that upgrades from pre-v15 to the latest version will *not* perform the
			// upgrade steps if there are no narratives; for example, upgrading to v16 will not save duration prefs
			int narrativesCount = NarrativesManager.getNarrativesCount(context.getContentResolver());
			if (narrativesCount <= 0) {
				Log.i(DebugUtilities.getLogTag(context), "First install - not upgrading; installing helper narrative");
				installHelperNarrative(context);
				return;
			}
		}

		// now process the upgrades one-by-one
		Log.i(DebugUtilities.getLogTag(context), "Upgrading from version " + currentVersion + " to " + newVersion);

		// v15 changed the way icons are drawn, so they need to be re-generated - delete thumbs folder to achieve this
		if (currentVersion < 15) {
			if (MediaPhone.DIRECTORY_THUMBS != null) {
				IOUtilities.deleteRecursive(MediaPhone.DIRECTORY_THUMBS);
				MediaPhone.DIRECTORY_THUMBS.mkdirs();
			}
		}

		// v16 updated settings screen to use sliders rather than an EditText box - must convert from string to float
		if (currentVersion < 16) {
			SharedPreferences.Editor prefsEditor = mediaPhoneSettings.edit();

			float newValue = 2.5f; // 2.5 is the default frame duration in v16 (saves reading TypedValue from prefs)
			String preferenceKey = "minimum_frame_duration"; // the old value of the frame duration key
			try {
				newValue = Float.valueOf(mediaPhoneSettings.getString(preferenceKey, Float.toString(newValue)));
				prefsEditor.remove(preferenceKey);
			} catch (Exception e) {
			}
			prefsEditor.putFloat(context.getString(R.string.key_minimum_frame_duration), newValue);

			preferenceKey = "word_duration";
			newValue = 0.2f; // 0.2 is the default frame duration in v16 (saves reading TypedValue from prefs)
			try {
				newValue = Float.valueOf(mediaPhoneSettings.getString(preferenceKey, Float.toString(newValue)));
				prefsEditor.remove(preferenceKey);
			} catch (Exception e) {
			}
			prefsEditor.putFloat(context.getString(R.string.key_word_duration), newValue);

			prefsEditor.commit(); // apply() is better, but only in SDK >= 9
		}

		// v17 added a helper narrative - add to the list if there are none in place already
		if (currentVersion < 17) {
			int narrativesCount = NarrativesManager.getNarrativesCount(context.getContentResolver());
			if (narrativesCount <= 0) {
				installHelperNarrative(context);
			}
		}

		// v18 fixed an issue with SD card cache paths - the temporary directory on the SD root is no-longer required
		if (currentVersion < 18) {
			if (IOUtilities.externalStorageIsWritable()) {
				// delete old temporary directory
				File oldTempDirectory = new File(Environment.getExternalStorageDirectory(), MediaPhone.APPLICATION_NAME
						+ context.getString(R.string.name_temp_directory));
				IOUtilities.deleteRecursive(oldTempDirectory);

				// delete old internally cached thumbnails if we've moved to using an external directory
				if (MediaPhone.DIRECTORY_THUMBS != null) {
					if (!IOUtilities.isInternalPath(MediaPhone.DIRECTORY_THUMBS.getAbsolutePath())) {
						File internalThumbsDir = IOUtilities.getNewCachePath(context, MediaPhone.APPLICATION_NAME
								+ context.getString(R.string.name_thumbs_directory), false, false);
						if (internalThumbsDir != null) {
							IOUtilities.deleteRecursive(internalThumbsDir);
						}
					}
				}
			}
		}

		// v19 added AMR audio pause/resume/export and moved to a list preference for audio quality
		if (currentVersion < 19) {
			// used to transfer the value here; no need any more as what used to be high quality is now default
			SharedPreferences.Editor prefsEditor = mediaPhoneSettings.edit();
			try {
				prefsEditor.remove("high_quality_audio"); // remove unnecessary preference key
			} catch (Exception e) {
			}
			prefsEditor.commit(); // apply() is better, but only in SDK >= 9
		} // never else - we want to check every previous step every time we do this

		// TODO: remember that pre-v15 versions will not get here if no narratives exist (i.e., don't do major changes)
	}

	public static void installHelperNarrative(Context context) {

		ContentResolver contentResolver = context.getContentResolver();
		if (NarrativesManager.findNarrativeByInternalId(contentResolver, NarrativeItem.HELPER_NARRATIVE_ID) != null) {
			return; // don't install if the helper narrative already exists
		}

		Resources res = context.getResources();
		final String narrativeId = NarrativeItem.HELPER_NARRATIVE_ID;
		final int narrativeSequenceIdIncrement = res.getInteger(R.integer.frame_narrative_sequence_increment);

		// add a narrative that gives a few tips on first use
		int[] mediaStrings = { R.string.helper_narrative_frame_1, R.string.helper_narrative_frame_2,
				R.string.helper_narrative_frame_3, R.string.helper_narrative_frame_4, };
		int[] frameImages = { 0, R.drawable.help_frame_editor, R.drawable.help_frame_export, 0 };

		for (int i = 0, n = mediaStrings.length; i < n; i++) {
			final FrameItem newFrame = new FrameItem(narrativeId, i * narrativeSequenceIdIncrement);

			// add the text
			final String frameText;
			if (i == 0) {
				frameText = context.getString(mediaStrings[i], context.getString(R.string.app_name));
			} else if (i == n - 1) {
				frameText = context.getString(mediaStrings[i],
						context.getString(R.string.preferences_contact_us_title),
						context.getString(R.string.title_preferences));
			} else {
				frameText = context.getString(mediaStrings[i]);
			}
			final String textUUID = MediaPhoneProvider.getNewInternalId();
			final File textContentFile = MediaItem.getFile(newFrame.getInternalId(), textUUID,
					MediaPhone.EXTENSION_TEXT_FILE);

			if (textContentFile != null) {
				try {
					FileWriter fileWriter = new FileWriter(textContentFile);
					BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);
					bufferedWriter.write(frameText);
					bufferedWriter.close();
				} catch (Exception e) {
				}

				MediaItem textMediaItem = new MediaItem(textUUID, newFrame.getInternalId(),
						MediaPhone.EXTENSION_TEXT_FILE, MediaPhoneProvider.TYPE_TEXT);
				textMediaItem.setDurationMilliseconds(7500); // TODO: this is a hack to improve playback
				MediaManager.addMedia(contentResolver, textMediaItem);
			}

			// add the image, if applicable
			if (frameImages[i] != 0) {
				final String imageUUID = MediaPhoneProvider.getNewInternalId();
				final String imageFileExtension = "png"; // all helper images are png format
				File imageContentFile = MediaItem.getFile(newFrame.getInternalId(), imageUUID, imageFileExtension);
				Bitmap rawBitmap = BitmapFactory.decodeResource(res, frameImages[i]);
				if (rawBitmap != null) {
					BitmapUtilities.saveBitmap(rawBitmap, Bitmap.CompressFormat.PNG, 100, imageContentFile);
				}

				if (imageContentFile.exists()) {
					MediaItem imageMediaItem = new MediaItem(imageUUID, newFrame.getInternalId(), imageFileExtension,
							MediaPhoneProvider.TYPE_IMAGE_BACK);
					MediaManager.addMedia(contentResolver, imageMediaItem);
				}
			}

			FramesManager.addFrameAndPreloadIcon(res, contentResolver, newFrame);
		}

		NarrativeItem newNarrative = new NarrativeItem(narrativeId,
				NarrativesManager.getNextNarrativeExternalId(contentResolver));
		NarrativesManager.addNarrative(contentResolver, newNarrative);
	}
}
