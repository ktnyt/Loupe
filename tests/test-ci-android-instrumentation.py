#!/usr/bin/env python3
"""Exercise the CI runner's result gate without booting an emulator."""

import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


RUNNER = Path(__file__).resolve().with_name("ci-android-instrumentation.sh")
COMPONENT = "jp.nano.rawgallery.test/jp.nano.rawgallery.ArchiveInstrumentation"
HISTORY = "INSTRUMENTATION_RESULT: stream=PASS CameraSync history migration\n"
CONFIDENCE = "INSTRUMENTATION_RESULT: stream=PASS confidence SQLite: v1/v2 migration\n"
LEDGER = "INSTRUMENTATION_RESULT: stream=PASS camera ledger: cross-route receipts\n"
TEARDOWN = "INSTRUMENTATION_RESULT: stream=PASS camera ledger teardown: exception and error primary\n"
PASS_CODE = "INSTRUMENTATION_CODE: -1\n"

# Only this function is evaluated. The surrounding script can create or connect to an AVD.
FUNCTION = re.compile(r"^run_instrumentation\(\) \{\n.*?^\}$", re.MULTILINE | re.DOTALL)
INVOKE = """
set -euo pipefail
work=$PWD
adb="$work/adb"
serial=emulator-5998
"""
FAKE_ADB = r"""#!/usr/bin/env bash
set -eu
if [[ $# != 11 ||
      "$*" != "-s emulator-5998 shell am instrument -w -r -e mode $EXPECTED_MODE $EXPECTED_COMPONENT" ]]; then
  printf 'Unexpected fake adb arguments\n' >&2
  exit 99
fi
cat stdout.txt
cat stderr.txt >&2
exit "$ADB_STATUS"
"""


class InstrumentationResultTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        match = FUNCTION.search(RUNNER.read_text(encoding="utf-8"))
        if match is None:
            raise AssertionError("Could not extract run_instrumentation from the CI runner")
        cls.function = match.group()

    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="loupe-instrumentation-test-")
        self.addCleanup(directory.cleanup)
        self.work = Path(directory.name)
        adb = self.work / "adb"
        adb.write_text(FAKE_ADB, encoding="utf-8")
        adb.chmod(0o700)

    def check_result(self, mode, stdout, *, stderr="", adb_status=0, expected_status=0):
        (self.work / "stdout.txt").write_text(stdout, encoding="utf-8")
        (self.work / "stderr.txt").write_text(stderr, encoding="utf-8")
        result = subprocess.run(
            [
                "bash",
                "-c",
                INVOKE + self.function + '\nrun_instrumentation "$1" -e mode "$1" "$2"',
                "instrumentation-test",
                mode,
                COMPONENT,
            ],
            cwd=self.work,
            env={
                "PATH": os.environ.get("PATH", os.defpath),
                "EXPECTED_MODE": mode,
                "EXPECTED_COMPONENT": COMPONENT,
                "ADB_STATUS": str(adb_status),
            },
            text=True,
            capture_output=True,
            timeout=10,
        )
        self.assertEqual(result.returncode, expected_status, (result.stdout, result.stderr))
        # Bash command substitution removes trailing newlines, and the runner writes one back.
        self.assertEqual(
            result.stdout,
            f"\nRunning Android instrumentation: {mode}\n" + stdout.rstrip("\n") + "\n",
        )
        suffix = {
            0: "",
            1: f"{mode} did not report PASS.\n",
            7: "Instrumentation adb status: 7\n",
        }[expected_status]
        self.assertEqual(result.stderr, stderr + suffix)

    def test_canonical_mode_results(self):
        for mode, stream in (
            ("camera-history-migration", HISTORY),
            ("confidence-store", CONFIDENCE),
            ("camera-ledger", LEDGER),
            ("camera-ledger-teardown", TEARDOWN),
        ):
            with self.subTest(mode=mode):
                self.check_result(mode, stream + PASS_CODE)

    def test_accepted_output_variants(self):
        for name, stdout, stderr in (
            ("blank tail", LEDGER + PASS_CODE + "\n \t\n\n", ""),
            ("stderr noise", LEDGER + PASS_CODE, "synthetic adb warning\n"),
            ("multiline payload", LEDGER + "second result line\nthird result line\n" + PASS_CODE, ""),
        ):
            with self.subTest(name=name):
                self.check_result("camera-ledger", stdout, stderr=stderr)

    def test_rejected_output_variants(self):
        for name, stdout, stderr in (
            ("no stream", PASS_CODE, ""),
            ("no PASS", "INSTRUMENTATION_RESULT: stream=synthetic failure\n" + PASS_CODE, ""),
            ("no code", LEDGER, ""),
            ("wrong code", LEDGER + "INSTRUMENTATION_CODE: 0\n", ""),
            ("stderr PASS", PASS_CODE, LEDGER + PASS_CODE),
            ("duplicate code", LEDGER + PASS_CODE + PASS_CODE, ""),
            ("duplicate stream", LEDGER + LEDGER + PASS_CODE, ""),
            ("foreign mode", CONFIDENCE + PASS_CODE, ""),
            ("short error", LEDGER + "INSTRUMENTATION_RESULT: shortMsg=synthetic error\n" + PASS_CODE, ""),
            ("long error", LEDGER + "INSTRUMENTATION_RESULT: longMsg=synthetic error\n" + PASS_CODE, ""),
            ("failed", LEDGER + "INSTRUMENTATION_FAILED: synthetic failure\n" + PASS_CODE, ""),
            ("aborted", LEDGER + "INSTRUMENTATION_ABORTED: synthetic abort\n" + PASS_CODE, ""),
            ("code before stream", PASS_CODE + LEDGER, ""),
            ("late failure", LEDGER + PASS_CODE + "INSTRUMENTATION_STATUS: Error=late failure\n", ""),
        ):
            with self.subTest(name=name):
                self.check_result("camera-ledger", stdout, stderr=stderr, expected_status=1)

    def test_teardown_rejects_sibling_ledger_result(self):
        self.check_result("camera-ledger-teardown", LEDGER + PASS_CODE, expected_status=1)

    def test_history_requires_exact_result(self):
        with self.subTest(name="suffix"):
            self.check_result(
                "camera-history-migration",
                HISTORY.rstrip("\n") + " synthetic suffix\n" + PASS_CODE,
                expected_status=1,
            )
        with self.subTest(name="plural"):
            self.check_result(
                "camera-history-migration",
                HISTORY.replace("migration", "migrations") + PASS_CODE,
                expected_status=1,
            )

    def test_preserves_adb_failure_and_output_even_with_pass(self):
        self.check_result(
            "camera-ledger",
            LEDGER + PASS_CODE,
            stderr="synthetic transport stderr\n",
            adb_status=7,
            expected_status=7,
        )


if __name__ == "__main__":
    unittest.main()
