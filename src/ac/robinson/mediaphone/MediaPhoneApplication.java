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
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ac.robinson.mediautilities.MediaUtilities;
import ac.robinson.service.ImportingService;
import ac.robinson.util.IOUtilities;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.util.TypedValue;

public class MediaPhoneApplication extends Application {

	// for communicating with the importing service
	private Messenger mImportingService = null;
	private boolean mImportingServiceIsBound;

	private static WeakReference<MediaPhoneActivity> mCurrentActivity = null;
	private static List<MessageContainer> mSavedMessages = Collections
			.synchronizedList(new ArrayList<MessageContainer>());

	// because messages are reused we need to save their contents instead
	private static class MessageContainer {
		public int what;
		public String data;
	}

	// for clients to communicate with the ImportingService
	private final Messenger mImportingServiceMessenger = new Messenger(new ImportingServiceMessageHandler());

	@Override
	public void onCreate() {
		if (MediaPhone.DEBUG) {
			StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build());
			StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().detectAll().penaltyLog().penaltyDeath().build());
		}
		super.onCreate();
		PreferenceManager.setDefaultValues(this, R.xml.preferences, true);
		initialiseDirectories();
		initialiseParameters();
	}

	private void initialiseDirectories() {

		// make sure we use the right storage location regardless of whether the user has moved the application between
		// SD card and phone; we check for missing files in each activity, so no need to do so here
		boolean useSDCard;
		String storageDirectoryName = MediaPhone.APPLICATION_NAME + "_storage";
		SharedPreferences mediaPhoneSettings = getSharedPreferences(MediaPhone.APPLICATION_NAME, Context.MODE_PRIVATE);
		if (mediaPhoneSettings.contains(MediaPhone.KEY_USE_EXTERNAL_STORAGE)) {
			// setting has previously been saved
			useSDCard = mediaPhoneSettings.getBoolean(MediaPhone.KEY_USE_EXTERNAL_STORAGE,
					IOUtilities.isInstalledOnSdCard(this));
			if (useSDCard) {
				MediaPhone.DIRECTORY_STORAGE = IOUtilities.getExternalStoragePath(this, storageDirectoryName);
			} else {
				MediaPhone.DIRECTORY_STORAGE = IOUtilities.getNewStoragePath(this, storageDirectoryName, false);
			}
		} else {
			// first run
			useSDCard = IOUtilities.isInstalledOnSdCard(this) || IOUtilities.externalStorageIsWritable();
			MediaPhone.DIRECTORY_STORAGE = IOUtilities.getNewStoragePath(this, storageDirectoryName, useSDCard);

			SharedPreferences.Editor prefsEditor = mediaPhoneSettings.edit();
			prefsEditor.putBoolean(MediaPhone.KEY_USE_EXTERNAL_STORAGE, useSDCard);
			prefsEditor.commit(); // apply is better, but only in API > 8
		}

		// use cache directories for thumbnails and temp (outgoing) files; don't clear
		MediaPhone.DIRECTORY_THUMBS = IOUtilities.getNewCachePath(this, MediaPhone.APPLICATION_NAME + "_thumbs", false);

		// temporary directory must be world readable to be able to send files
		if (IOUtilities.mustCreateTempDirectory(this)) {
			if (IOUtilities.externalStorageIsWritable()) {
				MediaPhone.DIRECTORY_TEMP = new File(Environment.getExternalStorageDirectory(),
						MediaPhone.APPLICATION_NAME + "_temp");
				MediaPhone.DIRECTORY_TEMP.mkdirs();
				if (!MediaPhone.DIRECTORY_TEMP.exists()) {
					MediaPhone.DIRECTORY_TEMP = null;
				} else {
					IOUtilities.setFullyPublic(MediaPhone.DIRECTORY_TEMP);
					for (File child : MediaPhone.DIRECTORY_TEMP.listFiles()) {
						IOUtilities.deleteRecursive(child);
					}
				}
			} else {
				MediaPhone.DIRECTORY_TEMP = null;
			}
		} else {
			// create, deleting existing temp directory
			MediaPhone.DIRECTORY_TEMP = IOUtilities.getNewCachePath(this, MediaPhone.APPLICATION_NAME + "_temp", true);

			// delete any leftovers
			if (IOUtilities.externalStorageIsWritable()) {
				File oldTempDirectory = new File(Environment.getExternalStorageDirectory(), MediaPhone.APPLICATION_NAME
						+ "_temp");
				if (oldTempDirectory.exists()) {
					oldTempDirectory.delete();
				}
			}
		}
	}

	private void initialiseParameters() {
		Resources res = getResources();

		MediaPhone.ANIMATION_FADE_TRANSITION_DURATION = res.getInteger(R.integer.animation_fade_transition_duration);
		MediaPhone.ANIMATION_ICON_SHOW_DELAY = res.getInteger(R.integer.animation_icon_show_delay);
		MediaPhone.ANIMATION_ICON_REFRESH_DELAY = res.getInteger(R.integer.animation_icon_refresh_delay);
		MediaPhone.ANIMATION_POPUP_SHOW_DELAY = res.getInteger(R.integer.animation_popup_show_delay);
		MediaPhone.ANIMATION_POPUP_HIDE_DELAY = res.getInteger(R.integer.animation_popup_hide_delay);

		// for swiping between activities
		MediaPhone.SWIPE_MIN_DISTANCE = res.getDimensionPixelSize(R.dimen.swipe_minimum_distance);
		MediaPhone.SWIPE_MAX_OFF_PATH = res.getDimensionPixelSize(R.dimen.swipe_maximum_off_path);
		MediaPhone.SWIPE_THRESHOLD_VELOCITY = res.getDimensionPixelSize(R.dimen.swipe_velocity_threshold);

		// for pressing two frames at once
		MediaPhone.TWO_FINGER_PRESS_INTERVAL = res.getInteger(R.integer.two_finger_press_interval);

		// for flinging to the end of the horizontal frame list
		TypedValue resourceValue = new TypedValue();
		res.getValue(R.attr.fling_to_end_minimum_ratio, resourceValue, true);
		MediaPhone.FLING_TO_END_MINIMUM_RATIO = resourceValue.getFloat();
	}

	public void registerActivityHandle(MediaPhoneActivity activity) {
		if (mCurrentActivity != null) {
			mCurrentActivity.clear();
			mCurrentActivity = null;
		}
		mCurrentActivity = new WeakReference<MediaPhoneActivity>(activity);
		for (MessageContainer msg : mSavedMessages) {
			// must duplicate the data here, or we crash
			Message clientMessage = Message.obtain(null, msg.what, 0, 0);
			Bundle messageBundle = new Bundle();
			messageBundle.putString(MediaUtilities.KEY_FILE_NAME, msg.data);
			clientMessage.setData(messageBundle);

			activity.processIncomingFiles(clientMessage);
		}
		mSavedMessages.clear();
	}

	public void removeActivityHandle(MediaPhoneActivity activity) {
		if (mCurrentActivity != null) {
			if (mCurrentActivity.get().equals(activity)) {
				mCurrentActivity.clear();
				mCurrentActivity = null;
			}
		}
	}

	private static class ImportingServiceMessageHandler extends Handler {

		@Override
		public void handleMessage(final Message msg) {
			if (mCurrentActivity != null) {
				MediaPhoneActivity currentActivity = mCurrentActivity.get();
				if (currentActivity != null) {
					currentActivity.processIncomingFiles(msg);
				}
			} else {
				MessageContainer clientMessage = new MessageContainer();
				clientMessage.what = msg.what;
				clientMessage.data = msg.peekData().getString(MediaUtilities.KEY_FILE_NAME);
				mSavedMessages.add(clientMessage);
			}
		}
	}

	private ServiceConnection mConnection = new ServiceConnection() {
		public void onServiceConnected(ComponentName className, IBinder service) {
			mImportingService = new Messenger(service);
			try {
				Message msg = Message.obtain(null, MediaUtilities.MSG_REGISTER_CLIENT);
				msg.replyTo = mImportingServiceMessenger;
				mImportingService.send(msg);

			} catch (RemoteException e) {
				// service has crashed before connecting; will be disconnected, restarted & reconnected automatically
			}
		}

		public void onServiceDisconnected(ComponentName className) {
			mImportingService = null; // unexpectedly disconnected/crashed
		}
	};

	public void startWatchingBluetooth(boolean watchWithoutBluetoothEnabled) {
		SharedPreferences mediaPhoneSettings = PreferenceManager
				.getDefaultSharedPreferences(MediaPhoneApplication.this);
		String watchedDirectory = getString(R.string.default_bluetooth_directory);
		if (!(new File(watchedDirectory).exists())) {
			watchedDirectory = getString(R.string.default_bluetooth_directory_alternative);
		}
		try {
			String settingsDirectory = mediaPhoneSettings.getString(getString(R.string.key_bluetooth_directory),
					watchedDirectory);
			watchedDirectory = settingsDirectory;
		} catch (Exception e) {
		}
		if (watchWithoutBluetoothEnabled || !watchedDirectory.equals(MediaPhone.IMPORT_DIRECTORY)) {
			stopWatchingBluetooth();
			MediaPhone.IMPORT_DIRECTORY = watchedDirectory;
		}
		if (!mImportingServiceIsBound) {
			final Intent bindIntent = new Intent(MediaPhoneApplication.this, ImportingService.class);
			bindIntent
					.putExtra(MediaUtilities.KEY_OBSERVER_CLASS, "ac.robinson.mediaphone.importing.BluetoothObserver");
			bindIntent.putExtra(MediaUtilities.KEY_OBSERVER_PATH, MediaPhone.IMPORT_DIRECTORY);
			bindIntent.putExtra(MediaUtilities.KEY_OBSERVER_REQUIRE_BT, !watchWithoutBluetoothEnabled);
			bindService(bindIntent, mConnection, Context.BIND_AUTO_CREATE);
			mImportingServiceIsBound = true;
		}
	}

	public void stopWatchingBluetooth() {
		if (mImportingServiceIsBound) {
			if (mImportingService != null) {
				try {
					Message msg = Message.obtain(null, MediaUtilities.MSG_DISCONNECT_CLIENT);
					msg.replyTo = mImportingServiceMessenger;
					mImportingService.send(msg);
				} catch (RemoteException e) {
				}
			}
			unbindService(mConnection);
			mImportingServiceIsBound = false;
		}
	}
}
