#!/usr/bin/env bash
# Installs the release APK on an emulator, opens every screen, and fails if the app crashes.
set -u
PKG=com.jondude.anzu
APK=$(ls AnzuOne-*.apk | head -1)
fail() { echo "::error title=Smoke test::$1"; adb logcat -d -s AndroidRuntime:E | tail -60; exit 1; }
note() { echo "::notice title=Smoke test::$1"; }

mkdir -p shots
shot() { adb exec-out screencap -p > "shots/$1.png" 2>/dev/null || true; }
adb shell cmd uimode night yes || true
adb install -r "$APK" || fail "install failed"
for p in BLUETOOTH_CONNECT BLUETOOTH_SCAN RECORD_AUDIO POST_NOTIFICATIONS ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION READ_CALENDAR WRITE_CALENDAR READ_CONTACTS SEND_SMS CALL_PHONE ACTIVITY_RECOGNITION; do
  adb shell pm grant $PKG android.permission.$p || true
done
adb logcat -c

alive() { adb shell pidof $PKG >/dev/null 2>&1; }
crashed() { adb logcat -d -s AndroidRuntime:E | grep -q "Process: $PKG"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml > ui.xml; }
texts() { dump; python3 - <<'PY'
import re, html
x = open('ui.xml', encoding='utf-8', errors='ignore').read()
t = [html.unescape(m) for m in re.findall(r'text="([^"]*)"', x) if m]
print('|'.join(t)[:900])
PY
}
has() { dump; python3 - "$1" <<'PY'
import sys, re, html
x = html.unescape(open('ui.xml', encoding='utf-8', errors='ignore').read())
sys.exit(0 if sys.argv[1] in x else 1)
PY
}
tap() { # tap the centre of the LAST element whose text is $1 (the nav bar sits last)
  dump
  local xy; xy=$(python3 - "$1" <<'PY'
import sys, re
x = open('ui.xml', encoding='utf-8', errors='ignore').read()
m = re.findall(r'(?:text|content-desc)="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(sys.argv[1]), x)
if m:
    a, b, c, d = map(int, m[-1]); print((a + c) // 2, (b + d) // 2)
PY
)
  [ -z "$xy" ] && return 1
  adb shell input tap $xy
}
PASSED=""
check() { sleep 4; alive || fail "process died: $1"; crashed && fail "crash: $1"; shot "$(echo "$1" | tr ' A-Z' '_a-z')"; PASSED="$PASSED$1; "; echo "OK $1: $(texts)"; }

adb shell am start -W -n $PKG/.MainActivity || fail "could not start MainActivity"
check "Home screen"
adb shell dumpsys activity services $PKG | grep -q GlassesService && PASSED="${PASSED}Background service running; " || fail "background service is not running"

for tab in Notes Whisper Settings Corin; do
  tap "$tab" || { sleep 5; tap "$tab"; } || fail "tab $tab not found: $(texts)"
  check "$tab tab"
done

# Type a message with no API key: Corin should answer honestly instead of crashing.
tap "Type to Corin" || fail "message box not found"
adb shell input text "hello"; adb shell input keyevent 66
sleep 6
has "reach a model" || fail "typed message got no reply: $(texts)"
check "Typed message answered"

# Unprompted speech: the Speak up button should produce a line.
has "Speak up" || { adb shell input keyevent 4; sleep 2; }   # close the on-screen keyboard
tap "Speak up" || fail "Speak up button not found: $(texts)"
sleep 8
has "reach a model" && fail "Speak up produced nothing new: $(texts)"
check "Spoke up on its own"
adb shell am force-stop $PKG; adb shell am start -W -n $PKG/.MainActivity; sleep 3

# Add a note through the UI.
tap "Notes"; sleep 2
tap "New note" || fail "note box not found"
adb shell input text "buy%sbelt"; adb shell input keyevent 4; sleep 1
tap "Add" || fail "Add button not found"
sleep 2
has "buy belt" || fail "note did not appear: $(texts)"
check "Note added"

# Notification access on, then a notification arrives: the listener must not crash.
adb shell cmd notification allow_listener $PKG/$PKG.NotifyListener || true
adb shell cmd notification post -t "Smoke" smoke "hello from the smoke test" || true
check "Notification listener"

# Scroll the settings screen top to bottom, checking each section is there.
tap "Settings"; sleep 2
seen=""
for i in $(seq 1 30); do
  dump
  for sec in Brain Identity Presence "Glasses and earbuds" Voice Listening "Buddie earbuds" "Home Assistant" "Hermes and your vault" "Obelisk and local models" App; do
    case "$seen" in *"[$sec]"*) ;; *) grep -q "text=\"$sec\"" ui.xml && seen="$seen[$sec]";; esac
  done
  adb shell input swipe 12 1500 12 600 300; sleep 1
done
for sec in Brain Identity Presence "Glasses and earbuds" Voice Listening "Buddie earbuds" "Home Assistant" "Hermes and your vault" "Obelisk and local models" App; do
  case "$seen" in *"[$sec]"*) ;; *) fail "Settings section missing: $sec";; esac
done
check "Settings scrolled"

# Voice picker: open the phone voice list from the Voice section.
tap "Corin"; sleep 1; tap "Settings"; sleep 2
tap "Voice" || fail "Voice settings row not found: $(texts)"; sleep 2; shot settings_voice_page
for i in $(seq 1 20); do has "Choose voice" && break; adb shell input swipe 12 1400 12 900 300; sleep 1; done
tap "Choose voice" || fail "voice picker button not found: $(texts)"
sleep 3
has "Voices (tap to hear)" || fail "voice picker did not open: $(texts)"
check "Voice picker"
adb shell input keyevent 4; sleep 1

# The glasses-gesture entry point.
adb shell am start -W -a android.intent.action.VOICE_COMMAND -n $PKG/.assist.AssistActivity || fail "assistant entry point failed"
check "Assistant gesture screen"
adb shell input keyevent 4

# Rotate and relaunch.
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity
check "Relaunch"
# The same screens in light mode, for the screenshots.
adb shell cmd uimode night no || true
adb shell am force-stop $PKG; adb shell am start -W -n $PKG/.MainActivity; sleep 5; shot light_home
tap "Settings"; sleep 3; shot light_settings
tap "Notes"; sleep 3; shot light_notes
note "Device log: $(adb logcat -d -s CORINTEST:I | grep CORINTEST | sed 's/.*CORINTEST: //' | tr '\n' '|' | cut -c1-700)"
note "All steps passed: $PASSED"
echo "SMOKE PASSED"
