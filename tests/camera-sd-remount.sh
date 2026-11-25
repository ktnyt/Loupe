#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
javac -d "$work" app/src/main/java/jp/nano/rawgallery/camera/SdCardRemountPlan.java \
  app/src/main/java/jp/nano/rawgallery/camera/SdRemountUsbDisks.java \
  app/src/main/java/jp/nano/rawgallery/camera/SdRemountUsbPort.java \
  app/src/main/java/jp/nano/rawgallery/camera/SdRemountUsbRestore.java \
  app/src/main/java/jp/nano/rawgallery/camera/UsbReaderReconnectPlan.java \
  app/src/main/java/jp/nano/rawgallery/camera/SdRemountMountNotice.java \
  app/src/main/java/jp/nano/rawgallery/camera/DirectUsbLeasePlan.java \
  tests/DirectUsbLeasePlanTest.java tests/SdCardRemountPlanTest.java tests/SdRemountReservationTest.java tests/UsbReaderReconnectPlanTest.java tests/SdRemountUsbRestoreTest.java
java -cp "$work" jp.nano.rawgallery.camera.SdCardRemountPlanTest
java -cp "$work" jp.nano.rawgallery.camera.SdRemountReservationTest
java -cp "$work" jp.nano.rawgallery.camera.UsbReaderReconnectPlanTest
java -cp "$work" jp.nano.rawgallery.camera.SdRemountUsbRestoreTest
java -cp "$work" jp.nano.rawgallery.camera.DirectUsbLeasePlanTest
