#!/usr/bin/env bash
# Run only against a fresh AVD, with an explicit serial for all adb operations.
set -euo pipefail
cd "$(dirname "$0")/.."
sdk=${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}
image=${LOUPE_INSTRUMENTATION_IMAGE:-system-images;android-35;google_apis;x86_64}
work=$(mktemp -d)
export ANDROID_AVD_HOME="$work"
adb="$sdk/platform-tools/adb"
port=5580
serial="emulator-$port"
emulator_pid=
cleanup() {
  if [[ -n "$emulator_pid" ]]; then
    kill "$emulator_pid" 2>/dev/null || true
    wait "$emulator_pid" 2>/dev/null || true
  fi
  rm -rf "$work"
}
trap cleanup EXIT
# Refuse to attach to a pre-existing emulator, even if it happens to use our port.
devices=$("$adb" devices)
if [[ "$devices" == *"$serial"$'\t'* ]]; then
  echo "$serial already exists; refusing to run CI checks on it." >&2
  exit 1
fi
"$sdk/cmdline-tools/latest/bin/avdmanager" create avd --name loupe-ci --package "$image" --device pixel_6 < /dev/null
"$sdk/emulator/emulator" -avd loupe-ci -port "$port" \
  -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect >"$work/emulator.log" 2>&1 &
emulator_pid=$!
deadline=$((SECONDS + 240))
until [[ $("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true) == 1 ]] &&
      "$adb" -s "$serial" shell pm path android >/dev/null 2>&1; do
  if ! kill -0 "$emulator_pid" 2>/dev/null || (( SECONDS >= deadline )); then
    cat "$work/emulator.log" >&2
    echo 'The Loupe CI emulator did not boot.' >&2
    exit 1
  fi
  sleep 2
done
abi=$("$adb" -s "$serial" shell getprop ro.product.cpu.abilist | tr -d '\r')
case ",$abi," in
  *,arm64-v8a,*) ;;
  *) echo "The emulator cannot install the arm64-v8a Loupe APK (ABIs: $abi)." >&2; exit 1 ;;
esac
"$adb" -s "$serial" shell svc power stayon true
"$adb" -s "$serial" shell wm dismiss-keyguard
"$adb" -s "$serial" install -g app/build/outputs/apk/debug/app-debug.apk
"$adb" -s "$serial" install -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
for mode in confidence-store camera-ledger camera-history-migration; do
  printf '\nRunning Android instrumentation: %s\n' "$mode"
  python3 scripts/device-check.py --adb "$adb" --serial "$serial" --mode "$mode" \
    --no-standby-video 'Disposable CI emulator'
done
