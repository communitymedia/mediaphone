package ac.robinson.mediaphone;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;

import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiObjectNotFoundException;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 18)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class GenerateScreenshots {

	static {
		BuildConfig.IS_TESTING.set(true); // required only to enable interaction with PlaybackActivity
	}

	private static final int LAUNCH_TIMEOUT = 5000;
	private static final int ACTION_WAIT_DELAY = 1000; // how long to wait for, e.g., dialog boxes to appear
	private static final int TOAST_WAIT_DELAY = 3500; // based on the (private) NotificationManagerService.LONG_DELAY

	private static final int LONG_CLICK_DURATION = 750;

	private static final String NARRATIVE_BROWSER_ROOT = "list_narratives";
	private static final String FRAME_EDITOR_ROOT = "frame_editor_root";
	private static final String PLAYBACK_ROOT = "playback_root";

	private static final int SAMPLE_NARRATIVE_NUMBER = 5;

	private UiDevice mDevice;

	@Before
	public void startMainActivityFromHomeScreen() throws TimeoutException {
		Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
		Context context = instrumentation.getTargetContext();
		mDevice = UiDevice.getInstance(instrumentation);
		mDevice.pressHome();

		// wait for launcher
		final String launcherPackage = mDevice.getLauncherPackageName();
		assertThat(launcherPackage, notNullValue());
		mDevice.wait(Until.hasObject(By.pkg(launcherPackage).depth(0)), LAUNCH_TIMEOUT);

		// launch the app (clearing any previous instances) and wait for it to appear
		final Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(BuildConfig.APPLICATION_ID);
		launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
		context.startActivity(launchIntent);

		mDevice.wait(Until.hasObject(By.pkg(BuildConfig.APPLICATION_ID).depth(0)), LAUNCH_TIMEOUT);
	}

	private UiObject2 clickFrameAndWait(Context context, int narrativeNumber, int frameNumber, boolean longClick)
			throws TimeoutException {
		// UiCollection doesn't seem to work with custom collections, so we rely on content descriptions instead
		List<UiObject2> narratives = mDevice.findObjects(By.res(BuildConfig.APPLICATION_ID, "list_frames"));
		boolean found = false;
		for (UiObject2 narrative : narratives) {
			String contentDescription = narrative.getContentDescription();
			if (!TextUtils.isEmpty(contentDescription) &&
					contentDescription.contains(context.getString(R.string.narrative_browser_row_label, narrativeNumber))) {
				List<UiObject2> frames = narrative.findObjects(By.res(BuildConfig.APPLICATION_ID, "frame_item_image"));
				for (UiObject2 frame : frames) {
					contentDescription = frame.getContentDescription();
					if (!TextUtils.isEmpty(contentDescription) && contentDescription.contains(
							context.getString(R.string.frame_thumbnail_description_generic, frameNumber))) {
						if (longClick) {
							frame.click(LONG_CLICK_DURATION);
						} else {
							frame.click();
						}
						found = true;
						break; // avoid StaleObjectException (and unnecessary additional loops)
					}
				}
				if (found) {
					break;
				}
			}
		}

		return waitForResource(longClick ? PLAYBACK_ROOT : FRAME_EDITOR_ROOT);
		// mDevice.wait(Until.findObject(By.res(BuildConfig.APPLICATION_ID, longClick ? PLAYBACK_ROOT : FRAME_EDITOR_ROOT)),
		// 		LAUNCH_TIMEOUT)
	}

	private UiObject2 waitForResource(String resourceIdentifier) throws TimeoutException {
		// the default implementation of mDevice.wait has a 1 second poll interval that is not publicly configurable...
		UiObject2 foundObject = null;
		long startTime = SystemClock.uptimeMillis();
		long interval = 100;
		for (long elapsedTime = 0; foundObject == null; elapsedTime = SystemClock.uptimeMillis() - startTime) {

			if (elapsedTime >= LAUNCH_TIMEOUT) {
				throw new TimeoutException("Timed out waiting for resource " + resourceIdentifier);
			}

			SystemClock.sleep(interval);
			foundObject = mDevice.findObject(By.res(BuildConfig.APPLICATION_ID, resourceIdentifier));
		}
		return foundObject;
	}

	private UiObject2 pressBackAndWaitForResource(String resourceIdentifier) throws TimeoutException {
		mDevice.pressBack();
		return waitForResource(resourceIdentifier);
		// mDevice.wait(Until.findObject(By.res(BuildConfig.APPLICATION_ID, resourceIdentifier)), LAUNCH_TIMEOUT);
	}

	@Test
	public void stage_1_prepareScreenshots() throws TimeoutException, UiObjectNotFoundException {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

		// request access for all files so we can import the narratives on SDK >= 29 without major changes to app code
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			Intent storageAccessFrameworkIntent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
					Uri.parse("package:" + BuildConfig.APPLICATION_ID));
			storageAccessFrameworkIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			context.startActivity(storageAccessFrameworkIntent);

			// see: https://stackoverflow.com/a/15304289/
			final UiSelector switchSelector = new UiSelector().className(android.widget.Switch.class.getName());
			UiObject allFilesAccessSwitch = mDevice.findObject(switchSelector);
			if (allFilesAccessSwitch.waitForExists(LAUNCH_TIMEOUT) && allFilesAccessSwitch.isEnabled()) {
				if (!allFilesAccessSwitch.isChecked()) {
					allFilesAccessSwitch.click();
				}
				mDevice.pressBack();
			} else {
				throw new UiObjectNotFoundException("Unable to enable all files access - switch not found");
			}

			UiObject2 foundNarrativeBrowser = null;
			while (foundNarrativeBrowser == null) {
				try {
					foundNarrativeBrowser = waitForResource(NARRATIVE_BROWSER_ROOT);
				} catch (TimeoutException ignored) {
				}
			}
		}

		// import sample narratives - note that narrativeNumber is dependent on the number of samples imported
		openActionBarOverflowOrOptionsMenu(context);
		SystemClock.sleep(ACTION_WAIT_DELAY); // wait for the menu to appear
		onView(withText(R.string.menu_scan_imports)).perform(click());

		// wait for narratives to be imported
		UiObject2 foundFrame = null;
		while (foundFrame == null) {
			try {
				foundFrame = clickFrameAndWait(context, SAMPLE_NARRATIVE_NUMBER, 1, false);
			} catch (TimeoutException ignored) {
			}
		}
		pressBackAndWaitForResource(NARRATIVE_BROWSER_ROOT);
		SystemClock.sleep(TOAST_WAIT_DELAY); // wait for R.string.import_finished Toast to disappear
	}

	@Test
	public void stage_2_generateScreenshots() throws TimeoutException, IOException {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		String language_code = Locale.getDefault().toString();
		File screenshotDirectory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
				MediaPhone.APPLICATION_NAME + "/" + language_code);
		//noinspection ResultOfMethodCallIgnored
		screenshotDirectory.mkdirs();
		if (!screenshotDirectory.exists()) {
			throw new IOException("Couldn't create screenshot directory");
		}

		System.out.println(
				"Generating screenshots for language " + language_code + "; saving to " + screenshotDirectory.getAbsolutePath());

		// narrative browser
		assertTrue(mDevice.takeScreenshot(new File(screenshotDirectory, "1.png")));

		// blank frame editor
		mDevice.findObject(By.res(BuildConfig.APPLICATION_ID, "add_narrative_button")).click();
		mDevice.wait(Until.findObject(By.res(BuildConfig.APPLICATION_ID, "frame_editor_root")), LAUNCH_TIMEOUT);
		assertTrue(mDevice.takeScreenshot(new File(screenshotDirectory, "2.png")));
		pressBackAndWaitForResource(NARRATIVE_BROWSER_ROOT);

		// image editor with photo example
		clickFrameAndWait(context, SAMPLE_NARRATIVE_NUMBER, 1, false);
		mDevice.findObject(By.res(BuildConfig.APPLICATION_ID, "button_take_picture_video")).click();
		mDevice.wait(Until.findObject(By.res(BuildConfig.APPLICATION_ID, "camera_view_root")), LAUNCH_TIMEOUT);
		SystemClock.sleep(TOAST_WAIT_DELAY); // wait for R.string.retake_picture_hint Toast to disappear
		assertTrue(mDevice.takeScreenshot(new File(screenshotDirectory, "3.png")));
		pressBackAndWaitForResource(FRAME_EDITOR_ROOT);

		// audio editor with audio example (assumes there is already one audio recording in the sample)
		mDevice.findObject(By.res(BuildConfig.APPLICATION_ID, "button_record_audio_2")).click();
		mDevice.wait(Until.findObject(By.res(BuildConfig.APPLICATION_ID, "audio_view_root")), LAUNCH_TIMEOUT);
		mDevice.findObject(By.res(BuildConfig.APPLICATION_ID, "button_record_audio")).click();
		// SystemClock.sleep(2600); // as with playback, the handler causes ~10s delay, so we don't actually need to sleep here
		mDevice.findObject(By.res(BuildConfig.APPLICATION_ID, "button_record_audio")).click();
		assertTrue(mDevice.takeScreenshot(new File(screenshotDirectory, "4.png")));

		// delete the audio we just recorded so we leave the narrative in the same state we found it
		pressBackAndWaitForResource("button_delete_audio").click();
		SystemClock.sleep(ACTION_WAIT_DELAY); // wait for the dialog to appear
		onView(withText(R.string.button_delete)).perform(click());
		SystemClock.sleep(2 * TOAST_WAIT_DELAY); // wait for R.string.retake_audio_hint and delete_audio_succeeded to go

		// text editor with text example
		mDevice.findObject(By.res(BuildConfig.APPLICATION_ID, "button_add_text")).click();
		mDevice.wait(Until.findObject(By.res(BuildConfig.APPLICATION_ID, "text_view_root")), LAUNCH_TIMEOUT);
		SystemClock.sleep(ACTION_WAIT_DELAY); // wait for keyboard to appear
		assertTrue(mDevice.takeScreenshot(new File(screenshotDirectory, "5.png")));
		mDevice.pressBack(); // to hide the keyboard
		pressBackAndWaitForResource(FRAME_EDITOR_ROOT);

		// frame editor with content
		assertTrue(mDevice.takeScreenshot(new File(screenshotDirectory, "6.png")));
		pressBackAndWaitForResource(NARRATIVE_BROWSER_ROOT);

		// playback screen
		clickFrameAndWait(context, SAMPLE_NARRATIVE_NUMBER, 1, true);
		mDevice.findObject(By.res(BuildConfig.APPLICATION_ID, "playback_root")).click(); // to hide playback bar
		SystemClock.sleep(ACTION_WAIT_DELAY); // wait for playback bar to hide
		assertTrue(mDevice.takeScreenshot(new File(screenshotDirectory, "7.png")));

		// share popup - for some reason the share button isn't discoverable, so we need to use Espresso instead
		mDevice.findObject(By.res(BuildConfig.APPLICATION_ID, "playback_root")).click(); // to show playback bar
		SystemClock.sleep(ACTION_WAIT_DELAY); // wait for playback bar to appear
		openActionBarOverflowOrOptionsMenu(context);
		SystemClock.sleep(ACTION_WAIT_DELAY); // wait for the menu to appear
		onView(withText(R.string.menu_export)).perform(click());
		SystemClock.sleep(ACTION_WAIT_DELAY); // wait for the menu to appear
		assertTrue(mDevice.takeScreenshot(new File(screenshotDirectory, "8.png")));
	}
}
