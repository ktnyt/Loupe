#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
javac --add-modules jdk.httpserver -d "$work" "$root/app/src/main/java/jp/nano/rawgallery/camera/Ep7WifiClient.java" "$root/tests/Ep7WifiClientTest.java"
java --add-modules jdk.httpserver -cp "$work" Ep7WifiClientTest
