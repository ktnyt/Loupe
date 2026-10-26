#!/usr/bin/env python3
import pathlib
import importlib.util
import subprocess
import sys
import tempfile
import unittest


class DeviceCheckTests(unittest.TestCase):
    def test_unknown_mode_never_starts_adb(self):
        for script in ('device-check.py', 'talkback-check.py'):
            with self.subTest(script=script), tempfile.TemporaryDirectory() as directory:
                marker = pathlib.Path(directory) / 'called'
                fake = pathlib.Path(directory) / 'adb'
                fake.write_text('#!/usr/bin/env python3\nfrom pathlib import Path\nPath('+repr(str(marker))+').touch()\n')
                fake.chmod(0o700)
                result = subprocess.run([sys.executable, str(pathlib.Path(__file__).with_name(script)),
                                         '--serial', 'synthetic', '--mode', 'ranking-reslut', '--adb', str(fake)],
                                        capture_output=True, text=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(marker.exists())

    def test_instrumentation_exit_zero_does_not_hide_failure(self):
        for output, success in [('Archive device checks failed: AssertionError', False),
                                ('PASS synthetic fixture', True)]:
            with self.subTest(output=output), tempfile.TemporaryDirectory() as directory:
                fake = pathlib.Path(directory) / 'adb'
                fake.write_text('#!/usr/bin/env python3\nimport sys\nprint("showing=false" if "policy" in " ".join(sys.argv) else '+repr(output)+')\n')
                fake.chmod(0o700)
                result = subprocess.run([sys.executable, str(pathlib.Path(__file__).with_name('device-check.py')),
                                         '--serial', 'synthetic', '--mode', 'ranking-result', '--adb', str(fake),
                                         '--no-standby-video', 'host fixture'],
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode == 0, success)
                self.assertIn(output, result.stdout)

    def test_standby_lifecycle(self):
        spec = importlib.util.spec_from_file_location('device_check', pathlib.Path(__file__).with_name('device-check.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        for failed_test, locked_after in ((False, False), (True, False), (False, True)):
            with self.subTest(failed_test=failed_test, locked_after=locked_after):
                calls = []
                def shell(*parts):
                    calls.append(parts)
                    if parts[0] == 'dumpsys':
                        return 'showing=true' if locked_after and len(calls) > 2 else 'showing=false'
                    return 'state=PlaybackState {state=3,'
                def run():
                    with module.standby_video(shell, True):
                        calls.append(('instrument',))
                        if failed_test:
                            raise ValueError('test failed')
                if locked_after:
                    with self.assertRaises(RuntimeError): run()
                    self.assertFalse(any(c[0] == 'am' for c in calls))
                elif failed_test:
                    with self.assertRaises(ValueError): run()
                else:
                    run()
                self.assertLess(calls.index(('input', 'keyevent', '127')), calls.index(('instrument',)))
                if not locked_after:
                    self.assertGreater(calls.index(('input', 'keyevent', '126')), calls.index(('instrument',)))

    def test_standby_rejects_unconfirmed_playback(self):
        spec = importlib.util.spec_from_file_location('device_check', pathlib.Path(__file__).with_name('device-check.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        def shell(*parts):
            return 'showing=false' if parts[0] == 'dumpsys' else 'state=PlaybackState {state=2,'
        with self.assertRaisesRegex(RuntimeError, 'not confirmed'):
            with module.standby_video(shell, True): pass

    def test_missing_standby_choice_never_starts_adb(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = pathlib.Path(directory) / 'called'
            fake = pathlib.Path(directory) / 'adb'
            fake.write_text('#!/usr/bin/env python3\nfrom pathlib import Path\nPath('+repr(str(marker))+').touch()\n')
            fake.chmod(0o700)
            result = subprocess.run([sys.executable, str(pathlib.Path(__file__).with_name('device-check.py')),
                                     '--serial', 'synthetic', '--mode', 'ranking-result', '--adb', str(fake)],
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(marker.exists())

    def test_initial_lock_never_changes_playback(self):
        spec = importlib.util.spec_from_file_location('device_check', pathlib.Path(__file__).with_name('device-check.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        calls = []
        def shell(*parts):
            calls.append(parts)
            return 'showing=true'
        with self.assertRaisesRegex(RuntimeError, 'lock state'):
            with module.standby_video(shell, True): self.fail('must not start test')
        self.assertEqual(calls, [('dumpsys', 'window', 'policy')])


if __name__ == '__main__':
    unittest.main()
