#!/usr/bin/env python3
import pathlib
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
                fake.write_text('#!/usr/bin/env python3\nimport sys\nprint("showing=false" if "policy" in sys.argv else '+repr(output)+')\n')
                fake.chmod(0o700)
                result = subprocess.run([sys.executable, str(pathlib.Path(__file__).with_name('device-check.py')),
                                         '--serial', 'synthetic', '--mode', 'ranking-result', '--adb', str(fake)],
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode == 0, success)
                self.assertIn(output, result.stdout)


if __name__ == '__main__':
    unittest.main()
