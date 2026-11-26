#!/usr/bin/env bash
# Run only against a fresh AVD, with an explicit serial for all adb operations.
set -euo pipefail
cd "$(dirname "$0")/.."
segment=${1:-all}
case "$segment" in
  all|before-camera|camera-onward|ledger-diagnostic) ;;
  *) echo 'Choose all, before-camera, camera-onward or ledger-diagnostic.' >&2; exit 2 ;;
esac
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
mode_list=tests/ci-android-modes.txt
if [[ "$segment" == ledger-diagnostic ]]; then
  printf '%s\n' camera-history-migration confidence-store camera-ledger > "$work/modes.txt"
  mode_list="$work/modes.txt"
elif [[ "$segment" != all ]]; then
  awk -v segment="$segment" '
    $1 == "camera-pilot-usb-launch" { camera_started = 1 }
    (segment == "before-camera" && !camera_started) || (segment == "camera-onward" && camera_started) { print }
    END { if (!camera_started) exit 1 }
  ' "$mode_list" > "$work/modes.txt"
  mode_list="$work/modes.txt"
fi
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
if [[ "$segment" == ledger-diagnostic ]]; then
  "$adb" -s "$serial" shell cmd connectivity airplane-mode enable
  "$adb" -s "$serial" shell svc data disable
  "$adb" -s "$serial" shell cmd wifi set-wifi-enabled disabled
  offline_deadline=$((SECONDS + 60))
  offline_tries=0
  until [[ $("$adb" -s "$serial" shell settings get global airplane_mode_on | tr -d '\r') == 1 ]] &&
        [[ $("$adb" -s "$serial" shell settings get global wifi_on | tr -d '\r') == 0 ]] &&
        "$adb" -s "$serial" shell cmd wifi status | tr -d '\r' | grep -x 'Wifi is disabled' >/dev/null &&
        "$adb" -s "$serial" shell dumpsys connectivity | tr -d '\r' | grep -E 'Active default network: (none|-1)$' >/dev/null; do
    offline_tries=$((offline_tries + 1))
    if (( offline_tries == 2 )); then
      "$adb" -s "$serial" shell cmd wifi set-wifi-enabled disabled
    fi
    if (( SECONDS >= offline_deadline )); then
      echo 'Diagnostic AVD offline state was not confirmed; no APK installed.' >&2
      exit 1
    fi
    sleep 1
  done
fi
"$adb" -s "$serial" shell svc power stayon true
"$adb" -s "$serial" shell wm dismiss-keyguard
"$adb" -s "$serial" install -g app/build/outputs/apk/debug/app-debug.apk
"$adb" -s "$serial" install -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

run_instrumentation() {
  local label=$1 result status=0 expected stderr_file="$work/instrumentation.stderr"
  shift
  printf '\nRunning Android instrumentation: %s\n' "$label"
  result=$("$adb" -s "$serial" shell am instrument -w -r "$@" < /dev/null 2>"$stderr_file") || status=$?
  printf '%s\n' "$result"
  if [[ -s "$stderr_file" ]]; then cat "$stderr_file" >&2; fi
  if (( status != 0 )); then
    printf 'Instrumentation adb status: %s\n' "$status" >&2
    exit "$status"
  fi
  case "$label" in
    camera-history-migration) expected='INSTRUMENTATION_RESULT: stream=PASS CameraSync history migration' ;;
    confidence-store) expected='INSTRUMENTATION_RESULT: stream=PASS confidence SQLite:' ;;
    camera-ledger) expected='INSTRUMENTATION_RESULT: stream=PASS camera ledger:' ;;
    *) expected='INSTRUMENTATION_RESULT: stream=PASS ' ;;
  esac
  if ! awk -v expected="$expected" '
    /^INSTRUMENTATION_CODE:/ { codes++; if ($0 != "INSTRUMENTATION_CODE: -1") bad = 1 }
    /^INSTRUMENTATION_RESULT: stream=/ { streams++; if (index($0, expected) != 1) bad = 1 }
    /^INSTRUMENTATION_(FAILED|ABORTED):/ { bad = 1 }
    /^INSTRUMENTATION_RESULT: (shortMsg|longMsg)=/ { bad = 1 }
    END { exit !(codes == 1 && streams == 1 && !bad) }
  ' <<< "$result"; then
    if [[ "$label" == photo-edit ]]; then
      "$adb" -s "$serial" logcat -d -s LoupeRawEdit:I '*:S' |
        awk '/full[.]prepare|fixture[.]cancel|jpeg[.]load[.]complete/ {print}' || true
    elif [[ "$label" == nano-background ]]; then
      "$adb" -s "$serial" logcat -d -s LoupeNanoFixture:E '*:S' || true
    elif [[ "$label" == visual-status ]]; then
      "$adb" -s "$serial" logcat -d -s LoupeVisualSwipe:I '*:S' || true
    elif [[ "$label" == look-removal ]]; then
      "$adb" -s "$serial" logcat -d -s LoupeLookFixture:I '*:S' || true
    elif [[ "$label" == tag-confidence ||
            "$label" == 'tag-confidence (confidence_rebuild_delay_ms=12000)' ]]; then
      "$adb" -s "$serial" logcat -d -s LoupeConfidenceGate:I '*:S' || true
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
done < "$mode_list"
if ! "$ran"; then
  echo 'No Android instrumentation modes were configured.' >&2
  exit 1
fi

if [[ "$segment" == ledger-diagnostic ]]; then
  echo 'DIAGNOSTIC_ONLY_NO_REPRODUCTION: one ordered synthetic sequence passed; remaining verification not run.'
  exit 2
fi

if [[ "$segment" != before-camera ]]; then
  "$adb" -s "$serial" install -g raw-experiment/build/outputs/apk/debug/raw-experiment-debug.apk
  "$adb" -s "$serial" install -g raw-experiment/build/outputs/apk/androidTest/debug/raw-experiment-debug-androidTest.apk
  run_instrumentation 'raw-edit-full' -e full true \
    jp.nano.rawgallery.rawexperiment.test/jp.nano.rawgallery.RawEditInstrumentation
fi
