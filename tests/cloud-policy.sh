#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
. "$project_dir/tests/host-app-classpath.inc"
javac -cp "$classpath" -d "$test_dir" "$project_dir/tests/CloudPolicyTest.java"
java -cp "$test_dir:$classpath" jp.nano.rawgallery.CloudPolicyTest
