#!/usr/bin/env python3
"""Derive reproducible, increasing distribution versions from main history."""
import argparse
import json
import subprocess
import sys

BASE = '10d1f68a830d029a05ef42bf6c32fc0cd29e624b'


def git(repo, *args):
    return subprocess.run(['git', '-C', str(repo), *args], check=True,
                          capture_output=True, text=True).stdout.strip()


def version(repo, ref='HEAD', base=BASE):
    if git(repo, 'rev-parse', '--is-shallow-repository') != 'false':
        raise ValueError('Release versions require full git history')
    commit = git(repo, 'rev-parse', '--verify', ref + '^{commit}')
    history = git(repo, 'rev-list', '--first-parent', commit).splitlines()
    if base not in history:
        raise ValueError('Release baseline must be on the first-parent history')
    distance = history.index(base)
    code = 167 + distance
    if code > 2100000000:
        raise ValueError('Android versionCode limit exceeded')
    return dict(version_code=code, version_name='0.31.' + str(3 + distance))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', default='.')
    parser.add_argument('--ref', default='HEAD')
    args = parser.parse_args()
    try:
        print(json.dumps(version(args.repo, args.ref)))
    except (ValueError, subprocess.CalledProcessError) as error:
        print('Version calculation stopped: ' + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
