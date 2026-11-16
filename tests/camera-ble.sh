#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
javac -d "$work" "$root/app/src/main/java/jp/nano/rawgallery/camera/CameraBleProtocol.java" "$root/tests/CameraBleProtocolTest.java"
java -cp "$work" CameraBleProtocolTest
javac -d "$work" "$root/app/src/main/java/jp/nano/rawgallery/camera/CameraQrCredentials.java" "$root/tests/CameraQrCredentialsTest.java"
java -cp "$work" CameraQrCredentialsTest
javac -d "$work" "$root/app/src/main/java/jp/nano/rawgallery/camera/CameraBleRecovery.java" "$root/tests/CameraBleRecoveryTest.java"
java -cp "$work" jp.nano.rawgallery.camera.CameraBleRecoveryTest
