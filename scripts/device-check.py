#!/usr/bin/env python3
"""Run one synthetic device check; reject lock or non-PASS instrumentation output."""
import argparse
from contextlib import contextmanager
import shlex
import re
import subprocess
import sys
import time


def require_pass(output):
    if not output.startswith('PASS '):
        raise RuntimeError('Instrumentation did not report PASS')


def require_unlocked(shell):
    policy = shell('dumpsys', 'window', 'policy')
    if 'showing=false' not in policy or 'showing=true' in policy:
        raise RuntimeError('Device lock state does not permit testing or standby playback')


@contextmanager
def standby_video(shell, enabled):
    require_unlocked(shell)
    if not enabled:
        yield
        return
    # Pause before instrumentation; resume even if the test fails. Never unlock.
    shell('input', 'keyevent', '127')
    try:
        # Pausing plus Home can leave a paused picture-in-picture window over the test.
        shell('am', 'force-stop', 'com.google.android.youtube')
        shell('input', 'keyevent', '3')
        yield
    finally:
        require_unlocked(shell)
        shell('am', 'start', '-n', 'com.google.android.youtube/com.google.android.apps.youtube.app.watchwhile.WatchWhileActivity')
        shell('input', 'keyevent', '126')
        wait_for_standby(shell)
        require_unlocked(shell)
        print('YouTube window is focused and holds the screen on; recheck lock before the next device operation.', flush=True)


def wait_for_standby(shell, attempts=30, interval=0.5):
    # Read only the current YouTube focus/hold lines; do not expose window contents.
    query = "dumpsys window | awk '/mCurrentFocus=|mHoldScreenWindow=/{if(index($0,\" com.google.android.youtube/\")) print}'"
    for attempt in range(attempts):
        windows = shell('sh', '-c', query)
        if all(re.search(r'(?m)^\s*' + key + r'=Window\{[^\n]* com\.google\.android\.youtube/', windows)
               for key in ('mCurrentFocus', 'mHoldScreenWindow')):
            return
        if attempt + 1 < attempts:
            time.sleep(interval)
    raise RuntimeError('YouTube screen hold was not confirmed; check device before waiting')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--mode', required=True, choices=('share-screen', 'viewer-screen', 'gallery-screen', 'ranking-result', 'tag-detail', 'tag-confidence', 'confidence-store', 'sort-naming', 'consistency-layout'))
    parser.add_argument('--width', default='400')
    parser.add_argument('--font', default='1.0')
    parser.add_argument('--adb', default='/opt/homebrew/share/android-commandlinetools/platform-tools/adb')
    standby = parser.add_mutually_exclusive_group(required=True)
    standby.add_argument('--standby-youtube', action='store_true')
    standby.add_argument('--no-standby-video', metavar='REASON')
    args = parser.parse_args()
    adb = [args.adb, '-s', args.serial, 'shell']
    def shell(*parts):
        return subprocess.run(adb + [shlex.join(parts)], check=True,
                              capture_output=True, text=True, timeout=180).stdout

    with standby_video(shell, args.standby_youtube):
        output = shell('am', 'instrument', '-w', '-e', 'mode', args.mode,
                       '-e', 'width', args.width, '-e', 'font', args.font,
                       'jp.nano.rawgallery.test/jp.nano.rawgallery.ArchiveInstrumentation')
        print(output, end='', flush=True)
        require_pass(output.strip())



if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
