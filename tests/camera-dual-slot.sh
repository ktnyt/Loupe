#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
javac -d "$work" app/src/main/java/jp/nano/rawgallery/camera/CameraCardRouting.java tests/CameraCardRoutingTest.java
java -cp "$work" jp.nano.rawgallery.camera.CameraCardRoutingTest
