#!/usr/bin/env bash
# Run only against a fresh AVD, with an explicit serial for all adb operations.
set -euo pipefail
cd "$(dirname "$0")/.."
sdk=${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}
image=${LOUPE_INSTRUMENTATION_IMAGE:-system-images;android-35;google_apis;x86_64}
port=${LOUPE_INSTRUMENTATION_PORT:-5580}
if [[ ! "$port" =~ ^[0-9]{4}$ ]] || (( 10#$port < 5554 || 10#$port > 5682 || 10#$port % 2 )); then
  echo 'Use an even emulator console port from 5554 through 5682.' >&2
  exit 2
fi
serial="emulator-$port"
work=$(mktemp -d)
export ANDROID_AVD_HOME="$work"
adb="$sdk/platform-tools/adb"
avd="loupe-ci-${work##*.}"
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
"$sdk/cmdline-tools/latest/bin/avdmanager" create avd --name "$avd" --package "$image" --device pixel_6 < /dev/null
"$sdk/emulator/emulator" -avd "$avd" -port "$port" \
  -no-window -no-audio -no-boot-anim -no-snapshot -gpu swangle >"$work/emulator.log" 2>&1 &
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
running_avd=$("$adb" -s "$serial" emu avd name | tr -d '\r' | head -n 1)
if [[ "$running_avd" != "$avd" ]]; then
  echo "The selected serial does not belong to the disposable AVD ($running_avd)." >&2
  exit 1
fi
abi=$("$adb" -s "$serial" shell getprop ro.product.cpu.abilist | tr -d '\r')
case ",$abi," in
  *,arm64-v8a,*) ;;
  *) echo "The emulator cannot install the arm64-v8a Loupe APK (ABIs: $abi)." >&2; exit 1 ;;
esac
"$adb" -s "$serial" shell svc power stayon true
"$adb" -s "$serial" shell wm dismiss-keyguard
"$adb" -s "$serial" install -g app/build/outputs/apk/debug/app-debug.apk
"$adb" -s "$serial" install -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

run_instrumentation() {
  local label=$1 result
  shift
  printf '\nRunning Android instrumentation: %s\n' "$label"
  result=$("$adb" -s "$serial" shell am instrument -w -r "$@" < /dev/null)
  printf '%s\n' "$result"
  if ! grep -q '^INSTRUMENTATION_RESULT: stream=PASS ' <<< "$result" ||
     ! grep -qx 'INSTRUMENTATION_CODE: -1' <<< "$result"; then
    if [[ "$label" == photo-edit ]]; then
      "$adb" -s "$serial" logcat -d -s LoupeRawEdit:I '*:S' |
        awk '/full[.]prepare|fixture[.]cancel|jpeg[.]load[.]complete/ {print}' || true
    elif [[ "$label" == nano-background ]]; then
      "$adb" -s "$serial" logcat -d -s LoupeNanoFixture:E '*:S' || true
    fi
    echo "$label did not report PASS." >&2
    exit 1
  fi
}

# Modes are ordered because seed/verify pairs must share the same disposable device.
ran=false
while read -r mode key value; do
  case "$mode" in ''|\#*) continue ;; esac
  ran=true
  args=(-e width 400 -e font 1.0)
  if [[ -n "$key" ]]; then
    args+=(-e "$key" "$value")
  fi
  run_instrumentation "$mode${key:+ ($key=$value)}" -e mode "$mode" \
    "${args[@]}" jp.nano.rawgallery.test/jp.nano.rawgallery.ArchiveInstrumentation
done < tests/ci-android-modes.txt
if ! "$ran"; then
  echo 'No Android instrumentation modes were configured.' >&2
  exit 1
fi

"$adb" -s "$serial" install -g raw-experiment/build/outputs/apk/debug/raw-experiment-debug.apk
"$adb" -s "$serial" install -g raw-experiment/build/outputs/apk/androidTest/debug/raw-experiment-debug-androidTest.apk
run_instrumentation 'raw-edit-full' -e full true \
  jp.nano.rawgallery.rawexperiment.test/jp.nano.rawgallery.RawEditInstrumentation
