"""Run a Gradle build with bounded retries for public Maven repository failures."""
from itertools import takewhile
import re
import subprocess
import sys
import time

RETRY_DELAYS = (5, 15)
RESOLUTION = re.compile(r"Could not resolve all (?:artifacts|dependencies|files) for configuration ")
REPOSITORY = re.compile(
    r"https://(?:repo\.maven\.apache\.org/maven2/|dl\.google\.com/dl/android/maven2/|plugins\.gradle\.org/m2/)"
)
RESOURCE_REQUEST = re.compile(r"Could not (?:GET|HEAD|get resource)\b")
MISSING_MODULE = re.compile(r"Could not find [\w.-]+:[\w.-]+:[\w.+-]+\.\s*$")
TEMPORARY_TRANSPORT = re.compile(
    r"status code (?:408|429|5\d\d)\b|timed out|Connection reset|Connection refused|"
    r"Temporary failure in name resolution|Name or service not known|UnknownHostException|failed to respond",
    re.IGNORECASE,
)


def indentation(line):
    return len(line) - len(line.lstrip())


def retryable_resolution_failure(lines):
    if not any(RESOLUTION.search(line) for line in lines):
        return False
    for index, line in enumerate(lines):
        missing = MISSING_MODULE.search(line)
        request = RESOURCE_REQUEST.search(line) and REPOSITORY.search(line)
        if not (missing or request):
            continue
        indent = indentation(line)
        children = takewhile(lambda child: indentation(child) > indent, lines[index + 1:])
        if missing and any(REPOSITORY.search(child) for child in children):
            return True
        if request and (
            TEMPORARY_TRANSPORT.search(line) or any(TEMPORARY_TRANSPORT.search(child) for child in children)
        ):
            return True
    return False


def run(command, *, cwd=None, env=None):
    for attempt in range(len(RETRY_DELAYS) + 1):
        args = [*command, '--refresh-dependencies'] if attempt else command
        failures = []
        diagnostic = None
        with subprocess.Popen(
            args, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            encoding='utf-8', errors='replace',
        ) as gradle:
            for line in gradle.stdout:
                print(line, end='', flush=True)
                if line.startswith('* What went wrong:'):
                    diagnostic = []
                    failures.append(diagnostic)
                elif line.startswith('* '):
                    diagnostic = None
                elif diagnostic is not None:
                    diagnostic.append(line)
            status = gradle.wait()
        if status <= 0 or attempt == len(RETRY_DELAYS):
            return status
        if not failures or not all(retryable_resolution_failure(section) for section in failures):
            return status
        delay = RETRY_DELAYS[attempt]
        print(f'Maven dependency resolution failed. Retrying Gradle in {delay}s '
              f'with refreshed dependencies ({attempt + 2}/{len(RETRY_DELAYS) + 1}).', flush=True)
        time.sleep(delay)


if __name__ == '__main__':
    raise SystemExit(run(['./gradlew', *sys.argv[1:], '--console=plain']))
