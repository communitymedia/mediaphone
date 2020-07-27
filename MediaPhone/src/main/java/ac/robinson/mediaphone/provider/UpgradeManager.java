package ac.robinson.mediaphone.provider;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;

import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediaphone.R;
import ac.robinson.util.BitmapUtilities;
import ac.robinson.util.DebugUtilities;
import ac.robinson.util.IOUtilities;
import ac.robinson.util.StringUtilities;

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
					"Unable to find version code - not upgrading (will try again on next " + "launch)");
			return;
		}
		if (newVersion > currentVersion) {
			SharedPreferences.Editor prefsEditor = applicationVersionSettings.edit();
			prefsEditor.putInt(versionKey, newVersion);
			prefsEditor.apply();
		} else {
			handleUpgradeFixes(context); // need to check these every time we launch in case Android version has been upgraded
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
			} catch (Exception ignored) {
			}
			prefsEditor.putFloat(context.getString(R.string.key_minimum_frame_duration), newValue);

			preferenceKey = "word_duration";
			newValue = 0.2f; // 0.2 is the default frame duration in v16 (saves reading TypedValue from prefs)
			try {
				newValue = Float.valueOf(mediaPhoneSettings.getString(preferenceKey, Float.toString(newValue)));
				prefsEditor.remove(preferenceKey);
			} catch (Exception ignored) {
			}
			prefsEditor.putFloat(context.getString(R.string.key_word_duration), newValue);

			prefsEditor.apply();
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
				File oldTempDirectory = new File(Environment.getExternalStorageDirectory(),
						MediaPhone.APPLICATION_NAME + context.getString(R.string.name_temp_directory));
				IOUtilities.deleteRecursive(oldTempDirectory);

				// delete old internally cached thumbnails if we've moved to using an external directory
				if (MediaPhone.DIRECTORY_THUMBS != null) {
					if (!IOUtilities.isInternalPath(MediaPhone.DIRECTORY_THUMBS.getAbsolutePath())) {
						File internalThumbsDir = IOUtilities.getNewCachePath(context,
								MediaPhone.APPLICATION_NAME + context.getString(R.string.name_thumbs_directory), false, false);
						if (internalThumbsDir != null) {
							IOUtilities.deleteRecursive(internalThumbsDir);
						}
					}
				}
			}
		}

		// v19 added AMR audio pause/resume/export and moved to a list preference for audio quality
		// *note* AMR was subsequently removed as newer devices support M4A far more easily
		if (currentVersion < 19) {
			// used to transfer the value here; no need any more as what used to be high quality is now default
			SharedPreferences.Editor prefsEditor = mediaPhoneSettings.edit();
			try {
				prefsEditor.remove("high_quality_audio"); // remove unnecessary preference key
			} catch (Exception ignored) {
			}
			prefsEditor.apply();
		} // never else - we want to check every previous step every time we do this

		// v21 changed the app theme significantly so icons need to be re-generated - delete thumbs folder to achieve this
		if (currentVersion < 21) {
			if (MediaPhone.DIRECTORY_THUMBS != null) {
				IOUtilities.deleteRecursive(MediaPhone.DIRECTORY_THUMBS);
				MediaPhone.DIRECTORY_THUMBS.mkdirs();
			}
		} // never else - we want to check every previous step every time we do this

		// v38 introduced a timing editor; as a result we switched to storing the word count of text items, rather than duration
		if (currentVersion < 38) {
			ContentResolver contentResolver = context.getContentResolver();
			ArrayList<MediaItem> textMedia = MediaManager.findAllTextMedia(contentResolver);
			for (MediaItem media : textMedia) {
				String textContents = IOUtilities.getFileContents(media.getFile().getAbsolutePath()).trim();
				media.setExtra(StringUtilities.wordCount(textContents));
				MediaManager.updateMedia(contentResolver, media);
			}
		} // never else - we want to check every previous step every time we do this

		// TODO: remember that pre-v15 versions will not get here if no narratives exist (i.e., don't do major changes)

		handleUpgradeFixes(context); // need to check these every time we launch in case Android version has been upgraded
	}

	// these operations are things that depend not on the app version but on the Android platform version, so must be done on
	// every launch in case the platform has changed (in most cases these are runtime-dependent, but some (like resampling rates)
	// are better handled by fixing a preference value to ensure that we don't have to constantly check for edge cases
	// NOTE: we don't need to check the app version here: all version-dependent changes take place in the standard way, above
	private static void handleUpgradeFixes(Context context) {
		// versions after 32 support mp4 export, but we need to make sure we remove the preference to disable resampling as this
		// is not compatible - all narratives are now passed through the resampling process
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
			SharedPreferences mediaPhoneSettings = PreferenceManager.getDefaultSharedPreferences(context);
			String resamplingKey = context.getString(R.string.key_audio_resampling_bitrate);
			if ("0".equals(mediaPhoneSettings.getString(resamplingKey, null))) {
				SharedPreferences.Editor prefsEditor = mediaPhoneSettings.edit();
				prefsEditor.putString(resamplingKey, String.valueOf(context.getResources()
						.getInteger(R.integer.default_resampling_bitrate))); // string rather than int due to ListPreference
				prefsEditor.apply();
			}
		} // never else - we want to check every previous step every time we do this
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
		String[] mediaStrings = {
				context.getString(R.string.helper_narrative_frame_1, context.getString(R.string.app_name)),
				context.getString(R.string.helper_narrative_frame_2),
				context.getString(R.string.helper_narrative_frame_3),
				context.getString(R.string.helper_narrative_frame_4, context.getString(R.string.preferences_contact_us_title),
						context.getString(R.string.title_preferences))
		};
		int[] frameImages = { 0, R.drawable.help_frame_editor, R.drawable.help_frame_export, 0 };

		for (int i = 0, n = mediaStrings.length; i < n; i++) {
			final FrameItem newFrame = new FrameItem(narrativeId, i * narrativeSequenceIdIncrement);

			// add the text
			final String textUUID = MediaPhoneProvider.getNewInternalId();
			final File textContentFile = MediaItem.getFile(newFrame.getInternalId(), textUUID, MediaPhone.EXTENSION_TEXT_FILE);

			try {
				FileWriter fileWriter = new FileWriter(textContentFile);
				BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);
				bufferedWriter.write(mediaStrings[i]);
				bufferedWriter.close();
			} catch (Exception ignored) {
			}

			MediaItem textMediaItem = new MediaItem(textUUID, newFrame.getInternalId(), MediaPhone.EXTENSION_TEXT_FILE,
					MediaPhoneProvider.TYPE_TEXT);
			textMediaItem.setDurationMilliseconds(7500); // TODO: this is a hack to improve helper narrative playback
			MediaManager.addMedia(contentResolver, textMediaItem);

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

	public static void installTimingEditorNarrative(Context context) {
		ContentResolver contentResolver = context.getContentResolver();
		if (NarrativesManager.findNarrativeByInternalId(contentResolver, NarrativeItem.TIMING_EDITOR_NARRATIVE_ID) != null) {
			return; // don't install if the helper narrative already exists
		}

		Resources res = context.getResources();
		final String narrativeId = NarrativeItem.TIMING_EDITOR_NARRATIVE_ID;
		final int narrativeSequenceIdIncrement = res.getInteger(R.integer.frame_narrative_sequence_increment);

		// add a narrative that gives instructions for using the timing editor
		String[] mediaStrings = {
				context.getString(R.string.timing_editor_narrative_frame_1),
				context.getString(R.string.timing_editor_narrative_frame_2, context.getString(R.string.timing_editor_ffwd_icon)),
				context.getString(R.string.timing_editor_narrative_frame_3, context.getString(R.string.timing_editor_rew_icon),
						context.getString(R.string.timing_editor_menu_icon),
						context.getString(R.string.timing_editor_record_icon_alternative)),
				context.getString(R.string.timing_editor_narrative_frame_4),
				context.getString(R.string.timing_editor_narrative_frame_5,
						context.getString(R.string.preferences_contact_us_title), context.getString(R.string.title_preferences)),
		};

		for (int i = 0, n = mediaStrings.length; i < n; i++) {
			final FrameItem newFrame = new FrameItem(narrativeId, i * narrativeSequenceIdIncrement);

			// add the text
			final String textUUID = MediaPhoneProvider.getNewInternalId();
			final File textContentFile = MediaItem.getFile(newFrame.getInternalId(), textUUID, MediaPhone.EXTENSION_TEXT_FILE);

			try {
				FileWriter fileWriter = new FileWriter(textContentFile);
				BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);
				bufferedWriter.write(mediaStrings[i]);
				bufferedWriter.close();
			} catch (Exception ignored) {
			}

			MediaItem textMediaItem = new MediaItem(textUUID, newFrame.getInternalId(), MediaPhone.EXTENSION_TEXT_FILE,
					MediaPhoneProvider.TYPE_TEXT);
			MediaManager.addMedia(contentResolver, textMediaItem);

			FramesManager.addFrameAndPreloadIcon(res, contentResolver, newFrame);
		}

		NarrativeItem newNarrative = new NarrativeItem(narrativeId,
				NarrativesManager.getNextNarrativeExternalId(contentResolver));
		NarrativesManager.addNarrative(contentResolver, newNarrative);
	}
}
