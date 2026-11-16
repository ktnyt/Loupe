#!/usr/bin/env python3
"""Run the read-only exFAT regression against the Android build's actual Kotlin classes."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parent.parent
classes = root / 'app/build/tmp/kotlin-classes/debug'
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
runtime = cache / 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib'
jars = list(runtime.glob('*/*/kotlin-stdlib-*.jar'))
if not classes.is_dir() or not jars:
    raise SystemExit('Build :app:assembleDebug before running the exFAT check')
# Gradle has already resolved the runtime; newer Kotlin stdlib maintains this public JVM ABI.
def version(path):
    return tuple(int(part) if part.isdigit() else 0 for part in path.parents[1].name.split('.'))
stdlib = max(jars, key=version)
classpath = os.pathsep.join((str(classes), str(stdlib)))
with tempfile.TemporaryDirectory(prefix='loupe-exfat-check-') as work:
    subprocess.run(['javac', '-cp', classpath, '-d', work,
                    str(root / 'tests/CameraExFatCheck.java')], check=True)
    subprocess.run(['java', '-cp', work + os.pathsep + classpath,
                    'jp.nano.rawgallery.CameraExFatCheck'], check=True)
