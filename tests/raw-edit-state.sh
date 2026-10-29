#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT HUP INT TERM
javac -d "$classes" "$root/app/src/main/java/jp/nano/rawgallery/RawEditState.java" "$root/tests/RawEditStateTest.java"
java -cp "$classes" RawEditStateTest
