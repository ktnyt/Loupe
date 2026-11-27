#!/bin/sh
# Run regressions that need no external photo corpus, device or credentials.
set -eu
cd "$(dirname "$0")/.."
git rev-parse --show-toplevel >/dev/null
case "${1:-}" in
  ''|--junit-in-gradle) ;;
  *) printf 'Usage: %s [--junit-in-gradle]\n' "$0" >&2; exit 2 ;;
esac
tracked() {
  git --literal-pathspecs ls-files --error-unmatch -- "$1" >/dev/null 2>&1
}
for check in scripts/test-*.py tests/test-*.py tests/test_*.py; do
  tracked "$check" || continue
  printf '\nRunning %s\n' "$check"
  python3 "$check"
done
for check in tests/*.sh; do
  tracked "$check" || continue
  case "$check" in
    tests/host-regression.sh|tests/ci-android-instrumentation.sh) continue ;;
    tests/raw-corpus.sh|tests/run.sh)
      printf '\nRequires an external RAW fixture; not run: %s\n' "$check"
      continue ;;
  esac
  if [ "${1:-}" = --junit-in-gradle ]; then
    # HostRegressionTest invokes these same checks, including the RAW full-source shell fixture.
    case "$check" in
      tests/automatic-tags.sh|tests/camera-ble.sh|tests/camera-connection.sh|\
      tests/camera-dual-slot.sh|tests/camera-import-copy.sh|tests/camera-source.sh|\
      tests/diagnostic-writer.sh|tests/ep7-wifi.sh|tests/index-refresh.sh|\
      tests/nano-banana-e2.sh|tests/preparation-storage.sh|tests/ranking.sh|\
      tests/raw-edit-state.sh|tests/raw-full-source.sh|tests/raw-structure.sh|tests/related-photos.sh|\
      tests/share-history.sh|tests/sync-policy.sh|tests/tag-confidence.sh|tests/tag-judgments.sh|\
      tests/video-formats.sh) continue ;;
    esac
  fi
  printf '\nRunning %s\n' "$check"
  sh "$check"
done
