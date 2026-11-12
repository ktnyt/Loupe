#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
javac -d "$test_dir" "$project_dir/app/src/main/java/jp/nano/rawgallery/RawFormats.java" "$project_dir/app/src/main/java/jp/nano/rawgallery/VideoFormats.java" "$project_dir/tests/VideoFormatsTest.java"
java -cp "$test_dir" jp.nano.rawgallery.VideoFormatsTest
