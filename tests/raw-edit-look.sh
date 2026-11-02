#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT
javac -d "$BUILD" "$ROOT/app/src/main/java/jp/nano/rawgallery/RawEditState.java" "$ROOT/app/src/main/java/jp/nano/rawgallery/RawEditCurve.java" "$ROOT/app/src/main/java/jp/nano/rawgallery/RawEditTone.java" "$ROOT/app/src/main/java/jp/nano/rawgallery/RawEditCube.java" "$ROOT/app/src/main/java/jp/nano/rawgallery/RawEditCntCalibration.java" "$ROOT/app/src/main/java/jp/nano/rawgallery/RawEditLook.java" "$ROOT/tests/RawEditLookTest.java"
java -cp "$BUILD" jp.nano.rawgallery.RawEditLookTest
java -Xmx16m -cp "$BUILD" jp.nano.rawgallery.RawEditLookTest --streaming-only
