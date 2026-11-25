#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
. "$project_dir/tests/host-app-classpath.inc"
javac -cp "$classpath" -d "$test_dir" \
  "$project_dir/tests/RankingStoreTest.java" "$project_dir/tests/SharePreparationTest.java" \
  "$project_dir/tests/ShareCapacityTest.java" "$project_dir/tests/BurstGapSettingsTest.java" \
  "$project_dir/tests/DigestCompatibilityTest.java" "$project_dir/tests/GalleryCaptureDatesTest.java"
java -cp "$test_dir:$classpath" jp.nano.rawgallery.RankingStoreTest
java -cp "$test_dir:$classpath" jp.nano.rawgallery.SharePreparationTest
java -cp "$test_dir:$classpath" jp.nano.rawgallery.ShareCapacityTest
java -cp "$test_dir:$classpath" jp.nano.rawgallery.BurstGapSettingsTest
java -cp "$test_dir:$classpath" jp.nano.rawgallery.DigestCompatibilityTest
java -cp "$test_dir:$classpath" jp.nano.rawgallery.GalleryCaptureDatesTest
