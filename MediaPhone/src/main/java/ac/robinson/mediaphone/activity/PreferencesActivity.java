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

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.text.TextUtils;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
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
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * A {@link PreferenceActivity} for editing application settings.
 */
public class PreferencesActivity extends PreferenceActivity implements Preference.OnPreferenceChangeListener {

	private AppCompatDelegate mDelegate;

	private static final int PERMISSION_WRITE_STORAGE_PHOTOS = 104;
	private static final int PERMISSION_WRITE_STORAGE_AUDIO = 105;
	private static final int PERMISSION_WRITE_STORAGE_IMPORT_EXPORT = 106;

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

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case android.R.id.home:
				finish();
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
	}

	/**
	 * Loads preferences from XML and sets up preferences that need more configuration than just loading. For example,
	 * options like audio bit rate will be hidden on the oldest devices and those that don't support M4A, and the option
	 * for showing a back button will be hidden on Honeycomb or later.
	 */
	private void setupPreferences() {
		addPreferencesFromResource(R.xml.preferences);
		PreferenceScreen preferenceScreen = getPreferenceScreen();

		int[] preferencesRequiringPermissions = { R.string.key_pictures_to_media, R.string.key_audio_to_media };
		for (int preferenceKey : preferencesRequiringPermissions) {
			Preference preference = findPreference(getString(preferenceKey));
			preference.setOnPreferenceChangeListener(PreferencesActivity.this);
			if (preference instanceof CheckBoxPreference) {
				CheckBoxPreference checkBoxPreference = (CheckBoxPreference) preference;
				if (checkBoxPreference.isChecked()) {
					onPreferenceChange(preference, Boolean.TRUE); // so we check and update if they've removed the permission
				}
			}
		}

		// show the current audio recording, resampling bitrate (below) and export quality/format preferences
		bindPreferenceSummaryToValue(findPreference(getString(R.string.key_audio_bitrate)));
		bindPreferenceSummaryToValue(findPreference(getString(R.string.key_video_quality)));
		bindPreferenceSummaryToValue(findPreference(getString(R.string.key_video_format)));


		// disabling audio resampling will break MP4 export if there are multiple audio files (only MOV files support segmented
		// audio), so we remove it from the list (note: handling devices where this option was selected is in UpgradeManager)
		String resamplingKey = getString(R.string.key_audio_resampling_bitrate);
		ListPreference resamplingRate = (ListPreference) findPreference(resamplingKey);
		bindPreferenceSummaryToValue(resamplingRate); // this is also a preference that has a summary update
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
			Resources res = getResources();
			String[] resamplingEntries = res.getStringArray(R.array.preferences_audio_resampling_entries);
			String[] resamplingValues = res.getStringArray(R.array.preferences_audio_resampling_values);

			final ArrayList<String> tempList = new ArrayList<>();
			Collections.addAll(tempList, resamplingEntries);
			tempList.remove(0);
			resamplingRate.setEntries(tempList.toArray(new String[0]));

			tempList.clear();
			Collections.addAll(tempList, resamplingValues);
			tempList.remove(0);
			resamplingRate.setEntryValues(tempList.toArray(new String[0]));
		} else {
			// older devices have a different summary with an explanation of resampling
			resamplingRate.setSummary(getString(R.string.preferences_audio_resampling_summary_legacy));
		}

		// set up the select export directory option with the current chosen directory; register its click listener
		Preference exportButton = findPreference(getString(R.string.key_export_directory));
		exportButton.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
			@Override
			public boolean onPreferenceClick(Preference preference) {
				// exporting narratives requires permissions
				if (ContextCompat.checkSelfPermission(PreferencesActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
						PackageManager.PERMISSION_GRANTED) {
					if (ActivityCompat.shouldShowRequestPermissionRationale(PreferencesActivity.this,
							Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
						UIUtilities.showFormattedToast(PreferencesActivity.this, R.string.permission_storage_rationale,
								getString(R.string.app_name));
					}
					ActivityCompat.requestPermissions(PreferencesActivity.this, new String[]{
							Manifest.permission.WRITE_EXTERNAL_STORAGE
					}, PERMISSION_WRITE_STORAGE_IMPORT_EXPORT);
					return false;
				} else {
					SharedPreferences mediaPhoneSettings = preference.getSharedPreferences();
					File currentDirectory = null;
					String selectedOutputDirectory = mediaPhoneSettings.getString(getString(R.string.key_export_directory),
							null);
					if (!TextUtils.isEmpty(selectedOutputDirectory)) {
						File outputFile = new File(selectedOutputDirectory);
						if (outputFile.exists()) {
							currentDirectory = outputFile;
						}
					}
					if (currentDirectory == null) {
						currentDirectory =
								new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
										getString(R.string.export_local_directory));
					}

					final Intent intent = new Intent(getBaseContext(), SelectDirectoryActivity.class);
					intent.putExtra(SelectDirectoryActivity.START_PATH, currentDirectory.getAbsolutePath());
					startActivityForResult(intent, MediaPhone.R_id_intent_export_directory_chooser);
					return true;
				}
			}
		});

		// set up the select bluetooth directory option with the current chosen directory; register its click listener
		Preference bluetoothButton = findPreference(getString(R.string.key_bluetooth_directory));
		bluetoothButton.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
			@Override
			public boolean onPreferenceClick(Preference preference) {
				// importing media or narratives requires permissions
				if (ContextCompat.checkSelfPermission(PreferencesActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
						PackageManager.PERMISSION_GRANTED) {
					if (ActivityCompat.shouldShowRequestPermissionRationale(PreferencesActivity.this,
							Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
						UIUtilities.showFormattedToast(PreferencesActivity.this, R.string.permission_storage_rationale,
								getString(R.string.app_name));
					}
					ActivityCompat.requestPermissions(PreferencesActivity.this, new String[]{
							Manifest.permission.WRITE_EXTERNAL_STORAGE
					}, PERMISSION_WRITE_STORAGE_IMPORT_EXPORT);
					return false;
				} else {
					SharedPreferences mediaPhoneSettings = preference.getSharedPreferences();
					String currentDirectory = null;
					try {
						currentDirectory = mediaPhoneSettings.getString(getString(R.string.key_bluetooth_directory), null);
					} catch (Exception ignored) {
					}
					if (currentDirectory != null) {
						File current = new File(currentDirectory);
						if (!current.exists()) {
							currentDirectory = null;
						}
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
					startActivityForResult(intent, MediaPhone.R_id_intent_import_directory_chooser);
					return true;
				}
			}
		});

		// update the screen orientation option to show the current value
		bindPreferenceSummaryToValue(findPreference(getString(R.string.key_screen_orientation)));

		// hide the back/done button option if we're using the action bar instead TODO: remove this button from layouts?
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
					PreferenceCategory aboutCategory =
							(PreferenceCategory) getPreferenceScreen().findPreference(getString(R.string.key_about_category));
					aboutCategory.removePreference(preference);
					return true;
				}
			});
		} else {
			// the narrative exists - remove the button to prevent multiple installs
			PreferenceCategory aboutCategory =
					(PreferenceCategory) preferenceScreen.findPreference(getString(R.string.key_about_category));
			aboutCategory.removePreference(installHelperPreference);
		}

		// add the contact us button
		Preference contactUsPreference = preferenceScreen.findPreference(getString(R.string.key_contact_us));
		contactUsPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
			@Override
			public boolean onPreferenceClick(Preference preference) {
				Preference aboutPreference = findPreference(getString(R.string.key_about_application));
				String subject = getString(R.string.preferences_contact_us_email_subject, getString(R.string.app_name),
						SimpleDateFormat
						.getDateTimeInstance()
						.format(new java.util.Date()));
				String body = getString(R.string.preferences_contact_us_email_body, aboutPreference.getSummary());
				String mailTo =
						"mailto:" + getString(R.string.preferences_contact_us_email_address) + "?subject=" + Uri.encode(subject) +
								"&body=" + Uri.encode(body);
				Intent emailIntent = new Intent(Intent.ACTION_SENDTO);
				emailIntent.setData(Uri.parse(mailTo));

				//TODO: on some devices this content duplicates the mailto above; on others it replaces it. But it is necessary
				//TODO: to work around a bug in Gmail where the body is sometimes not included at all (!)
				// see: https://medium.com/better-programming/the-imperfect-android-send-email-action-59610dfd1c2d
				emailIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
				emailIntent.putExtra(Intent.EXTRA_TEXT, body);

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
			aboutPreference.setTitle(getString(R.string.preferences_about_app_title, getString(R.string.app_name),
					info.versionName));
			aboutPreference.setSummary(getString(R.string.preferences_about_app_summary, info.versionCode,
					dateFormat.format(BuildConfig.BUILD_TIME), DebugUtilities
					.getDeviceDebugSummary(getWindowManager(), getResources())));
		} catch (Exception e) {
			PreferenceCategory aboutCategory =
					(PreferenceCategory) preferenceScreen.findPreference(getString(R.string.key_about_category));
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
		onPreferenceChange(preference, PreferenceManager.getDefaultSharedPreferences(preference.getContext())
				.getString(preference.getKey(), ""));
	}

	/**
	 * When a ListPreference changes, update its summary to reflect its new value.
	 */
	@Override
	public boolean onPreferenceChange(Preference preference, Object value) {
		final String key = preference.getKey();
		if ((getString(R.string.key_pictures_to_media).equals(key) || getString(R.string.key_audio_to_media).equals(key)) &&
				(Boolean) value) {
			// adding photos or audio to media library requires permissions
			if (ContextCompat.checkSelfPermission(PreferencesActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
					PackageManager.PERMISSION_GRANTED) {
				if (ActivityCompat.shouldShowRequestPermissionRationale(PreferencesActivity.this,
						Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
					UIUtilities.showFormattedToast(PreferencesActivity.this, R.string.permission_storage_rationale,
							getString(R.string.app_name));
				}
				ActivityCompat.requestPermissions(PreferencesActivity.this, new String[]{
						Manifest.permission.WRITE_EXTERNAL_STORAGE
				}, getString(R.string.key_pictures_to_media).equals(key) ? PERMISSION_WRITE_STORAGE_PHOTOS :
						PERMISSION_WRITE_STORAGE_AUDIO);
			}

		} else if (preference instanceof ListPreference) {
			ListPreference listPreference = (ListPreference) preference;
			int index = listPreference.findIndexOfValue(value.toString());

			// set the summary of list preferences to their current value; bitrate, resampling & export quality are special cases
			if (getString(R.string.key_audio_bitrate).equals(key)) {
				preference.setSummary(
						(index >= 0 ? getString(R.string.current_value_as_sentence, listPreference.getEntries()[index]) : "") +
								" " + getString(R.string.preferences_audio_bitrate_summary)); // getString trims spaces
			} else if (getString(R.string.key_audio_resampling_bitrate).equals(key)) {
				preference.setSummary(
						(index >= 0 ? getString(R.string.current_value_as_sentence, listPreference.getEntries()[index]) : "") +
								" " + getString(R.string.preferences_export_quality_summary));
			} else if (getString(R.string.key_video_quality).equals(key)) {
				preference.setSummary(
						(index >= 0 ? getString(R.string.current_value_as_sentence, listPreference.getEntries()[index]) : "") +
								" " + getString(R.string.preferences_export_quality_summary));
			} else if (getString(R.string.key_video_format).equals(key)) {
				preference.setSummary(
						(index >= 0 ? getString(R.string.current_value_as_sentence, listPreference.getEntries()[index]) : "") +
								" " + getString(R.string.preferences_video_format_summary));
			} else {
				preference.setSummary(index >= 0 ? listPreference.getEntries()[index] : null);
			}
		}
		return true;
	}

	/**
	 * Deal with the result of the bluetooth / export directory choosers, updating the stored directory
	 */
	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent resultIntent) {
		switch (requestCode) {
			case MediaPhone.R_id_intent_import_directory_chooser:
				updateDirectoryPreference(resultCode, resultIntent, R.string.key_bluetooth_directory);
				break;

			case MediaPhone.R_id_intent_export_directory_chooser:
				updateDirectoryPreference(resultCode, resultIntent, R.string.key_export_directory);
				break;

			default:
				super.onActivityResult(requestCode, resultCode, resultIntent);
		}
	}

	private void updateDirectoryPreference(int resultCode, Intent resultIntent, int directoryKey) {
		if (resultCode == Activity.RESULT_OK && resultIntent != null) {
			String resultPath = resultIntent.getStringExtra(SelectDirectoryActivity.RESULT_PATH);
			if (resultPath != null) {
				File newPath = new File(resultPath);
				if (newPath.canRead()) {
					SharedPreferences mediaPhoneSettings =
							PreferenceManager.getDefaultSharedPreferences(PreferencesActivity.this);
					SharedPreferences.Editor prefsEditor = mediaPhoneSettings.edit();
					prefsEditor.putString(getString(directoryKey), resultPath);
					prefsEditor.apply();
				} else {
					UIUtilities.showToast(PreferencesActivity.this, R.string.preferences_directory_error);
				}
			}
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		switch (requestCode) {
			case PERMISSION_WRITE_STORAGE_PHOTOS:
			case PERMISSION_WRITE_STORAGE_AUDIO:
				if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
					UIUtilities.showFormattedToast(PreferencesActivity.this, R.string.permission_storage_error,
							getString(R.string.app_name));
					CheckBoxPreference preference =
							(CheckBoxPreference) findPreference(getString(R.string.key_pictures_to_media));
					preference.setChecked(false);
					preference = (CheckBoxPreference) findPreference(getString(R.string.key_audio_to_media));
					preference.setChecked(false);
				}
				break;

			case PERMISSION_WRITE_STORAGE_IMPORT_EXPORT:
				if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
					UIUtilities.showFormattedToast(PreferencesActivity.this, R.string.permission_storage_error,
							getString(R.string.app_name));
				}
				break;

			default:
				break;
		}
	}
}
