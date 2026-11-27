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
if [[ "$segment" == ledger-diagnostic && "$image" != 'system-images;android-35;google_apis;x86_64' ]]; then
  echo 'Ledger diagnostic requires the Android 35 Google APIs x86_64 image.' >&2
  exit 1
fi
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
  observe_offline() {
    airplane=$("$adb" -s "$serial" shell settings get global airplane_mode_on 2>/dev/null | tr -d '\r') || airplane=unknown
    wifi_setting=$("$adb" -s "$serial" shell settings get global wifi_on 2>/dev/null | tr -d '\r') || wifi_setting=unknown
    [[ "$airplane" == 0 || "$airplane" == 1 ]] || airplane=unknown
    [[ "$wifi_setting" =~ ^[0-3]$ ]] || wifi_setting=unknown

    local wifi_status wifi_status_line
    wifi_status=$("$adb" -s "$serial" shell cmd wifi status 2>/dev/null | tr -d '\r') || wifi_status=
    wifi_status_line=${wifi_status%%$'\n'*}
    case "$wifi_status_line" in
      'Wifi is disabled') wifi_disabled=true ;;
      'Wifi is enabled') wifi_disabled=false ;;
      *) wifi_disabled=unknown ;;
    esac
    default_network=$("$adb" -s "$serial" shell dumpsys connectivity 2>/dev/null | tr -d '\r' |
      awk '
        /^[[:blank:]]*Active default network: / {
          count++
          state = $0
          sub(/^[[:blank:]]*Active default network: /, "", state)
        }
        END {
          if (count != 1) print "unknown"
          else if (state == "none" || state == "-1") print "absent"
          else if (state ~ /^[0-9]+$/) print "present"
          else print "unknown"
        }
      ') || default_network=unknown

    # Android records 3 when Wi-Fi is disabled in airplane mode.
    [[ "$airplane" == 1 && ("$wifi_setting" == 0 || "$wifi_setting" == 3) &&
       "$wifi_disabled" == true && "$default_network" == absent ]]
  }

  report_offline() {
    printf 'DIAGNOSTIC_ONLY_OFFLINE: airplane=%s wifi_setting=%s wifi_disabled=%s default_network=%s\n' \
      "$airplane" "$wifi_setting" "$wifi_disabled" "$default_network"
  }

  if ! "$adb" -s "$serial" shell cmd connectivity airplane-mode enable ||
     ! "$adb" -s "$serial" shell svc data disable ||
     ! "$adb" -s "$serial" shell cmd wifi set-wifi-enabled disabled; then
    observe_offline || true
    report_offline
    echo 'Diagnostic AVD offline setup failed; no APK installed.' >&2
    exit 1
  fi
  offline_deadline=$((SECONDS + 60))
  offline_tries=0
  until observe_offline; do
    offline_tries=$((offline_tries + 1))
    if (( offline_tries == 2 )) && ! "$adb" -s "$serial" shell cmd wifi set-wifi-enabled disabled; then
      report_offline
      echo 'Diagnostic AVD could not disable Wi-Fi; no APK installed.' >&2
      exit 1
    fi
    if (( SECONDS >= offline_deadline )); then
      report_offline
      echo 'Diagnostic AVD offline state was not confirmed; no APK installed.' >&2
      exit 1
    fi
    sleep 1
  done
  report_offline
fi
"$adb" -s "$serial" shell svc power stayon true
"$adb" -s "$serial" shell wm dismiss-keyguard
"$adb" -s "$serial" install -g app/build/outputs/apk/debug/app-debug.apk
"$adb" -s "$serial" install -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

report_identity_mismatch() {
  local trace
  trace=$("$adb" -s "$serial" logcat -d -v raw -s 'LedgerIdentityProbe:E' '*:S' |
    awk '
      /^[[:space:]]*$/ { next }
      /^--------- beginning of (main|system|crash)$/ { next }
      /^branch=reopen-null$/ ||
      /^branch=(reopened-stat|held-stat) regularChanged=(true|false) devChanged=(true|false) inodeChanged=(true|false) sizeChanged=(true|false) mtimeChanged=(true|false) ctimeChanged=(true|false)$/ {
        count++
        entry = $0
        next
      }
      { bad = 1 }
      END {
        if (bad || count != 1) exit 1
        print entry
      }
    ') || return 1
  printf 'DIAGNOSTIC_ONLY_IDENTITY_MISMATCH: %s\n' "$trace"
}

run_instrumentation() {
  local label=$1 result status=0 expected exact=0 stderr_file="$work/instrumentation.stderr"
  shift
  printf '\nRunning Android instrumentation: %s\n' "$label"
  result=$("$adb" -s "$serial" shell am instrument -w -r "$@" < /dev/null 2>"$stderr_file") || status=$?
  printf '%s\n' "$result"
  if [[ -s "$stderr_file" ]]; then cat "$stderr_file" >&2; fi
  if (( status != 0 )); then
    printf 'Instrumentation adb status: %s\n' "$status" >&2
  fi
  case "$label" in
    camera-history-migration) expected='INSTRUMENTATION_RESULT: stream=PASS CameraSync history migration'; exact=1 ;;
    confidence-store) expected='INSTRUMENTATION_RESULT: stream=PASS confidence SQLite:' ;;
    camera-ledger) expected='INSTRUMENTATION_RESULT: stream=PASS camera ledger:' ;;
    camera-ledger-teardown) expected='INSTRUMENTATION_RESULT: stream=PASS camera ledger teardown:' ;;
    *) expected='INSTRUMENTATION_RESULT: stream=PASS ' ;;
  esac
  if (( status != 0 )) || ! awk -v expected="$expected" -v exact="$exact" '
    { if (finished && $0 !~ /^[[:space:]]*$/) bad = 1 }
    /^INSTRUMENTATION_CODE:/ {
      codes++; if (streams != 1 || $0 != "INSTRUMENTATION_CODE: -1") bad = 1
      finished = 1
    }
    /^INSTRUMENTATION_RESULT: stream=/ {
      streams++; if (codes || index($0, expected) != 1 || (exact && $0 != expected)) bad = 1
    }
    /^INSTRUMENTATION_(FAILED|ABORTED):/ { bad = 1 }
    /^INSTRUMENTATION_STATUS_CODE:[[:blank:]]*-[0-9]+[[:space:]]*$/ { bad = 1 }
    /^INSTRUMENTATION_STATUS:[[:blank:]]*Error=/ { bad = 1 }
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
    elif [[ "$label" == viewer-screen ]]; then
      "$adb" -s "$serial" logcat -d -s LoupeViewerTap:I '*:S' || true
    elif [[ "$label" == tag-confidence ||
            "$label" == 'tag-confidence (confidence_rebuild_delay_ms=12000)' ]]; then
      "$adb" -s "$serial" logcat -d -s LoupeConfidenceGate:I '*:S' || true
    fi
    if [[ "${segment:-}" == ledger-diagnostic ]]; then
      if (( status == 0 )) && [[ "$label" == camera-ledger ]] &&
         awk '
           /^[[:space:]]*$/ { next }
           finished { bad = 1; next }
           !stream && $0 == "INSTRUMENTATION_RESULT: stream=java.io.IOException" {
             stream = 1
             next
           }
           summary && $0 == "INSTRUMENTATION_CODE: 0" { finished = 1; next }
           !stream || summary { bad = 1; next }
           !frame {
             if ($0 ~ /^  at jp[.]nano[.]rawgallery[.]camera[.]CameraPhotoImporter[.]savedPhotoChanged[(]CameraPhotoImporter[.]java:[0-9]+[)]$/)
               frame = 1
             else
               bad = 1
             next
           }
           $0 == "Archive device checks failed: IOException" { summary = 1; next }
           /^  at / || /^(Suppressed|Caused by): / { next }
           { bad = 1 }
           END { exit !(finished && !bad) }
         ' <<< "$result" &&
         report_identity_mismatch; then
        exit 3
      fi
      echo "DIAGNOSTIC_ONLY_INCONCLUSIVE: $label did not report PASS with a confirmed identity trace." >&2
      exit 1
    fi
    if (( status != 0 )); then exit "$status"; fi
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
  echo 'DIAGNOSTIC_ONLY_SEQUENCE_PASS: migration, confidence and ledger all reported PASS.'
  exit 2
fi

if [[ "$segment" != before-camera ]]; then
  "$adb" -s "$serial" install -g raw-experiment/build/outputs/apk/debug/raw-experiment-debug.apk
  "$adb" -s "$serial" install -g raw-experiment/build/outputs/apk/androidTest/debug/raw-experiment-debug-androidTest.apk
  run_instrumentation 'raw-edit-full' -e full true \
    jp.nano.rawgallery.rawexperiment.test/jp.nano.rawgallery.RawEditInstrumentation
fi
