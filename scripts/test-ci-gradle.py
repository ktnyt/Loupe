#!/usr/bin/env python3
"""Exercise the retry command against synthetic Gradle diagnostics."""
from contextlib import redirect_stdout
import io
from pathlib import Path
import sys
import tempfile
import textwrap
import unittest
from unittest.mock import patch

import ci_gradle

MAVEN_FAILURE = """\
* What went wrong:
A problem occurred configuring root project 'RawGallery'.
> Could not resolve all artifacts for configuration 'classpath'.
   > Could not find org.jetbrains.kotlin:kotlin-gradle-plugins-bom:2.1.21.
     Searched in the following locations:
       - https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugins-bom/2.1.21/kotlin-gradle-plugins-bom-2.1.21.pom

* Try:
"""


class GradleRetryTests(unittest.TestCase):
    def run_build(self, diagnostic, *, recover=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = root / 'fake_gradle.py'
            script.write_text(textwrap.dedent(f"""\
                from pathlib import Path
                import sys

                refresh = '--refresh-dependencies' in sys.argv
                with Path('attempts').open('a') as log:
                    log.write(str(refresh) + '\\n')
                if refresh and {recover!r}:
                    print('BUILD SUCCESSFUL')
                    sys.exit(0)
                print({diagnostic!r})
                sys.exit(7)
                """))
            output = io.StringIO()
            with patch.object(ci_gradle.time, 'sleep') as sleep, redirect_stdout(output):
                code = ci_gradle.run([sys.executable, str(script)], cwd=root)
            attempts = (root / 'attempts').read_text().splitlines()
        return code, attempts, output.getvalue(), [call.args[0] for call in sleep.call_args_list]

    def test_missing_public_dependency_recovers(self):
        code, attempts, output, delays = self.run_build(MAVEN_FAILURE, recover=True)
        self.assertEqual((code, attempts, delays), (0, ['False', 'True'], [5]))
        self.assertIn('Could not find org.jetbrains.kotlin', output)
        self.assertIn('BUILD SUCCESSFUL', output)

    def test_persistent_resolution_error_exits_after_two_retries(self):
        code, attempts, _, delays = self.run_build(MAVEN_FAILURE)
        self.assertEqual((code, attempts, delays), (7, ['False', 'True', 'True'], [5, 15]))

    def test_public_repository_http_error_is_retried(self):
        diagnostic = """\
* What went wrong:
> Could not resolve all files for configuration ':app:debugRuntimeClasspath'.
   > Could not GET 'https://dl.google.com/dl/android/maven2/module.pom'. Received status code 503.
"""
        code, attempts, _, _ = self.run_build(diagnostic, recover=True)
        self.assertEqual((code, attempts), (0, ['False', 'True']))

    def test_public_repository_dns_or_no_response_is_retried(self):
        for message in ('Name or service not known', 'failed to respond'):
            with self.subTest(message=message):
                diagnostic = f"""\
* What went wrong:
> Could not resolve all artifacts for configuration 'classpath'.
   > Could not GET 'https://repo.maven.apache.org/maven2/module.pom'.
      > repo.maven.apache.org: {message}
"""
                code, attempts, _, _ = self.run_build(diagnostic, recover=True)
                self.assertEqual((code, attempts), (0, ['False', 'True']))

    def test_other_build_errors_are_not_retried(self):
        diagnostics = (
            "* What went wrong:\nExecution failed for task ':app:lintDebug'.\nLint found errors.",
            MAVEN_FAILURE.replace('https://repo.maven.apache.org/maven2/', 'https://custom.invalid/'),
            MAVEN_FAILURE + "\n* What went wrong:\nLint found errors.\n",
            """\
* What went wrong:
> Could not resolve all artifacts for configuration 'classpath'.
    > Could not GET 'https://repo.maven.apache.org/maven2/pkg.pom'. Received status code 401.
    > Could not GET 'https://custom.invalid/pkg.pom'.
      > Read timed out.
""",
            MAVEN_FAILURE.replace('Could not find org.jetbrains.kotlin:kotlin-gradle-plugins-bom:2.1.21.',
                                  'Received status code 401.'),
        )
        for diagnostic in diagnostics:
            with self.subTest(diagnostic=diagnostic):
                code, attempts, _, delays = self.run_build(diagnostic, recover=True)
                self.assertEqual((code, attempts, delays), (7, ['False'], []))


if __name__ == '__main__':
    unittest.main()
