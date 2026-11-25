#!/usr/bin/env python3
"""Verify native notices and reject source archives in Android APKs."""
import argparse
from pathlib import Path
import sys
from zipfile import BadZipFile, ZipFile


THIRD_PARTY = Path(__file__).resolve().parents[1] / 'third-party'
NOTICES = {
    'libraw': ('COPYRIGHT', 'LICENSE.CDDL', 'NOTICE.txt'),
    'libjpeg-turbo': ('LICENSE.md', 'README.ijg', 'NOTICE-libjpeg-turbo.txt'),
}
NATIVE_LIBRARY = 'lib/arm64-v8a/libloupe_raw.so'
TARBALL_EXTENSIONS = ('.tar', '.tar.gz', '.tgz', '.tar.bz2', '.tar.xz', '.tar.zst')


def check_apk(path):
    with ZipFile(path) as apk:
        entries = apk.namelist()
        tarballs = sorted(name for name in entries if name.startswith('assets/')
                          and name.casefold().endswith(TARBALL_EXTENSIONS))
        if tarballs:
            raise ValueError('unexpected assets tarballs: ' + ', '.join(tarballs))
        if entries.count(NATIVE_LIBRARY) != 1 or apk.getinfo(NATIVE_LIBRARY).file_size == 0:
            raise ValueError('missing, empty or duplicate ' + NATIVE_LIBRARY)
        for library, notices in NOTICES.items():
            for notice in notices:
                entry = 'assets/' + notice
                source = THIRD_PARTY / library / notice
                if entries.count(entry) != 1 or apk.read(entry) != source.read_bytes():
                    raise ValueError('missing, duplicate or changed ' + entry)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path, nargs='+')
    args = parser.parse_args()
    for path in args.apk:
        try:
            check_apk(path)
        except (BadZipFile, OSError, ValueError) as error:
            print(f'{path}: {error}', file=sys.stderr)
            return 1
        print(f'{path}: native licenses and notices verified, no assets tarballs')
    return 0


if __name__ == '__main__':
    sys.exit(main())
