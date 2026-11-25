#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
java_dir="$project_dir/app/src/main/java/jp/nano/rawgallery"
javac -d "$test_dir" "$java_dir/SyncPolicy.java" "$java_dir/RawPreview.java" \
  "$project_dir/tests/SyncPolicyTest.java"
java -cp "$test_dir" jp.nano.rawgallery.SyncPolicyTest
