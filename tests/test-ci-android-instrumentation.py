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
NANO = "INSTRUMENTATION_RESULT: stream=PASS synthetic nano\n"
NANO_LOGCAT_ARGS = ["-s", "emulator-5998", "logcat", "-d", "-s", "LoupeNanoFixture:E", "*:S"]
LOGCAT_LINE = "E LoupeNanoFixture: synthetic geometry\n"
LOGCAT_ERROR = "synthetic logcat failure\n"

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
printf '%s\n' "$#" "$@" >> calls.txt
if [[ $# == 11 &&
      "$*" == "-s emulator-5998 shell am instrument -w -r -e mode $EXPECTED_MODE $EXPECTED_COMPONENT" ]]; then
  cat stdout.txt
  cat stderr.txt >&2
  exit "$ADB_STATUS"
fi
if [[ $EXPECTED_MODE == nano-background && $# == 7 &&
      "$*" == '-s emulator-5998 logcat -d -s LoupeNanoFixture:E *:S' ]]; then
  if (( LOGCAT_STATUS == 0 )); then
    printf '%s' "$LOGCAT_LINE"
  else
    printf '%s' "$LOGCAT_ERROR" >&2
  fi
  exit "$LOGCAT_STATUS"
fi
printf 'Unexpected fake adb arguments\n' >&2
exit 99
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

    def check_result(
        self, mode, stdout, *, stderr="", adb_status=0, expected_status=0, logcat_status=None
    ):
        calls = self.work / "calls.txt"
        calls.write_text("", encoding="utf-8")
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
                "LOGCAT_STATUS": str(0 if logcat_status is None else logcat_status),
                "LOGCAT_LINE": LOGCAT_LINE,
                "LOGCAT_ERROR": LOGCAT_ERROR,
            },
            text=True,
            capture_output=True,
            timeout=10,
        )
        self.assertEqual(result.returncode, expected_status, (result.stdout, result.stderr))
        expected_calls = [
            "11", "-s", "emulator-5998", "shell", "am", "instrument", "-w", "-r",
            "-e", "mode", mode, COMPONENT,
        ]
        if logcat_status is not None:
            expected_calls += [str(len(NANO_LOGCAT_ARGS)), *NANO_LOGCAT_ARGS]
        self.assertEqual(calls.read_text(encoding="utf-8").splitlines(), expected_calls)
        # Bash command substitution removes trailing newlines, and the runner writes one back.
        self.assertEqual(
            result.stdout,
            f"\nRunning Android instrumentation: {mode}\n"
            + stdout.rstrip("\n") + "\n" + (LOGCAT_LINE if logcat_status == 0 else ""),
        )
        if adb_status:
            stderr += f"Instrumentation adb status: {adb_status}\n"
        if logcat_status:
            stderr += LOGCAT_ERROR
        if expected_status == 1 and not adb_status:
            stderr += f"{mode} did not report PASS.\n"
        self.assertEqual(result.stderr, stderr)

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
            (
                "normal progress status",
                "INSTRUMENTATION_STATUS: stream=synthetic progress\n"
                "INSTRUMENTATION_STATUS_CODE: 0\n" + LEDGER + PASS_CODE,
                "",
            ),
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

    def test_status_failure_is_retained_before_pass(self):
        error = "INSTRUMENTATION_STATUS: Error=synthetic failure\n"
        negative_code = "INSTRUMENTATION_STATUS_CODE: -2\n"
        for record in (error, negative_code, error + negative_code):
            with self.subTest(record=record):
                self.check_result("camera-ledger", record + LEDGER + PASS_CODE, expected_status=1)

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


    def test_nano_parser_failure_collects_logcat(self):
        self.check_result("nano-background", NANO + PASS_CODE)
        self.check_result("nano-background", NANO, expected_status=1, logcat_status=0)

    def test_nano_logcat_does_not_replace_adb_failure(self):
        for logcat_status in (0, 9):
            with self.subTest(logcat_status=logcat_status):
                self.check_result(
                    "nano-background",
                    NANO + PASS_CODE,
                    stderr="synthetic transport stderr\n",
                    adb_status=7,
                    expected_status=7,
                    logcat_status=logcat_status,
                )


if __name__ == "__main__":
    unittest.main()
