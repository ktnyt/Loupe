#!/bin/sh
# Run regressions that need no external photo corpus, device or credentials.
set -eu
cd "$(dirname "$0")/.."
git rev-parse --show-toplevel >/dev/null
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
  printf '\nRunning %s\n' "$check"
  sh "$check"
done
