#!/usr/bin/env python3
"""Publish a verified main Loupe bundle through a verified GitHub draft release."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('deploy_apk', Path(__file__).with_name('deploy-apk.py'))
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)
REPOSITORY = 'ktnyt/Loupe'
CERTIFICATE = deploy.CERTIFICATE
BUNDLE_FIELDS = {'schema', 'mode', 'source_commit', 'source_tree', 'issue', 'apk',
                 'package', 'version_code', 'version_name', 'certificates', 'features',
                 'apk_sha256', 'provenance'}


def gh(args, *parts):
    return deploy.run([args.gh, *parts])


def require_absent(args, endpoint):
    try:
        gh(args, 'api', endpoint)
    except subprocess.CalledProcessError as error:
        # Authentication, connectivity and permission failures are not absence.
        if 'HTTP 404' in (error.stderr or ''):
            return
        raise
    raise ValueError('Existing GitHub tag or release will not be overwritten')


def verify_candidate(args, repo, bundle, apk):
    actual = deploy.apk_info(apk, args.sdk)
    deploy.verify_bundle(bundle, actual)
    if set(bundle) != BUNDLE_FIELDS:
        raise ValueError('Publication bundle must contain only the verified deployment fields')
    if bundle['provenance'] != actual['provenance']:
        raise ValueError('Publication bundle provenance differs from signed APK')
    if bundle['mode'] != 'main':
        raise ValueError('Only main bundles may be published')
    source = bundle.get('source_commit', '')
    if not re.fullmatch(r'[0-9a-f]{40}', source):
        raise ValueError('Invalid source commit')
    deploy.git(repo, 'fetch', 'origin', 'main')
    if deploy.git(repo, 'rev-parse', 'origin/main') != source:
        raise ValueError('Publication source must equal fetched origin/main')
    if deploy.git(repo, 'rev-parse', source + '^{tree}') != bundle.get('source_tree'):
        raise ValueError('Source tree differs from bundle')
    if actual['certificates'] != [CERTIFICATE]:
        raise ValueError('Publication requires the existing Loupe signing certificate')
    if (not isinstance(actual['version_code'], int) or actual['version_code'] <= 0
            or not re.fullmatch(r'\d+\.\d+\.\d+', actual['version_name'])):
        raise ValueError('Publication requires a stable version name and positive versionCode')
    return 'v' + actual['version_name']


def publish(args):
    repo = Path(deploy.git(args.repo, 'rev-parse', '--show-toplevel'))
    bundle_path = Path(args.bundle).resolve()
    bundle = deploy.load(bundle_path)
    apk = (bundle_path.parent / bundle.get('apk', '')).resolve()
    if bundle.get('apk') != 'loupe.apk' or apk.parent != bundle_path.parent or not apk.is_file():
        raise ValueError('Release bundle must reference its adjacent loupe.apk')
    # Upload and inspect these same private snapshots, never mutable caller paths.
    with tempfile.TemporaryDirectory(prefix='loupe-release-') as temp:
        root = Path(temp)
        snapshot = root / 'loupe.apk'
        shutil.copyfile(apk, snapshot)
        shutil.copyfile(args.notes_file, root / 'notes.md')
        tag = verify_candidate(args, repo, bundle, snapshot)
        deploy.save(root / 'bundle.json', bundle)
        require_absent(args, f'repos/{REPOSITORY}/git/ref/tags/{tag}')
        require_absent(args, f'repos/{REPOSITORY}/releases/tags/{tag}')
        gh(args, 'release', 'create', tag, '--repo', REPOSITORY, '--draft',
           '--target', bundle['source_commit'], '--title', args.title or tag,
           '--notes-file', root / 'notes.md', snapshot, root / 'bundle.json')
        # Failures after draft creation deliberately leave it for inspection.
        download = root / 'download'
        download.mkdir()
        gh(args, 'release', 'download', tag, '--repo', REPOSITORY, '--dir', download)
        metadata = json.loads(gh(args, 'release', 'view', tag, '--repo', REPOSITORY,
                                 '--json', 'tagName,targetCommitish,isDraft,assets'))
        if (metadata.get('tagName') != tag or metadata.get('targetCommitish') != bundle['source_commit']
                or metadata.get('isDraft') is not True
                or sorted(x.get('name', '') for x in metadata.get('assets', [])) != ['bundle.json', 'loupe.apk']):
            raise ValueError('Draft release metadata or asset names differ; draft retained')
        if (set(x.name for x in download.iterdir()) != {'loupe.apk', 'bundle.json'}
                or deploy.sha256(download / 'loupe.apk') != deploy.sha256(snapshot)
                or deploy.sha256(download / 'bundle.json') != deploy.sha256(root / 'bundle.json')):
            raise ValueError('Downloaded release asset hash differs; draft retained')
        gh(args, 'release', 'edit', tag, '--repo', REPOSITORY, '--draft=false')
        print(f'https://github.com/{REPOSITORY}/releases/tag/{tag}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', default='.')
    parser.add_argument('--sdk', default='/opt/homebrew/share/android-commandlinetools')
    parser.add_argument('--gh', default='gh')
    parser.add_argument('--bundle', required=True)
    parser.add_argument('--notes-file', required=True)
    parser.add_argument('--title')
    args = parser.parse_args()
    try:
        publish(args)
    except (ValueError, OSError, subprocess.CalledProcessError, ET.ParseError) as error:
        print('Publication stopped: ' + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
