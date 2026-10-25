#!/bin/sh
# Input: sample.<extension> files. Originals are symlinked read-only by convention, never edited.
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
input_dir=$(CDPATH= cd -- "$1" && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
mkdir -p "$test_dir/corpus/references" "$test_dir/classes" "$test_dir/extracted"
for original in "$input_dir"/sample.*; do
  name=$(basename "$original")
  extension=${name#sample.}
  ln -s "$original" "$test_dir/corpus/$name"
  # Unsupported metadata formats may return nonzero; the independent JPEG scan still runs.
  exiftool -a -b -preview:all -W! "$test_dir/corpus/references/$extension-%t%-c.%s" "$original" || true
done
javac -d "$test_dir/classes" "$project_dir/app/src/main/java/jp/nano/rawgallery/RawPreview.java" \
  "$project_dir/tests/RawPreviewCorpusTest.java"
java -Djava.awt.headless=true -cp "$test_dir/classes" RawPreviewCorpusTest "$test_dir/corpus" "$test_dir/extracted"
