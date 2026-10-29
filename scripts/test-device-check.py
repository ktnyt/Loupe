#!/usr/bin/env python3
import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest
import contextlib
import io

SCRIPT = pathlib.Path(__file__).with_name('device-check.py')
YOUTUBE = 'Window{fixture u0 com.google.android.youtube/Player}'
HELD = 'mCurrentFocus=' + YOUTUBE + '\nmHoldScreenWindow=' + YOUTUBE


def module():
    spec = importlib.util.spec_from_file_location('device_check', SCRIPT)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


class DeviceCheckTests(unittest.TestCase):
    def test_unknown_mode_never_starts_adb(self):
        for script in ('device-check.py', 'talkback-check.py'):
            with self.subTest(script=script), tempfile.TemporaryDirectory() as directory:
                marker = pathlib.Path(directory) / 'called'
                fake = pathlib.Path(directory) / 'adb'
                fake.write_text('#!/usr/bin/env python3\nfrom pathlib import Path\nPath('+repr(str(marker))+').touch()\n')
                fake.chmod(0o700)
                result = subprocess.run([sys.executable, str(SCRIPT.with_name(script)), '--serial', 'synthetic',
                                         '--mode', 'ranking-reslut', '--adb', str(fake)], capture_output=True, text=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(marker.exists())

    def test_instrumentation_exit_zero_does_not_hide_failure(self):
        for output, success in [('Archive device checks failed: AssertionError', False), ('PASS synthetic fixture', True)]:
            with self.subTest(output=output), tempfile.TemporaryDirectory() as directory:
                fake = pathlib.Path(directory) / 'adb'
                fake.write_text('#!/usr/bin/env python3\nimport sys\nprint("showing=false" if "policy" in " ".join(sys.argv) else '+repr(output)+')\n')
                fake.chmod(0o700)
                result = subprocess.run([sys.executable, str(SCRIPT), '--serial', 'synthetic', '--mode',
                                         'ranking-result', '--adb', str(fake), '--no-standby-video', 'host fixture'],
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode == 0, success)
                self.assertIn(output, result.stdout)

    def test_standby_lifecycle(self):
        m = module()
        for failed_test, locked_after in ((False, False), (True, False), (False, True)):
            with self.subTest(failed_test=failed_test, locked_after=locked_after):
                calls = []
                def shell(*parts):
                    calls.append(parts)
                    if parts[0] == 'dumpsys':
                        return 'showing=true' if locked_after and len(calls) > 2 else 'showing=false'
                    return HELD
                def run():
                    with m.standby_video(shell, True):
                        calls.append(('instrument',))
                        if failed_test: raise ValueError('test failed')
                if locked_after:
                    with self.assertRaises(RuntimeError): run()
                    self.assertFalse(any(c[:2] == ('am', 'start') for c in calls))
                elif failed_test:
                    with self.assertRaises(ValueError): run()
                else: run()
                self.assertLess(calls.index(('input', 'keyevent', '127')), calls.index(('input', 'keyevent', '3')))
                self.assertLess(calls.index(('am', 'force-stop', 'com.google.android.youtube')), calls.index(('instrument',)))
                self.assertLess(calls.index(('input', 'keyevent', '3')), calls.index(('instrument',)))
                if not locked_after:
                    self.assertGreater(calls.index(('input', 'keyevent', '126')), calls.index(('instrument',)))

    def test_missing_standby_choice_never_starts_adb(self):
        result = subprocess.run([sys.executable, str(SCRIPT), '--serial', 'synthetic', '--mode', 'ranking-result',
                                 '--adb', '/must-not-run-adb'], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('required', result.stderr)

    def test_initial_lock_never_changes_playback(self):
        m = module(); calls = []
        def shell(*parts): calls.append(parts); return 'showing=true'
        with self.assertRaisesRegex(RuntimeError, 'lock state'):
            with m.standby_video(shell, True): self.fail('must not start test')
        self.assertEqual(calls, [('dumpsys', 'window', 'policy')])

    def test_screen_hold_confirmation_waits_without_repeating_input(self):
        m = module(); replies = iter(['mCurrentFocus=' + YOUTUBE, HELD]); calls = []
        def shell(*parts): calls.append(parts); return next(replies)
        m.wait_for_standby(shell, attempts=2, interval=0)
        self.assertEqual(len(calls), 2)
        self.assertTrue(all(c[:2] == ('sh', '-c') and 'mHoldScreenWindow' in c[2] for c in calls))

    def test_delayed_screen_hold_uses_bounded_confirmation_without_more_input(self):
        m = module(); calls = []
        def shell(*parts):
            calls.append(parts)
            return HELD if len(calls) >= 12 else 'mCurrentFocus=' + YOUTUBE
        m.wait_for_standby(shell, interval=0)
        self.assertEqual(len(calls), 12)
        self.assertTrue(all(c[:2] == ('sh', '-c') for c in calls))

    def test_resume_is_sent_once_after_youtube_is_focused(self):
        m = module(); calls = []; replies = iter(['', 'mCurrentFocus=' + YOUTUBE, HELD])
        def shell(*parts):
            calls.append(parts)
            return next(replies) if parts[0] == 'sh' else 'showing=false' if parts[0] == 'dumpsys' else ''
        m.wait_for_standby(shell, attempts=3, interval=0, play_when_focused=True)
        self.assertGreater(calls.index(('input', 'keyevent', '126')), calls.index(('dumpsys', 'window', 'policy')))
        self.assertEqual(calls.count(('input', 'keyevent', '126')), 1)

    def test_stuck_miniplayer_uses_exact_play_control_once_then_confirms_hold(self):
        m = module(); calls = []; held = False
        xml = '<hierarchy><node package="com.google.android.youtube" resource-id="com.google.android.youtube:id/modern_miniplayer_overlay_action_button" content-desc="動画を再生" clickable="true" enabled="true" bounds="[600,2068][744,2212]"/><node text="private fixture title"/></hierarchy>'
        def shell(*parts):
            nonlocal held
            calls.append(parts)
            if parts[0] == 'dumpsys': return 'showing=false'
            if parts[0] == 'sh': return HELD if held else 'mCurrentFocus=' + YOUTUBE
            if parts[0] == 'cat': return xml
            if parts[:2] == ('input', 'tap'): held = True
            return ''
        with contextlib.redirect_stdout(io.StringIO()) as output, contextlib.redirect_stderr(io.StringIO()) as error:
            m.wait_for_standby(shell, attempts=8, interval=0, play_when_focused=True)
        self.assertEqual(calls.count(('input', 'keyevent', '126')), 1)
        self.assertEqual(calls.count(('input', 'tap', '672', '2140')), 1)
        self.assertEqual(sum(c[0] == 'uiautomator' for c in calls), 1)
        tap = calls.index(('input', 'tap', '672', '2140'))
        self.assertEqual(calls[tap-2], ('dumpsys', 'window', 'policy'))
        self.assertEqual(calls[tap-1][:2], ('sh', '-c'))
        self.assertTrue(any(c[:2] == ('sh', '-c') for c in calls[tap+1:]))
        self.assertNotIn('private fixture title', output.getvalue() + error.getvalue())

    def test_miniplayer_fallback_rejects_wrong_app_lock_and_stale_ui(self):
        for scenario in ('wrong_app', 'locked', 'locked_after_dump', 'focus_changed_after_dump', 'already_held'):
            with self.subTest(scenario=scenario):
                m = module(); calls = []; dumped = False
                def shell(*parts):
                    nonlocal dumped
                    calls.append(parts)
                    if parts[0] == 'dumpsys': return 'showing=true' if scenario == 'locked' or (scenario == 'locked_after_dump' and dumped) else 'showing=false'
                    if parts[0] == 'sh':
                        if scenario == 'already_held': return HELD
                        if scenario == 'wrong_app' or (scenario == 'focus_changed_after_dump' and dumped): return 'mCurrentFocus=Window{fixture u0 another.app/Main}'
                        return 'mCurrentFocus=' + YOUTUBE
                    if parts[0] == 'uiautomator': dumped = True
                    if parts[0] == 'cat': return '<hierarchy><node package="com.google.android.youtube" resource-id="com.google.android.youtube:id/modern_miniplayer_overlay_action_button" content-desc="動画を再生" clickable="true" enabled="true" bounds="[600,2068][744,2212]"/></hierarchy>'
                    return ''
                if scenario == 'already_held': m.wait_for_standby(shell, attempts=8, interval=0, play_when_focused=True)
                else:
                    with self.assertRaises(RuntimeError): m.wait_for_standby(shell, attempts=8, interval=0, play_when_focused=True)
                self.assertFalse(any(c[:2] == ('input', 'tap') for c in calls))
                if scenario in ('wrong_app', 'locked', 'already_held'):
                    self.assertFalse(any(c[0] == 'uiautomator' for c in calls))
                if scenario in ('wrong_app', 'locked'):
                    self.assertFalse(any(c[0] == 'input' for c in calls))

    def test_miniplayer_fallback_does_not_guess_a_control_or_repeat_dump(self):
        node = '<node package="com.google.android.youtube" resource-id="com.google.android.youtube:id/modern_miniplayer_overlay_action_button" content-desc="動画を再生" clickable="true" enabled="true" bounds="[600,2068][744,2212]"/>'
        for xml in ('<hierarchy/>', '<broken', '<hierarchy>' + node.replace('動画を再生', '動画を一時停止') + '</hierarchy>',
                    '<hierarchy>' + node.replace('clickable="true"', 'clickable="false"') + '</hierarchy>',
                    '<hierarchy>' + node.replace('[600,2068][744,2212]', '[744,2212][600,2068]') + '</hierarchy>',
                    '<hierarchy>' + node.replace('package="com.google.android.youtube"', 'package="another.app"') + '</hierarchy>',
                    '<hierarchy>' + node + node + '</hierarchy>'):
            with self.subTest(xml=xml):
                calls = []
                def shell(*parts):
                    calls.append(parts)
                    if parts[0] == 'dumpsys': return 'showing=false'
                    if parts[0] == 'sh': return 'mCurrentFocus=' + YOUTUBE
                    if parts[0] == 'cat': return xml
                    return ''
                with self.assertRaisesRegex(RuntimeError, 'not confirmed'):
                    module().wait_for_standby(shell, attempts=8, interval=0, play_when_focused=True)
                self.assertFalse(any(c[:2] == ('input', 'tap') for c in calls))
                self.assertEqual(sum(c[0] == 'uiautomator' for c in calls), 1)

    def test_restore_failure_does_not_hide_original_test_failure(self):
        m = module()
        def fail_restore(*args, **kwargs): raise RuntimeError('restore failed')
        m.wait_for_standby = fail_restore
        def shell(*parts): return 'showing=false'
        with contextlib.redirect_stderr(io.StringIO()) as report:
            with self.assertRaisesRegex(ValueError, 'original fixture failure'):
                with m.standby_video(shell, True): raise ValueError('original fixture failure')
        self.assertIn('restore failed', report.getvalue())

    def test_stale_hold_or_another_app_never_counts_as_standby(self):
        for windows in ('mCurrentFocus=' + YOUTUBE,
                        'mCurrentFocus=' + YOUTUBE + '\nmLastWakeLockHoldingWindow=' + YOUTUBE,
                        'mCurrentFocus=Window{other u0 another.app/Main}\nmHoldScreenWindow=' + YOUTUBE,
                        'mCurrentFocus=' + YOUTUBE + '\nmHoldScreenWindow=Window{other u0 another.app/Main}'):
            with self.subTest(windows=windows):
                with self.assertRaisesRegex(RuntimeError, 'not confirmed'):
                    module().wait_for_standby(lambda *parts: windows, attempts=2, interval=0)

    def test_focused_youtube_hold_needs_no_network_playback_state(self):
        module().wait_for_standby(lambda *parts: HELD, attempts=1)


if __name__ == '__main__':
    unittest.main()
