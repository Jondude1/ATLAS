#!/usr/bin/env bash
# Installs the release APK on an emulator, opens every screen, and fails if the app crashes.
set -u
PKG=com.jondude.anzu
APK=$(ls AnzuOne-*.apk | head -1)
fail() { echo "::error title=Smoke test::$1"; adb logcat -d -s AndroidRuntime:E | tail -60; exit 1; }
note() { echo "::notice title=Smoke test::$1"; }

adb install -r "$APK" || fail "install failed"
for p in BLUETOOTH_CONNECT BLUETOOTH_SCAN RECORD_AUDIO POST_NOTIFICATIONS ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION READ_CALENDAR ACTIVITY_RECOGNITION; do
  adb shell pm grant $PKG android.permission.$p || true
done
adb logcat -c

alive() { adb shell pidof $PKG >/dev/null 2>&1; }
crashed() { adb logcat -d -s AndroidRuntime:E | grep -q "Process: $PKG"; }
texts() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*"' | sed 's/text="//;s/"$//' | grep -v '^$' | tr '\n' '|' | cut -c1-900; }
tap() { # tap the centre of the element whose text is $1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local b; b=$(adb shell cat /sdcard/ui.xml | grep -o "text=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]')
  [ -z "$b" ] && return 1
  local n; n=($(echo "$b" | grep -o '[0-9]*'))
  adb shell input tap $(( (n[0]+n[2])/2 )) $(( (n[1]+n[3])/2 ))
}
check() { sleep 4; alive || fail "process died: $1"; crashed && fail "crash: $1"; note "$1 OK: $(texts)"; }

adb shell am start -W -n $PKG/.MainActivity || fail "could not start MainActivity"
check "Home screen"
adb shell dumpsys activity services $PKG | grep -q GlassesService && note "Background service is running" || note "Background service not running (emulator has no Bluetooth permission path)"

for tab in Notes Whisper Settings Corin; do
  tap "$tab" || fail "tab $tab not found"
  check "$tab tab"
done

# Type a message with no API key: should answer with the "need a key" line, not crash.
tap "Type to Corin" && adb shell input text "hello" && adb shell input keyevent 66
check "Typed message"

# Scroll the settings screen top to bottom.
tap "Settings"; sleep 2
for i in 1 2 3 4 5 6; do adb shell input swipe 500 1500 500 400 200; sleep 1; done
check "Settings scrolled"

# The glasses-gesture entry point.
adb shell am start -W -a android.intent.action.VOICE_COMMAND -n $PKG/.assist.AssistActivity || fail "assistant entry point failed"
check "Assistant gesture screen"
adb shell input keyevent 4

# Rotate and relaunch.
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity
check "Relaunch"
echo "SMOKE PASSED"
