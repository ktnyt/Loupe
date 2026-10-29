#!/usr/bin/env python3
"""Run one synthetic device check; reject lock or non-PASS instrumentation output."""
import argparse
from contextlib import contextmanager
import shlex
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


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
    primary_error = None
    try:
        # Pausing plus Home can leave a paused picture-in-picture window over the test.
        shell('am', 'force-stop', 'com.google.android.youtube')
        shell('input', 'keyevent', '3')
        yield
    except BaseException as error:
        primary_error = error
        raise
    finally:
        try:
            require_unlocked(shell)
            shell('am', 'start', '-n', 'com.google.android.youtube/com.google.android.apps.youtube.app.watchwhile.WatchWhileActivity')
            wait_for_standby(shell, play_when_focused=True)
            require_unlocked(shell)
            print('YouTube window is focused and holds the screen on; recheck lock before the next device operation.', flush=True)
        except Exception as restore_error:
            if primary_error is None:
                raise
            print('Standby restoration also failed: ' + str(restore_error), file=sys.stderr, flush=True)


def youtube_window(windows, key):
    return bool(re.search(r'(?m)^\s*' + key + r'=Window\{[^\n]* com\.google\.android\.youtube/', windows))


def play_current_miniplayer(shell, query):
    # Only the previously identified Play control is eligible. Never print XML or video metadata.
    require_unlocked(shell)
    windows = shell('sh', '-c', query)
    if not youtube_window(windows, 'mCurrentFocus') or youtube_window(windows, 'mHoldScreenWindow'):
        return False
    path = '/data/local/tmp/loupe-standby-ui.xml'
    shell('rm', '-f', path)
    try:
        shell('uiautomator', 'dump', path)
        xml = shell('cat', path)
    finally:
        shell('rm', '-f', path)
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return False
    matches = [node for node in root.iter('node')
               if node.get('package') == 'com.google.android.youtube'
               and node.get('resource-id') == 'com.google.android.youtube:id/modern_miniplayer_overlay_action_button'
               and node.get('content-desc') == '動画を再生'
               and node.get('clickable') == 'true' and node.get('enabled') == 'true']
    if len(matches) != 1:
        return False
    bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', matches[0].get('bounds', ''))
    if bounds is None:
        return False
    left, top, right, bottom = map(int, bounds.groups())
    if right <= left or bottom <= top:
        return False
    # Dumping can take time: recheck both lock and foreground immediately before the single tap.
    require_unlocked(shell)
    windows = shell('sh', '-c', query)
    if not youtube_window(windows, 'mCurrentFocus') or youtube_window(windows, 'mHoldScreenWindow'):
        return False
    shell('input', 'tap', str((left + right) // 2), str((top + bottom) // 2))
    return True


def wait_for_standby(shell, attempts=30, interval=0.5, play_when_focused=False):
    # Read only the current YouTube focus/hold lines; do not expose window contents.
    query = "dumpsys window | awk '/mCurrentFocus=|mHoldScreenWindow=/{if(index($0,\" com.google.android.youtube/\")) print}'"
    play_attempt = None
    fallback_tried = False
    for attempt in range(attempts):
        windows = shell('sh', '-c', query)
        focused = youtube_window(windows, 'mCurrentFocus')
        if play_when_focused and focused and play_attempt is None:
            require_unlocked(shell)
            shell('input', 'keyevent', '126')
            play_attempt = attempt
        if all(youtube_window(windows, key) for key in ('mCurrentFocus', 'mHoldScreenWindow')):
            return
        if (play_when_focused and focused and play_attempt is not None
                and attempt - play_attempt >= 4 and attempt + 1 < attempts and not fallback_tried):
            fallback_tried = True
            play_current_miniplayer(shell, query)
        if attempt + 1 < attempts:
            time.sleep(interval)
    raise RuntimeError('YouTube screen hold was not confirmed; check device before waiting')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--mode', required=True, choices=('photo-edit', 'share-screen', 'viewer-screen', 'gallery-screen', 'ranking-result', 'tag-detail', 'tag-confidence', 'confidence-store', 'sort-naming', 'consistency-layout'))
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
