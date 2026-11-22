#!/bin/sh
set -eu
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
android_jar="$ANDROID_HOME/platforms/android-35/android.jar"
# Resolve real Java/Kotlin and ML types through the application compiler. The tests
# themselves stay on the host and exercise only ranking and file preparation.
(cd "$project_dir" && python3 scripts/ci_gradle.py :app:compileDebugJavaWithJavac --quiet)
app_classes="$project_dir/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
kotlin_classes="$project_dir/app/build/tmp/kotlin-classes/debug"
classpath="$app_classes:$kotlin_classes:$android_jar"
javac -cp "$classpath" -d "$test_dir" \
  "$project_dir/tests/RankingStoreTest.java" "$project_dir/tests/SharePreparationTest.java" \
  "$project_dir/tests/ShareCapacityTest.java"
java -cp "$test_dir:$classpath" jp.nano.rawgallery.RankingStoreTest
java -cp "$test_dir:$classpath" jp.nano.rawgallery.SharePreparationTest
java -cp "$test_dir:$classpath" jp.nano.rawgallery.ShareCapacityTest
