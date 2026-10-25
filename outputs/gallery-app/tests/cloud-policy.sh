#!/bin/sh
set -eu
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
android_jar="$ANDROID_HOME/platforms/android-35/android.jar"
javac -cp "$android_jar" -sourcepath "$project_dir/app/src/main/java" -d "$test_dir" "$project_dir/tests/CloudPolicyTest.java"
java -cp "$test_dir:$android_jar" jp.nano.rawgallery.CloudPolicyTest
