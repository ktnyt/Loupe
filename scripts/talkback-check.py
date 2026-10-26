#!/usr/bin/env python3
"""Run an authorized synthetic test with TalkBack; restore and verify settings."""
import argparse
import shlex
import subprocess
import time

KEYS = ('enabled_accessibility_services', 'accessibility_enabled')
SERVICE = 'com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService'


def restore(shell, before):
    errors = []
    for key, value in before.items():
        try:
            shell('settings', 'delete' if value == 'null' else 'put', 'secure', key,
                  *([] if value == 'null' else [value]))
        except Exception as error:
            errors.append(f'{key}: {error}')
    for key, expected in before.items():
        try:
            actual = shell('settings', 'get', 'secure', key)
            if actual != expected:
                errors.append(f'{key}: restoration mismatch ({actual!r} != {expected!r})')
        except Exception as error:
            errors.append(f'{key}: verification failed: {error}')
    if errors:
        raise RuntimeError('Restore failed; inspect device settings: ' + '; '.join(errors))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default='/opt/homebrew/share/android-commandlinetools/platform-tools/adb')
    parser.add_argument('--mode', choices=('ranking-result-talkback',), default='ranking-result-talkback')
    parser.add_argument('--width', default='400')
    parser.add_argument('--font', default='1.0')
    args = parser.parse_args()

    def shell(*parts):
        # adb joins remote arguments: quote once for the device shell, including empty strings.
        return subprocess.run([args.adb, '-s', args.serial, 'shell', shlex.join(parts)],
                              check=True, capture_output=True, text=True, timeout=180).stdout.strip()

    policy = shell('dumpsys', 'window', 'policy')
    if 'showing=false' not in policy or 'showing=true' in policy:
        raise RuntimeError('Device lock state does not permit testing; settings unchanged')
    before = {key: shell('settings', 'get', 'secure', key) for key in KEYS}
    if before[KEYS[0]] not in ('', 'null') or before[KEYS[1]] != '0':
        raise RuntimeError('Accessibility is already configured; settings unchanged')
    try:
        shell('settings', 'put', 'secure', KEYS[0], SERVICE)
        shell('settings', 'put', 'secure', KEYS[1], '1')
        time.sleep(2)
        output = shell('am', 'instrument', '-w', '-e', 'mode', args.mode,
                       '-e', 'width', args.width, '-e', 'font', args.font,
                       'jp.nano.rawgallery.test/jp.nano.rawgallery.ArchiveInstrumentation')
        print(output, flush=True)
        if not output.startswith('PASS '):
            raise RuntimeError('Instrumentation did not report PASS')
    finally:
        restore(shell, before)
        print('Restored and verified: accessibility services empty, accessibility disabled.', flush=True)


if __name__ == '__main__':
    main()
