#!/usr/bin/env python3
"""Run direct USB and exFAT checks against the classes from the Android build."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parent.parent
camera = 'jp/nano/rawgallery/camera'
kotlin = root / 'app/build/tmp/kotlin-classes/debug'
java = root / 'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk or not (kotlin / camera / 'BlockDevice.class').is_file() or not java.is_dir():
    raise SystemExit('Set ANDROID_HOME and build :app:assembleDebug before running direct USB checks')
android = Path(sdk) / 'platforms/android-35/android.jar'
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
stdlib = cache / 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib'
jars = list(stdlib.glob('*/*/kotlin-stdlib-*.jar'))
if not android.is_file() or not jars:
    raise SystemExit('Android platform and Kotlin standard library are required')
classpath = os.pathsep.join(map(str, (java, kotlin, android, *jars)))
checks = ('DirectExFatSourceCheck', 'DirectExFatReservationCheck', 'DirectUsbTransportCheck',
          'DirectPhotoTransferCheck', 'DirectExifComparisonCheck')
with tempfile.TemporaryDirectory(prefix='loupe-direct-exfat-check-') as work:
    subprocess.run(['javac', '-cp', classpath, '-d', work,
                    *(str(root / 'tests' / f'{name}.java') for name in checks)], check=True)
    for name in checks:
        subprocess.run(['java', '-cp', work + os.pathsep + classpath,
                        f'jp.nano.rawgallery.camera.{name}'], check=True)
