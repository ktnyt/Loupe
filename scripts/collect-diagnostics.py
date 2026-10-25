#!/usr/bin/env python3
"""Collect only Loupe's diagnostic files; never pull photos or app databases."""
import argparse
import json
import pathlib
import subprocess
import sys

p = argparse.ArgumentParser()
p.add_argument('--adb', default='/opt/homebrew/share/android-commandlinetools/platform-tools/adb')
p.add_argument('--serial')
p.add_argument('--output', type=pathlib.Path, required=True)
a = p.parse_args()
cmd = [a.adb] + (['-s', a.serial] if a.serial else [])
a.output.mkdir(parents=True, exist_ok=True)
for name in ('previous.jsonl', 'events.jsonl'):
    r = subprocess.run(cmd + ['exec-out', 'run-as', 'jp.nano.rawgallery', 'cat', 'files/diagnostics/' + name], capture_output=True)
    if r.returncode or not r.stdout.lstrip().startswith(b'{'):
        if name == 'previous.jsonl' and b'No such file' in r.stderr + r.stdout:
            continue
        sys.exit(r.stderr.decode(errors='replace') or r.stdout.decode(errors='replace'))
    # A live final line can be partial; retain bytes and let the analyzer report it.
    (a.output / name).write_bytes(r.stdout)
    print(a.output / name)
r = subprocess.run(cmd + ['logcat', '-d', '-s', 'LoupeDiagnostic:E', '*:S'], capture_output=True)
(a.output / 'writer-status.txt').write_bytes(r.stdout + r.stderr)
