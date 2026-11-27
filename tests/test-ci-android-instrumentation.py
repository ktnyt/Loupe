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
VIEWER = "INSTRUMENTATION_RESULT: stream=PASS synthetic viewer\n"
LOGCAT_FIXTURES = {
    "nano-background": ("LoupeNanoFixture:E", "E LoupeNanoFixture: synthetic geometry\n"),
    "viewer-screen": ("LoupeViewerTap:I", "I LoupeViewerTap: result fit=false\n"),
}
LOGCAT_ERROR = "synthetic logcat failure\n"
IDENTITY_HEADER = "INSTRUMENTATION_RESULT: stream=java.io.IOException\n"
IDENTITY_FRAME = (
    "  at jp.nano.rawgallery.camera.CameraPhotoImporter."
    "savedPhotoChanged(CameraPhotoImporter.java:179)\n"
)
IDENTITY_REPORT = (
    IDENTITY_HEADER + IDENTITY_FRAME
    + "Suppressed: jp.nano.rawgallery.CameraImportLedgerChecks$FixtureDiagnostic: phase=CROSS_ROUTE\n"
    + "Archive device checks failed: IOException\n\n"
)
FAIL_CODE = "INSTRUMENTATION_CODE: 0\n"
IDENTITY_TRACE = "branch=reopen-null\n"

# Only the result and trace functions are evaluated. The surrounding script can create an AVD.
FUNCTIONS = re.compile(
    r"^(?:run_instrumentation|report_identity_mismatch)\(\) \{\n.*?^\}$",
    re.MULTILINE | re.DOTALL,
)
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
if [[ -n $EXPECTED_LOGCAT_TAG && $# == 7 &&
      "$*" == "-s emulator-5998 logcat -d -s $EXPECTED_LOGCAT_TAG *:S" ]]; then
  if (( LOGCAT_STATUS == 0 )); then
    printf '%s' "$LOGCAT_LINE"
  else
    printf '%s' "$LOGCAT_ERROR" >&2
  fi
  exit "$LOGCAT_STATUS"
fi
if [[ "$*" == '-s emulator-5998 logcat -d -v raw -s LedgerIdentityProbe:E *:S' ]]; then
  printf '%s' "$LOGCAT_LINE"
  exit "$LOGCAT_STATUS"
fi
printf 'Unexpected fake adb arguments\n' >&2
exit 99
"""


class InstrumentationResultTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        functions = FUNCTIONS.findall(RUNNER.read_text(encoding="utf-8"))
        if len(functions) != 2:
            raise AssertionError("Could not extract the result and trace functions from the CI runner")
        cls.functions = "\n".join(functions)

    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="loupe-instrumentation-test-")
        self.addCleanup(directory.cleanup)
        self.work = Path(directory.name)
        adb = self.work / "adb"
        adb.write_text(FAKE_ADB, encoding="utf-8")
        adb.chmod(0o700)

    def check_result(
        self, mode, stdout, *, stderr="", adb_status=0, expected_status=0, logcat_status=None,
        diagnostic=False, identity_trace=IDENTITY_TRACE,
    ):
        calls = self.work / "calls.txt"
        calls.write_text("", encoding="utf-8")
        (self.work / "stdout.txt").write_text(stdout, encoding="utf-8")
        (self.work / "stderr.txt").write_text(stderr, encoding="utf-8")
        logcat_tag, logcat_line = LOGCAT_FIXTURES.get(mode, ("", ""))
        setup = INVOKE
        if diagnostic:
            setup += "segment=ledger-diagnostic\n"
            logcat_tag, logcat_line = "LedgerIdentityProbe:E", identity_trace
        result = subprocess.run(
            [
                "bash",
                "-c",
                setup + self.functions + '\nrun_instrumentation "$1" -e mode "$1" "$2"',
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
                "EXPECTED_LOGCAT_TAG": logcat_tag,
                "LOGCAT_LINE": logcat_line,
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
            if diagnostic:
                expected_calls += [
                    "9", "-s", "emulator-5998", "logcat", "-d", "-v", "raw", "-s", logcat_tag, "*:S",
                ]
            else:
                expected_calls += [
                    "7", "-s", "emulator-5998", "logcat", "-d", "-s", logcat_tag, "*:S",
                ]
        self.assertEqual(calls.read_text(encoding="utf-8").splitlines(), expected_calls)
        # Bash command substitution removes trailing newlines, and the runner writes one back.
        output = f"\nRunning Android instrumentation: {mode}\n" + stdout.rstrip("\n") + "\n"
        if diagnostic and expected_status == 3:
            output += "DIAGNOSTIC_ONLY_IDENTITY_MISMATCH: " + logcat_line
        elif not diagnostic and logcat_status == 0:
            output += logcat_line
        self.assertEqual(result.stdout, output)
        if adb_status:
            stderr += f"Instrumentation adb status: {adb_status}\n"
        if logcat_status and not diagnostic:
            stderr += LOGCAT_ERROR
        if diagnostic and expected_status == 1:
            stderr += f"DIAGNOSTIC_ONLY_INCONCLUSIVE: {mode} did not report PASS with a confirmed identity trace.\n"
        elif expected_status == 1 and not adb_status:
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

    def test_viewer_collects_only_after_its_own_failure(self):
        self.check_result("viewer-screen", VIEWER + PASS_CODE)
        for logcat_status in (0, 9):
            with self.subTest(logcat_status=logcat_status):
                self.check_result(
                    "viewer-screen", VIEWER, expected_status=1, logcat_status=logcat_status
                )
        self.check_result("gallery-screen", VIEWER, expected_status=1)

    def test_viewer_logcat_does_not_replace_adb_failure(self):
        for logcat_status in (0, 9):
            with self.subTest(logcat_status=logcat_status):
                self.check_result(
                    "viewer-screen",
                    VIEWER + PASS_CODE,
                    stderr="synthetic transport stderr\n",
                    adb_status=7,
                    expected_status=7,
                    logcat_status=logcat_status,
                )


    def test_diagnostic_requires_the_complete_ledger_failure_before_reading_trace(self):
        complete = IDENTITY_REPORT + FAIL_CODE
        self.check_result(
            "camera-ledger", complete + " \t\n", diagnostic=True,
            logcat_status=0, expected_status=3,
        )
        for name, stdout in (
            ("contradictory code", complete + PASS_CODE),
            ("duplicate code", complete + FAIL_CODE),
            ("duplicate stream", IDENTITY_HEADER + complete),
            ("aborted", "INSTRUMENTATION_ABORTED: synthetic abort\n" + complete),
            ("failed", "INSTRUMENTATION_FAILED: synthetic failure\n" + complete),
            ("status error", "INSTRUMENTATION_STATUS: Error=synthetic error\n" + complete),
            ("status code", "INSTRUMENTATION_STATUS_CODE: -2\n" + complete),
            ("short message", "INSTRUMENTATION_RESULT: shortMsg=synthetic error\n" + complete),
            ("long message", "INSTRUMENTATION_RESULT: longMsg=synthetic error\n" + complete),
            ("missing terminal code", IDENTITY_REPORT),
            ("missing summary", IDENTITY_HEADER + IDENTITY_FRAME + FAIL_CODE),
            ("frame after suppression", IDENTITY_HEADER + "Suppressed: java.io.IOException\n"
             + IDENTITY_FRAME + "Archive device checks failed: IOException\n" + FAIL_CODE),
            ("non-primary saved-photo frame", complete.replace(
                IDENTITY_FRAME, "  at unrelated.Class.run(Class.java:1)\n" + IDENTITY_FRAME,
            )),
            ("trailing record", complete + "synthetic unexpected data\n"),
        ):
            with self.subTest(name=name):
                self.check_result("camera-ledger", stdout, diagnostic=True, expected_status=1)

        self.check_result("camera-ledger", complete, diagnostic=True, adb_status=7, expected_status=1)
        self.check_result("confidence-store", complete, diagnostic=True, expected_status=1)

    def test_diagnostic_requires_an_unambiguous_trace(self):
        for trace, status in (("", 0), (IDENTITY_TRACE * 2, 0), (IDENTITY_TRACE, 7)):
            with self.subTest(trace=trace, status=status):
                self.check_result(
                    "camera-ledger", IDENTITY_REPORT + FAIL_CODE, diagnostic=True,
                    logcat_status=status, identity_trace=trace, expected_status=1,
                )


if __name__ == "__main__":
    unittest.main()
