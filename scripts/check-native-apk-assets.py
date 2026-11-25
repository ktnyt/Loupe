#!/usr/bin/env python3
"""Verify native notices and reject source archives in Android assets."""
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


def check_assets(names, read):
    tarballs = sorted(name for name in names if name.casefold().endswith(TARBALL_EXTENSIONS))
    if tarballs:
        raise ValueError('unexpected assets tarballs: ' + ', '.join(tarballs))
    for library, notices in NOTICES.items():
        for notice in notices:
            source = THIRD_PARTY / library / notice
            if names.count(notice) != 1 or read(notice) != source.read_bytes():
                raise ValueError('missing, duplicate or changed assets/' + notice)


def check_apk(path):
    with ZipFile(path) as apk:
        entries = apk.namelist()
        names = [name.removeprefix('assets/') for name in entries if name.startswith('assets/')]
        check_assets(names, lambda name: apk.read('assets/' + name))
        if entries.count(NATIVE_LIBRARY) != 1 or apk.getinfo(NATIVE_LIBRARY).file_size == 0:
            raise ValueError('missing, empty or duplicate ' + NATIVE_LIBRARY)


def check_merged_assets(path):
    names = [entry.relative_to(path).as_posix() for entry in path.rglob('*') if entry.is_file()]
    check_assets(names, lambda name: (path / name).read_bytes())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--merged-assets', type=Path)
    args = parser.parse_args()
    for path, check in ((args.apk, check_apk), (args.merged_assets, check_merged_assets)):
        if path is None:
            continue
        try:
            check(path)
        except (BadZipFile, OSError, ValueError) as error:
            print(f'{path}: {error}', file=sys.stderr)
            return 1
        print(f'{path}: native licenses and notices verified, no assets tarballs')
    return 0


if __name__ == '__main__':
    sys.exit(main())
