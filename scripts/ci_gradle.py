"""Run a Gradle build with bounded retries for public Maven repository failures."""
import re
import subprocess
import sys
import time

RETRY_DELAYS = (5, 15)
RESOLUTION = re.compile(r"Could not resolve all (?:artifacts|dependencies|files) for configuration ")
REPOSITORY = re.compile(
    r"https://(?:repo\.maven\.apache\.org/maven2/|dl\.google\.com/dl/android/maven2/|plugins\.gradle\.org/m2/)"
)
MISSING_MODULE = re.compile(r"Could not find [\w.-]+:[\w.-]+:[\w.+-]+\.\s*$")
TEMPORARY_TRANSPORT = re.compile(
    r"status code (?:408|429|5\d\d)\b|timed out|Connection reset|Connection refused|"
    r"Temporary failure in name resolution|UnknownHostException",
    re.IGNORECASE,
)


def retryable_resolution_failure(lines):
    return (
        any(RESOLUTION.search(line) for line in lines)
        and any(REPOSITORY.search(line) for line in lines)
        and any(MISSING_MODULE.search(line) or TEMPORARY_TRANSPORT.search(line) for line in lines)
    )


def run(command, *, cwd=None, env=None):
    for attempt in range(len(RETRY_DELAYS) + 1):
        args = [*command, '--refresh-dependencies'] if attempt else command
        failure = []
        in_diagnostic = False
        with subprocess.Popen(
            args, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            encoding='utf-8', errors='replace',
        ) as gradle:
            for line in gradle.stdout:
                print(line, end='', flush=True)
                if line.startswith('* What went wrong:'):
                    in_diagnostic = True
                elif line.startswith('* '):
                    in_diagnostic = False
                elif in_diagnostic:
                    failure.append(line)
            status = gradle.wait()
        if status <= 0 or attempt == len(RETRY_DELAYS) or not retryable_resolution_failure(failure):
            return status
        delay = RETRY_DELAYS[attempt]
        print(f'Maven dependency resolution failed. Retrying Gradle in {delay}s '
              f'with refreshed dependencies ({attempt + 2}/{len(RETRY_DELAYS) + 1}).', flush=True)
        time.sleep(delay)


if __name__ == '__main__':
    raise SystemExit(run(['./gradlew', *sys.argv[1:], '--console=plain']))
