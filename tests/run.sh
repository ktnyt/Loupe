#!/bin/sh
set -eu
if [ "$#" -ne 2 ]; then
  echo 'Usage: tests/run.sh RAW_DIRECTORY REFERENCE_JPEG_DIRECTORY' >&2
  exit 2
fi
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
javac -d "$test_dir" "$project_dir/app/src/main/java/jp/nano/rawgallery/RawPreview.java" "$project_dir/tests/RawPreviewTest.java"
java -cp "$test_dir" RawPreviewTest "$1" "$2"
