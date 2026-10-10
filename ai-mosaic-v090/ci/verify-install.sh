#!/usr/bin/env bash
set -euo pipefail
APK=ai-mosaic-v090/project/app/build/outputs/apk/debug/app-debug.apk
PKG=kr.ledoa.cut.mosaic
LEGACY=kr.ledoa.cut.aiface0864
TOOLS=$(dirname "$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -1)")
FIXTURE="$RUNNER_TEMP/legacy-install-fixture"
mkdir -p "$FIXTURE"
# A different signer with the old package ID models an already installed test app.
# It is not the original app or a signature recovery; no user data or old APK is modified.
cat > "$FIXTURE/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="kr.ledoa.cut.aiface0864" android:versionCode="864" android:versionName="install-fixture"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="28"/><application android:label="Legacy identity fixture" android:debuggable="true" android:hasCode="false"/></manifest>
XML
keytool -genkeypair -keystore "$FIXTURE/fixture.keystore" -storepass android -keypass android -alias fixture -keyalg RSA -validity 30 -dname 'CN=Install fixture' >/dev/null 2>&1
"$TOOLS/aapt" package -f -M "$FIXTURE/AndroidManifest.xml" -I "$ANDROID_HOME/platforms/android-35/android.jar" -F "$FIXTURE/unsigned.apk"
"$TOOLS/zipalign" -f 4 "$FIXTURE/unsigned.apk" "$FIXTURE/legacy.apk"
"$TOOLS/apksigner" sign --ks "$FIXTURE/fixture.keystore" --ks-pass pass:android --key-pass pass:android "$FIXTURE/legacy.apk"
adb install "$FIXTURE/legacy.apk"
adb shell run-as "$LEGACY" mkdir -p files
printf 'legacy-data-survives' | adb shell run-as "$LEGACY" tee files/install-sentinel.txt >/dev/null
adb install "$APK"
adb shell run-as "$PKG" mkdir -p files
printf 'mosaic-update-survives' | adb shell run-as "$PKG" tee files/install-sentinel.txt >/dev/null
# Reinstall exactly the distributable APK; the signature and package must support updates.
adb install -r "$APK"
test "$(adb shell run-as "$LEGACY" cat files/install-sentinel.txt | tr -d '\r')" = 'legacy-data-survives'
test "$(adb shell run-as "$PKG" cat files/install-sentinel.txt | tr -d '\r')" = 'mosaic-update-survives'
adb shell am start -W -n "$PKG/kr.ledoa.cut.aitest.MainActivity"
adb shell dumpsys package "$PKG" | grep 'versionCode=901'
echo 'PASS: independent install beside old package with different signer; old data retained; signed reinstall retains new app data.'
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getconf PAGE_SIZE
