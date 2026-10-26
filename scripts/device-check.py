#!/usr/bin/env python3
"""Run one synthetic device check; reject lock or non-PASS instrumentation output."""
import argparse
import shlex
import subprocess
import sys


def require_pass(output):
    if not output.startswith('PASS '):
        raise RuntimeError('Instrumentation did not report PASS')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--mode', required=True, choices=('gallery-screen', 'ranking-result', 'tag-detail', 'sort-naming', 'consistency-layout'))
    parser.add_argument('--width', default='400')
    parser.add_argument('--font', default='1.0')
    parser.add_argument('--adb', default='/opt/homebrew/share/android-commandlinetools/platform-tools/adb')
    args = parser.parse_args()
    adb = [args.adb, '-s', args.serial, 'shell']
    policy = subprocess.run(adb + ['dumpsys', 'window', 'policy'], check=True,
                            capture_output=True, text=True, timeout=20).stdout
    if 'showing=false' not in policy or 'showing=true' in policy:
        raise RuntimeError('Device lock state does not permit testing')
    result = subprocess.run(adb + [shlex.join(['am', 'instrument', '-w', '-e', 'mode', args.mode,
                                  '-e', 'width', args.width, '-e', 'font', args.font,
                                  'jp.nano.rawgallery.test/jp.nano.rawgallery.ArchiveInstrumentation'])],
                            check=True, capture_output=True, text=True, timeout=180)
    print(result.stdout, end='', flush=True)
    require_pass(result.stdout.strip())


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
