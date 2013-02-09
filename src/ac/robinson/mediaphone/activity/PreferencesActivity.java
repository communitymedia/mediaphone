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

import ac.robinson.mediaphone.R;
import ac.robinson.mediautilities.SelectDirectoryActivity;
import ac.robinson.util.DebugUtilities;
import ac.robinson.util.UIUtilities;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Point;
import android.os.Build;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.view.Menu;
import android.view.MenuItem;

public class PreferencesActivity extends PreferenceActivity {

	// @SuppressWarnings("deprecation") because until we move to fragments this is the only way to provide custom
	// formatted preferences (PreferenceFragment is not in the compatibility library)
	@SuppressWarnings("deprecation")
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		UIUtilities.setPixelDithering(getWindow());
		UIUtilities.configureActionBar(this, true, true, R.string.title_preferences, 0);
		addPreferencesFromResource(R.xml.preferences);

		// hide the high quality audio option if we're using Gingerbread's first release
		PreferenceScreen preferenceScreen = getPreferenceScreen();
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.GINGERBREAD_MR1) {
			PreferenceCategory editingCategory = (PreferenceCategory) preferenceScreen
					.findPreference(getString(R.string.key_editing_category));
			CheckBoxPreference highQualityAudioPreference = (CheckBoxPreference) editingCategory
					.findPreference(getString(R.string.key_high_quality_audio));
			editingCategory.removePreference(highQualityAudioPreference);
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

		// hide the back/done button option if we're using the action bar instead
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			PreferenceCategory appearanceCategory = (PreferenceCategory) preferenceScreen
					.findPreference(getString(R.string.key_appearance_category));
			CheckBoxPreference backButtonPreference = (CheckBoxPreference) appearanceCategory
					.findPreference(getString(R.string.key_show_back_button));
			appearanceCategory.removePreference(backButtonPreference);
		}

		// add version and build information
		Preference aboutPreference = preferenceScreen.findPreference(getString(R.string.key_about_application));
		try {
			PackageManager manager = this.getPackageManager();
			PackageInfo info = manager.getPackageInfo(this.getPackageName(), 0);

			aboutPreference.setTitle(String.format(getString(R.string.preferences_about_app_title),
					getString(R.string.app_name), info.versionName));
			Point screenSize = UIUtilities.getScreenSize(getWindowManager());
			String debugString = Build.MODEL + ", v" + Build.VERSION.SDK_INT + " (" + Build.VERSION.RELEASE + "), "
					+ screenSize.x + "x" + screenSize.y;
			aboutPreference.setSummary(String.format(getString(R.string.preferences_about_app_summary),
					info.versionCode, DebugUtilities.getApplicationBuildTime(getPackageManager(), getPackageName()),
					debugString));

		} catch (Exception e) {
			PreferenceCategory aboutCategory = (PreferenceCategory) preferenceScreen
					.findPreference(getString(R.string.key_about_category));
			aboutCategory.removePreference(aboutPreference);
		}

		// add the report problem button
		Preference contactUsPreference = preferenceScreen.findPreference(getString(R.string.key_contact_us));
		contactUsPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
			@Override
			public boolean onPreferenceClick(Preference preference) {
				Intent emailIntent = new Intent(android.content.Intent.ACTION_SEND);
				emailIntent.setType("plain/text");
				emailIntent.putExtra(android.content.Intent.EXTRA_EMAIL,
						new String[] { getString(R.string.preferences_contact_us_email_address) });
				emailIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, String.format(
						getString(R.string.preferences_contact_us_email_subject), getString(R.string.app_name),
						SimpleDateFormat.getDateTimeInstance().format(new java.util.Date())));
				Preference aboutPreference = findPreference(getString(R.string.key_about_application));
				emailIntent.putExtra(
						android.content.Intent.EXTRA_TEXT,
						String.format(getString(R.string.preferences_contact_us_email_body),
								aboutPreference.getSummary()));
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
	}

	// @SuppressWarnings("deprecation") for getPreferenceScreen() - same reason as above
	@SuppressWarnings("deprecation")
	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) {
			// add the current value of the screen orientation preference - done here so we update if its value changes
			PreferenceCategory appearanceCategory = (PreferenceCategory) getPreferenceScreen().findPreference(
					getString(R.string.key_appearance_category));
			ListPreference displayOrientationPreference = (ListPreference) appearanceCategory
					.findPreference(getString(R.string.key_screen_orientation));
			SharedPreferences mediaPhoneSettings = displayOrientationPreference.getSharedPreferences();
			Resources res = getResources();
			int requestedOrientation = res.getInteger(R.integer.default_screen_orientation);
			try {
				String requestedOrientationString = mediaPhoneSettings.getString(
						getString(R.string.key_screen_orientation), null);
				requestedOrientation = Integer.valueOf(requestedOrientationString);
			} catch (Exception e) {
			}
			String[] displayValues = res.getStringArray(R.array.preferences_orientation_values);
			int orientationIndex = 0;
			for (int i = 0, n = displayValues.length; i < n; i++) {
				try {
					if (Integer.valueOf(displayValues[i]) == requestedOrientation) {
						orientationIndex = i;
						break;
					}
				} catch (Exception e) {
				}
			}
			String[] displayOptions = res.getStringArray(R.array.preferences_orientation_entries);
			displayOrientationPreference.setSummary(String.format(
					getString(R.string.preferences_orientation_summary_with_current),
					getString(R.string.preferences_orientation_summary), displayOptions[orientationIndex]));
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
							prefsEditor.apply();
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
