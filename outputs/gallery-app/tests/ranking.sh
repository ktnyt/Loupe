#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
javac -d "$test_dir" "$project_dir/app/src/main/java/jp/nano/rawgallery/PhotoRanking.java" "$project_dir/tests/PhotoRankingTest.java"
java -cp "$test_dir" jp.nano.rawgallery.PhotoRankingTest
javac -d "$test_dir" "$project_dir/app/src/main/java/jp/nano/rawgallery/ComparisonLayout.java" "$project_dir/tests/ComparisonLayoutTest.java"
java -cp "$test_dir" jp.nano.rawgallery.ComparisonLayoutTest
javac -d "$test_dir" "$project_dir/app/src/main/java/jp/nano/rawgallery/BestShotSelection.java" "$project_dir/tests/BestShotSelectionTest.java"
java -cp "$test_dir" jp.nano.rawgallery.BestShotSelectionTest
