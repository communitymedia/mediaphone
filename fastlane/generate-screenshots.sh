#!/bin/bash
set -eux

# this script, in combination with the GenerateScreenshots test, handles the entire process of generating screenshots in
# multiple languages, also taking care of installing sample narratives and retrieving created screnshots from the device

# TODO:
# - see https://docs.fastlane.tools/getting-started/android/screenshots/ for other tips (e.g., keyguard disable, etc)
# - alternative for screen unlock: https://stackoverflow.com/a/23223142
# - parse into fastlane directories automatically (e.g., via device type parameter)

# unlock the device's screen (temperemental, but normally fine on devices with no security)
adb shell input keyevent 82

# upload sample narratives - two variants to cope with multiple adb versions/quirks
name=""
adb shell rm -rf /sdcard/Download/import/ || true
adb shell rm -f /sdcard/Download/import
for directory in sample-narratives/*
do
	for file in $directory/*
	do
		adb push "$file" "/sdcard/Download/import/" || true
		name=$(basename "$file")
		adb push "$file" "/sdcard/Download/import/$name" || true
	done
done

# make sure we are able to change the device's language, ignoring failures
# note that this requires https://play.google.com/store/apps/details?id=net.sanapeli.adbchangelanguage as adb-change-language.apk
adb install -r adb-change-language.apk || true
adb shell pm grant net.sanapeli.adbchangelanguage android.permission.CHANGE_CONFIGURATION

# remove any older screenshots (both locally and on the device to avoid permission errors)
rm -rf latest
mkdir latest
adb shell rm -rf /sdcard/Pictures/mediaphone

# uninstall the app so we start afresh (ignoring errors if it is not installed)
# adb shell pm clear ac.robinson.mediaphone # alternative is to clear, but doesn't reset Storage Access Framework permissions
adb shell pm uninstall ac.robinson.mediaphone || true
adb shell pm uninstall ac.robinson.mediaphone.test || true

# install the app and the test package (note: can check instrumentation via adb shell pm list instrumentation)
cd ../
./gradlew installScreenshots
./gradlew installScreenshotsAndroidTest
cd fastlane/

# most permissions can be handled here; Storage Access Framework needs UI interaction to confirm
adb shell pm grant ac.robinson.mediaphone android.permission.CAMERA
adb shell pm grant ac.robinson.mediaphone android.permission.RECORD_AUDIO
adb shell pm grant ac.robinson.mediaphone android.permission.WRITE_EXTERNAL_STORAGE
adb shell pm grant ac.robinson.mediaphone android.permission.READ_EXTERNAL_STORAGE

# import narratives and request Storage Access Framework permissions
adb shell am instrument -w -e class ac.robinson.mediaphone.GenerateScreenshots#stage_1_prepareScreenshots ac.robinson.mediaphone.test/androidx.test.runner.AndroidJUnitRunner

# disable system demo mode and change language initially to avoid a display corruption issue seen in demo mode on some devices when changing language
adb shell am broadcast -a com.android.systemui.demo -e command exit
adb shell settings put global sysui_demo_allowed 0
adb shell am start -n net.sanapeli.adbchangelanguage/.AdbChangeLanguage -e language en

# run the screenshot generation test in all available languages
# TODO: detect available languages automatically as in fastlane.py?
languages=(en es fr nl pl pt ru)
for i in "${languages[@]}"
do
	echo "Generating screenshots for $i"

	# change language - note: this shows the USB debugging notification again, but it disappears before the test has started
	adb shell am start -n net.sanapeli.adbchangelanguage/.AdbChangeLanguage -e language "$i"

	# enable system demo mode
	adb shell settings put global sysui_demo_allowed 1
	adb shell am broadcast -a com.android.systemui.demo -e command exit

	# configure system demo mode - we need to do this each time we change language due to bugs/quirks that affect its appearance on some devices when changing language
	# see also: https://github.com/aosp-mirror/platform_frameworks_base/blob/master/tests/SystemUIDemoModeController/src/com/example/android/demomodecontroller/DemoModeController.java
	# note that clock millis is to work around a device bug that shows 1230 as AM not PM; fully true separate from other mobile commands and sent twice is for similar reasons
	adb shell am broadcast -a com.android.systemui.demo -e command clock -e millis 41400000 &&
	adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false &&
	adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false &&
	adb shell am broadcast -a com.android.systemui.demo -e command status -e bluetooth none &&
	adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi hide &&
	adb shell am broadcast -a com.android.systemui.demo -e command network -e fully true &&
	adb shell am broadcast -a com.android.systemui.demo -e command network -e mobile show -e datatype none -e level 4 &&
	adb shell am broadcast -a com.android.systemui.demo -e command network -e fully true

	# run the actual screenshot task
	adb shell am instrument -w -e class ac.robinson.mediaphone.GenerateScreenshots#stage_2_generateScreenshots ac.robinson.mediaphone.test/androidx.test.runner.AndroidJUnitRunner

	adb shell am broadcast -a com.android.systemui.demo -e command exit
	adb shell settings put global sysui_demo_allowed 0

	# retrieve the new screenshots to the appropriate fastlane directory
	upper=$(tr a-z A-Z <<< ${i})
	mkdir "latest/$i-$upper"
	for s in {1..8}
	do
		adb pull "/sdcard/Pictures/mediaphone/$i/$s.png" "latest/$i-$upper/$s.png"
	done
done

# reset to English
adb shell am start -n net.sanapeli.adbchangelanguage/.AdbChangeLanguage -e language en
