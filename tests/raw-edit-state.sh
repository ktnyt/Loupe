#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT
javac -d "$BUILD" "$ROOT/app/src/main/java/jp/nano/rawgallery/RawEditState.java" "$ROOT/app/src/main/java/jp/nano/rawgallery/RawEditCurve.java" "$ROOT/app/src/main/java/jp/nano/rawgallery/RawEditTone.java" "$ROOT/app/src/main/java/jp/nano/rawgallery/RawEditHistory.java" "$ROOT/tests/RawEditStateTest.java" "$ROOT/tests/RawEditToneTest.java"
java -cp "$BUILD" RawEditStateTest
java -cp "$BUILD" jp.nano.rawgallery.RawEditToneTest
