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
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Environment;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.support.annotation.LayoutRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatDelegate;
import android.support.v7.widget.Toolbar;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

import ac.robinson.mediaphone.BuildConfig;
import ac.robinson.mediaphone.MediaPhone;
import ac.robinson.mediaphone.R;
import ac.robinson.mediaphone.provider.NarrativeItem;
import ac.robinson.mediaphone.provider.NarrativesManager;
import ac.robinson.mediaphone.provider.UpgradeManager;
import ac.robinson.mediautilities.SelectDirectoryActivity;
import ac.robinson.util.DebugUtilities;
import ac.robinson.util.IOUtilities;
import ac.robinson.util.UIUtilities;

/**
 * A {@link PreferenceActivity} for editing application settings.
 */
public class PreferencesActivity extends PreferenceActivity implements Preference.OnPreferenceChangeListener {

	private AppCompatDelegate mDelegate;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		getDelegate().installViewFactory();
		getDelegate().onCreate(savedInstanceState);
		super.onCreate(savedInstanceState);
		UIUtilities.setPixelDithering(getWindow());

		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayShowTitleEnabled(true);
			actionBar.setDisplayHomeAsUpEnabled(true);
		}

		setupPreferences();
	}

	@Override
	protected void onPostCreate(Bundle savedInstanceState) {
		super.onPostCreate(savedInstanceState);
		getDelegate().onPostCreate(savedInstanceState);
	}

	public ActionBar getSupportActionBar() {
		return getDelegate().getSupportActionBar();
	}

	public void setSupportActionBar(@Nullable Toolbar toolbar) {
		getDelegate().setSupportActionBar(toolbar);
	}

	@NonNull
	@Override
	public MenuInflater getMenuInflater() {
		return getDelegate().getMenuInflater();
	}

	@Override
	public void setContentView(@LayoutRes int layoutResID) {
		getDelegate().setContentView(layoutResID);
	}

	@Override
	public void setContentView(View view) {
		getDelegate().setContentView(view);
	}

	@Override
	public void setContentView(View view, ViewGroup.LayoutParams params) {
		getDelegate().setContentView(view, params);
	}

	@Override
	public void addContentView(View view, ViewGroup.LayoutParams params) {
		getDelegate().addContentView(view, params);
	}

	@Override
	protected void onPostResume() {
		super.onPostResume();
		getDelegate().onPostResume();
	}

	@Override
	protected void onTitleChanged(CharSequence title, int color) {
		super.onTitleChanged(title, color);
		getDelegate().setTitle(title);
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		getDelegate().onConfigurationChanged(newConfig);
	}

	@Override
	protected void onStop() {
		super.onStop();
		getDelegate().onStop();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		getDelegate().onDestroy();
	}

	public void invalidateOptionsMenu() {
		getDelegate().invalidateOptionsMenu();
	}

	private AppCompatDelegate getDelegate() {
		if (mDelegate == null) {
			mDelegate = AppCompatDelegate.create(this, null);
		}
		return mDelegate;
	}

	/**
	 * Loads preferences from XML and sets up preferences that need more configuration than just loading. For example,
	 * options like audio bit rate will be hidden on the oldest devices and those that don't support M4A, and the option
	 * for showing a back button will be hidden on Honeycomb or later.
	 */
	private void setupPreferences() {
		addPreferencesFromResource(R.xml.preferences);
		PreferenceScreen preferenceScreen = getPreferenceScreen();

		// TODO: find an elegant way of showing that the preferences for adding items to the media library have no effect unless
		// TODO: storage permission is granted (disable until click? add hint to text? prompt for permission on click?)
		// TODO: - similar issue applies to import directory selector (which can't browse /sdcard without permission)

		// hide the high quality audio option if we're using Gingerbread's first release or only AMR is supported
		String bitrateKey = getString(R.string.key_audio_bitrate);
		if (DebugUtilities.supportsAMRAudioRecordingOnly()) {
			PreferenceCategory editingCategory = (PreferenceCategory) preferenceScreen.findPreference(getString(R.string
					.key_editing_category));
			Preference audioBitratePreference = editingCategory.findPreference(bitrateKey);
			editingCategory.removePreference(audioBitratePreference);
		} else {
			bindPreferenceSummaryToValue(findPreference(bitrateKey));
		}

		// set up the select bluetooth directory option with the current chosen directory; register its click listener
		Preference bluetoothButton = (Preference) findPreference(getString(R.string.key_bluetooth_directory));
		bluetoothButton.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
			@Override
			public boolean onPreferenceClick(Preference preference) {
				SharedPreferences mediaPhoneSettings = preference.getSharedPreferences();
				String currentDirectory = null;
				try {
					currentDirectory = mediaPhoneSettings.getString(getString(R.string.key_bluetooth_directory), null);
				} catch (Exception ignored) {
				}
				if (currentDirectory == null) {
					currentDirectory = getString(R.string.default_bluetooth_directory);
					if (!new File(currentDirectory).exists()) {
						currentDirectory = getString(R.string.default_bluetooth_directory_alternative);
						if (!new File(currentDirectory).exists() && IOUtilities.externalStorageIsReadable()) {
							currentDirectory = Environment.getExternalStorageDirectory().getAbsolutePath();
						} else {
							currentDirectory = "/"; // default to storage root
						}
					}
				}
				final Intent intent = new Intent(getBaseContext(), SelectDirectoryActivity.class);
				intent.putExtra(SelectDirectoryActivity.START_PATH, currentDirectory);
				startActivityForResult(intent, MediaPhone.R_id_intent_directory_chooser);
				return true;
			}
		});

		// update the screen orientation option to show the current value
		bindPreferenceSummaryToValue(findPreference(getString(R.string.key_screen_orientation)));

		// hide the back/done button option if we're using the action bar instead
		//if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
		//	PreferenceCategory appearanceCategory = (PreferenceCategory) preferenceScreen
		//			.findPreference(getString(R.string.key_appearance_category));
		//	Preference backButtonPreference = (Preference) appearanceCategory
		//			.findPreference(getString(R.string.key_show_back_button));
		//	appearanceCategory.removePreference(backButtonPreference);
		//}

		// add the helper narrative button - it has a fixed id so that we can restrict to a single install
		Preference installHelperPreference = preferenceScreen.findPreference(getString(R.string.key_install_helper_narrative));
		if (NarrativesManager.findNarrativeByInternalId(getContentResolver(), NarrativeItem.HELPER_NARRATIVE_ID) == null) {
			installHelperPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
				@Override
				public boolean onPreferenceClick(Preference preference) {
					preference.setOnPreferenceClickListener(null); // so they can't click twice
					UpgradeManager.installHelperNarrative(PreferencesActivity.this);
					UIUtilities.showToast(PreferencesActivity.this, R.string.preferences_install_helper_narrative_success);
					PreferenceCategory aboutCategory = (PreferenceCategory) getPreferenceScreen().findPreference(getString(R
							.string.key_about_category));
					aboutCategory.removePreference(preference);
					return true;
				}
			});
		} else {
			// the narrative exists - remove the button to prevent multiple installs
			PreferenceCategory aboutCategory = (PreferenceCategory) preferenceScreen.findPreference(getString(R.string
					.key_about_category));
			aboutCategory.removePreference(installHelperPreference);
		}

		// add the contact us button
		Preference contactUsPreference = preferenceScreen.findPreference(getString(R.string.key_contact_us));
		contactUsPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
			@Override
			public boolean onPreferenceClick(Preference preference) {
				Intent emailIntent = new Intent(android.content.Intent.ACTION_SEND);
				emailIntent.setType("plain/text");
				emailIntent.putExtra(android.content.Intent.EXTRA_EMAIL, new String[]{getString(R.string
						.preferences_contact_us_email_address)});
				emailIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string
						.preferences_contact_us_email_subject, getString(R.string.app_name), SimpleDateFormat
						.getDateTimeInstance().format(new java.util.Date())));
				Preference aboutPreference = findPreference(getString(R.string.key_about_application));
				emailIntent.putExtra(android.content.Intent.EXTRA_TEXT, getString(R.string.preferences_contact_us_email_body,
						aboutPreference.getSummary()));
				try {
					startActivity(Intent.createChooser(emailIntent, getString(R.string.preferences_contact_us_title)));
				} catch (ActivityNotFoundException e) {
					UIUtilities.showFormattedToast(PreferencesActivity.this, R.string.preferences_contact_us_email_error,
							getString(R.string.preferences_contact_us_email_address));
				}
				return true;
			}
		});

		// add version and build information
		Preference aboutPreference = preferenceScreen.findPreference(getString(R.string.key_about_application));
		try {
			SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yy HH:mm", Locale.ENGLISH);
			dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
			PackageManager manager = this.getPackageManager();
			PackageInfo info = manager.getPackageInfo(this.getPackageName(), 0);
			aboutPreference.setTitle(getString(R.string.preferences_about_app_title, getString(R.string.app_name), info
					.versionName));
			aboutPreference.setSummary(getString(R.string.preferences_about_app_summary, info.versionCode, dateFormat.format
					(BuildConfig.BUILD_TIME), DebugUtilities.getDeviceDebugSummary(getWindowManager(), getResources())));
		} catch (Exception e) {
			PreferenceCategory aboutCategory = (PreferenceCategory) preferenceScreen.findPreference(getString(R.string
					.key_about_category));
			aboutCategory.removePreference(aboutPreference);
		}
	}

	/**
	 * Binds a preference's summary to its value (via onPreferenceChange), and updates it immediately to show the
	 * current value.
	 */
	private void bindPreferenceSummaryToValue(Preference preference) {
		// set the listener and trigger immediately to update the preference with the current value
		preference.setOnPreferenceChangeListener(this);
		onPreferenceChange(preference, PreferenceManager.getDefaultSharedPreferences(preference.getContext()).getString
				(preference.getKey(), ""));
	}

	/**
	 * When a ListPreference changes, update its summary to reflect its new value.
	 */
	@Override
	public boolean onPreferenceChange(Preference preference, Object value) {
		if (preference instanceof ListPreference) {
			ListPreference listPreference = (ListPreference) preference;
			int index = listPreference.findIndexOfValue(value.toString());

			// set the summary of list preferences to their current value; audio bit rate is a special case
			if (getString(R.string.key_audio_bitrate).equals(listPreference.getKey())) {
				preference.setSummary((index >= 0 ? getString(R.string.current_value_as_sentence, listPreference.getEntries()
						[index]) : "") + " " + getString(R.string.preferences_audio_bitrate_summary)); // getString trims spaces
			} else {
				preference.setSummary(index >= 0 ? listPreference.getEntries()[index] : null);
			}
		}
		return true;
	}

	/**
	 * Deal with the result of the bluetooth directory chooser, updating the stored directory
	 */
	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent resultIntent) {
		switch (requestCode) {
			case MediaPhone.R_id_intent_directory_chooser:
				if (resultCode == Activity.RESULT_OK && resultIntent != null) {
					String resultPath = resultIntent.getStringExtra(SelectDirectoryActivity.RESULT_PATH);
					if (resultPath != null) {
						File newPath = new File(resultPath);
						if (newPath.canRead()) {
							SharedPreferences mediaPhoneSettings = PreferenceManager.getDefaultSharedPreferences
									(PreferencesActivity.this);
							SharedPreferences.Editor prefsEditor = mediaPhoneSettings.edit();
							prefsEditor.putString(getString(R.string.key_bluetooth_directory), resultPath);
							prefsEditor.commit(); // apply() is better, but only in SDK >= 9
						} else {
							UIUtilities.showToast(PreferencesActivity.this, R.string.preferences_bluetooth_directory_error);
						}
					}
				}
				break;

			default:
				super.onActivityResult(requestCode, resultCode, resultIntent);
		}
	}
}
