#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
javac -d "$test_dir" \
  "$project_dir/app/src/main/java/jp/nano/rawgallery/camera/CameraImportFile.java" \
  "$project_dir/app/src/main/java/jp/nano/rawgallery/camera/CameraImportCopy.java" \
  "$project_dir/tests/CameraImportCopyTest.java"
java -cp "$test_dir" jp.nano.rawgallery.camera.CameraImportCopyTest
