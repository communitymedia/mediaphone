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
import java.text.SimpleDateFormat;
import java.util.Arrays;

import ac.robinson.mediaphone.R;
import ac.robinson.mediaphone.provider.NarrativeItem;
import ac.robinson.mediaphone.provider.NarrativesManager;
import ac.robinson.mediaphone.provider.UpgradeManager;
import ac.robinson.mediautilities.SelectDirectoryActivity;
import ac.robinson.util.DebugUtilities;
import ac.robinson.util.UIUtilities;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Point;
import android.os.Build;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.view.Menu;
import android.view.MenuItem;

public class PreferencesActivity extends PreferenceActivity implements OnSharedPreferenceChangeListener {

	// @SuppressWarnings("deprecation") because until we move to fragments this is the only way to provide custom
	// formatted preferences (PreferenceFragment is not in the compatibility library)
	@SuppressWarnings("deprecation")
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		UIUtilities.setPixelDithering(getWindow());
		UIUtilities.configureActionBar(this, true, true, R.string.title_preferences, 0);
		addPreferencesFromResource(R.xml.preferences);

		PreferenceScreen preferenceScreen = getPreferenceScreen();
		SharedPreferences mediaPhoneSettings = preferenceScreen.getSharedPreferences();
		mediaPhoneSettings.registerOnSharedPreferenceChangeListener(this);

		// hide the high quality audio option if we're using Gingerbread's first release or only AMR is supported
		if (DebugUtilities.supportsAMRAudioRecordingOnly()) {
			PreferenceCategory editingCategory = (PreferenceCategory) preferenceScreen
					.findPreference(getString(R.string.key_editing_category));
			Preference audioBitratePreference = editingCategory.findPreference(getString(R.string.key_audio_bitrate));
			editingCategory.removePreference(audioBitratePreference);
		} else {
			updateAudioBitrateValue(mediaPhoneSettings);
		}

		// set up select bluetooth directory option
		Preference bluetoothButton = (Preference) findPreference(getString(R.string.key_bluetooth_directory));
		bluetoothButton.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
			@Override
			public boolean onPreferenceClick(Preference preference) {
				SharedPreferences mediaPhoneSettings = preference.getSharedPreferences();
				String currentDirectory = null;
				try {
					currentDirectory = mediaPhoneSettings.getString(getString(R.string.key_bluetooth_directory), null);
				} catch (Exception e) {
				}
				if (currentDirectory == null) {
					currentDirectory = getString(R.string.default_bluetooth_directory);
					if (!new File(currentDirectory).exists()) {
						currentDirectory = getString(R.string.default_bluetooth_directory_alternative);
					}
				}
				final Intent intent = new Intent(getBaseContext(), SelectDirectoryActivity.class);
				intent.putExtra(SelectDirectoryActivity.START_PATH, currentDirectory);
				startActivityForResult(intent, R.id.intent_directory_chooser);
				return true;
			}
		});

		// update the screen orientation option to show the current value
		updateScreenOrientationValue(mediaPhoneSettings);

		// hide the back/done button option if we're using the action bar instead
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			PreferenceCategory appearanceCategory = (PreferenceCategory) preferenceScreen
					.findPreference(getString(R.string.key_appearance_category));
			Preference backButtonPreference = (Preference) appearanceCategory
					.findPreference(getString(R.string.key_show_back_button));
			appearanceCategory.removePreference(backButtonPreference);
		}

		// add the helper narrative button - it has a fixed id so that we can restrict to a single install
		Preference installHelperPreference = preferenceScreen
				.findPreference(getString(R.string.key_install_helper_narrative));
		if (NarrativesManager.findNarrativeByInternalId(getContentResolver(), NarrativeItem.HELPER_NARRATIVE_ID) == null) {
			installHelperPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
				@Override
				public boolean onPreferenceClick(Preference preference) {
					preference.setOnPreferenceClickListener(null); // so they can't click twice
					UpgradeManager.installHelperNarrative(PreferencesActivity.this);
					UIUtilities.showToast(PreferencesActivity.this,
							R.string.preferences_install_helper_narrative_success);
					PreferenceCategory aboutCategory = (PreferenceCategory) getPreferenceScreen().findPreference(
							getString(R.string.key_about_category));
					aboutCategory.removePreference(preference);
					return true;
				}
			});
		} else {
			PreferenceCategory aboutCategory = (PreferenceCategory) preferenceScreen
					.findPreference(getString(R.string.key_about_category));
			aboutCategory.removePreference(installHelperPreference);
		}

		// add the contact us button
		Preference contactUsPreference = preferenceScreen.findPreference(getString(R.string.key_contact_us));
		contactUsPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
			@Override
			public boolean onPreferenceClick(Preference preference) {
				Intent emailIntent = new Intent(android.content.Intent.ACTION_SEND);
				emailIntent.setType("plain/text");
				emailIntent.putExtra(android.content.Intent.EXTRA_EMAIL,
						new String[] { getString(R.string.preferences_contact_us_email_address) });
				emailIntent.putExtra(
						android.content.Intent.EXTRA_SUBJECT,
						getString(R.string.preferences_contact_us_email_subject, getString(R.string.app_name),
								SimpleDateFormat.getDateTimeInstance().format(new java.util.Date())));
				Preference aboutPreference = findPreference(getString(R.string.key_about_application));
				emailIntent.putExtra(android.content.Intent.EXTRA_TEXT,
						getString(R.string.preferences_contact_us_email_body, aboutPreference.getSummary()));
				try {
					startActivity(Intent.createChooser(emailIntent, getString(R.string.preferences_contact_us_title)));
				} catch (ActivityNotFoundException e) {
					UIUtilities.showFormattedToast(PreferencesActivity.this,
							R.string.preferences_contact_us_email_error,
							getString(R.string.preferences_contact_us_email_address));
				}
				return true;
			}
		});

		// add version and build information
		Preference aboutPreference = preferenceScreen.findPreference(getString(R.string.key_about_application));
		try {
			PackageManager manager = this.getPackageManager();
			PackageInfo info = manager.getPackageInfo(this.getPackageName(), 0);

			aboutPreference.setTitle(getString(R.string.preferences_about_app_title, getString(R.string.app_name),
					info.versionName));
			Point screenSize = UIUtilities.getScreenSize(getWindowManager());
			String debugString = Build.MODEL + ", " + DebugUtilities.getDeviceBrandProduct() + ", v"
					+ Build.VERSION.SDK_INT + " (" + Build.VERSION.RELEASE + "), " + screenSize.x + "x" + screenSize.y;
			aboutPreference.setSummary(getString(R.string.preferences_about_app_summary, info.versionCode,
					DebugUtilities.getApplicationBuildTime(getPackageManager(), getPackageName()), debugString));

		} catch (Exception e) {
			PreferenceCategory aboutCategory = (PreferenceCategory) preferenceScreen
					.findPreference(getString(R.string.key_about_category));
			aboutCategory.removePreference(aboutPreference);
		}
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
		if (key != null) {
			if (key.equals(getString(R.string.key_audio_bitrate))) {
				updateAudioBitrateValue(sharedPreferences);
			} else if (key.equals(getString(R.string.key_screen_orientation))) {
				updateScreenOrientationValue(sharedPreferences);
			}
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			getMenuInflater().inflate(R.menu.finished_editing, menu);
		}
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case android.R.id.home:
			case R.id.menu_finished_editing:
				onBackPressed();
				return true;

			default:
				return super.onOptionsItemSelected(item);
		}
	}

	// @SuppressWarnings("deprecation") for getPreferenceScreen() - same reason as above
	@SuppressWarnings("deprecation")
	private void updateAudioBitrateValue(SharedPreferences sharedPreferences) {
		String bitrateKey = getString(R.string.key_audio_bitrate);
		PreferenceCategory editingCategory = (PreferenceCategory) getPreferenceScreen().findPreference(
				getString(R.string.key_editing_category));

		Resources res = getResources();
		int defaultBitrate = res.getInteger(R.integer.default_audio_bitrate);
		int requestedBitrate = defaultBitrate;
		try {
			String requestedBitrateString = sharedPreferences.getString(bitrateKey, null);
			requestedBitrate = Integer.valueOf(requestedBitrateString);
		} catch (Exception e) {
		}

		String[] bitrateOptions = res.getStringArray(R.array.preferences_audio_bitrate_entries);
		String[] bitrateValues = res.getStringArray(R.array.preferences_audio_bitrate_values);
		int bitrateIndex = Arrays.binarySearch(bitrateValues, Integer.toString(defaultBitrate));
		try {
			for (int i = 0, n = bitrateValues.length; i < n; i++) {
				if (Integer.valueOf(bitrateValues[i]) == requestedBitrate) {
					bitrateIndex = i;
					break;
				}
			}
		} catch (Exception e) {
		}

		editingCategory.findPreference(bitrateKey).setSummary(
				getString(R.string.current_value_as_sentence, bitrateOptions[bitrateIndex]) + " "
						+ getString(R.string.preferences_audio_bitrate_summary)); // getString trims spaces
	}

	// @SuppressWarnings("deprecation") for getPreferenceScreen() - same reason as above
	@SuppressWarnings("deprecation")
	private void updateScreenOrientationValue(SharedPreferences sharedPreferences) {
		String orientationKey = getString(R.string.key_screen_orientation);
		PreferenceCategory appearanceCategory = (PreferenceCategory) getPreferenceScreen().findPreference(
				getString(R.string.key_appearance_category));

		Resources res = getResources();
		int defaultOrientation = res.getInteger(R.integer.default_screen_orientation);
		int requestedOrientation = defaultOrientation;
		try {
			String requestedOrientationString = sharedPreferences.getString(orientationKey, null);
			requestedOrientation = Integer.valueOf(requestedOrientationString);
		} catch (Exception e) {
		}

		String[] displayOptions = res.getStringArray(R.array.preferences_orientation_entries);
		String[] displayValues = res.getStringArray(R.array.preferences_orientation_values);
		int orientationIndex = Arrays.binarySearch(displayValues, Integer.toString(defaultOrientation));
		try {
			for (int i = 0, n = displayValues.length; i < n; i++) {
				if (Integer.valueOf(displayValues[i]) == requestedOrientation) {
					orientationIndex = i;
					break;
				}
			}
		} catch (Exception e) {
		}

		appearanceCategory.findPreference(orientationKey).setSummary(displayOptions[orientationIndex]);
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent resultIntent) {
		switch (requestCode) {
			case R.id.intent_directory_chooser:
				if (resultCode == Activity.RESULT_OK && resultIntent != null) {
					String resultPath = resultIntent.getStringExtra(SelectDirectoryActivity.RESULT_PATH);
					if (resultPath != null) {
						File newPath = new File(resultPath);
						if (newPath.canRead()) {
							SharedPreferences mediaPhoneSettings = PreferenceManager
									.getDefaultSharedPreferences(PreferencesActivity.this);
							SharedPreferences.Editor prefsEditor = mediaPhoneSettings.edit();
							prefsEditor.putString(getString(R.string.key_bluetooth_directory), resultPath);
							prefsEditor.commit(); // apply() is better, but only in SDK >= 9
						} else {
							UIUtilities.showToast(PreferencesActivity.this,
									R.string.preferences_bluetooth_directory_error);
						}
					}
				}
				break;

			default:
				super.onActivityResult(requestCode, resultCode, resultIntent);
		}
	}
}
