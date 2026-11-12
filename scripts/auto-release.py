#!/usr/bin/env python3
"""Publish the current official main, or verify an already published stable release."""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


publisher = module('publish_apk', 'publish-apk.py')
deploy = publisher.deploy
versions = module('release_version', 'release-version.py')
REPOSITORY = 'ktnyt/Loupe'


def official_main(args, source):
    ref = json.loads(publisher.gh(args, 'api', f'repos/{REPOSITORY}/git/ref/heads/main'))
    if (ref.get('ref') != 'refs/heads/main' or ref.get('object', {}).get('type') != 'commit'
            or ref.get('object', {}).get('sha') != source):
        raise ValueError('Source must equal the official ktnyt/Loupe main commit')


def expected_bundle(bundle, source, version):
    if (bundle.get('source_commit') != source or bundle.get('mode') != 'main'
            or bundle.get('certificates') != [deploy.CERTIFICATE]
            or bundle.get('version_code') != version['version_code']
            or bundle.get('version_name') != version['version_name']
            or bundle.get('apk') != 'loupe.apk'):
        raise ValueError('Release bundle differs from the expected main source, certificate or version')


def release_identity(release, source, tag):
    if (type(release.get('id')) is not int or release['id'] <= 0
            or release.get('tag_name') != tag or release.get('target_commitish') != source
            or release.get('draft') is not False or release.get('prerelease') is not False):
        raise ValueError('Existing release must be a published stable release of this exact source')
    assets = release.get('assets', [])
    if (not isinstance(assets, list) or len(assets) != 2
            or sorted(asset.get('name', '') for asset in assets) != ['bundle.json', 'loupe.apk']
            or any(type(asset.get('id')) is not int or asset['id'] <= 0 for asset in assets)
            or len({asset['id'] for asset in assets}) != 2):
        raise ValueError('Existing release assets are malformed')
    return {asset['name']: asset['id'] for asset in assets}


def verify_existing(args, repo, release, source, tag, version):
    asset_ids = release_identity(release, source, tag)
    ref = json.loads(publisher.gh(args, 'api', f'repos/{REPOSITORY}/git/ref/tags/{tag}'))
    if (ref.get('ref') != 'refs/tags/' + tag or ref.get('object', {}).get('type') != 'commit'
            or ref.get('object', {}).get('sha') != source):
        raise ValueError('Existing tag differs from the expected source')
    with tempfile.TemporaryDirectory(prefix='loupe-release-verify-') as temp:
        root = Path(temp)
        for name, asset_id in asset_ids.items():
            publisher.download_asset(args, asset_id, root / name)
        bundle = deploy.load(root / 'bundle.json')
        expected_bundle(bundle, source, version)
        if publisher.verify_candidate(args, repo, bundle, root / 'loupe.apk') != tag:
            raise ValueError('Existing signed APK version differs from the expected tag')
        current = json.loads(publisher.gh(args, 'api', f'repos/{REPOSITORY}/releases/{release["id"]}'))
        if current.get('id') != release['id'] or release_identity(current, source, tag) != asset_ids:
            raise ValueError('Existing release assets changed during verification')
    official_main(args, source)
    print(f'https://github.com/{REPOSITORY}/releases/tag/{tag}')


def automatic_release(args):
    repo = Path(deploy.git(args.repo, 'rev-parse', '--show-toplevel'))
    source = deploy.git(repo, 'rev-parse', 'HEAD')
    deploy.git(repo, 'fetch', 'origin', 'main')
    if deploy.git(repo, 'rev-parse', 'origin/main') != source:
        print('Stale source skipped: HEAD no longer equals fetched origin/main.')
        return
    official_main(args, source)
    version = versions.version(repo, source)
    tag = 'v' + version['version_name']
    pages = json.loads(publisher.gh(args, 'api', f'repos/{REPOSITORY}/releases?per_page=100',
                                   '--paginate', '--slurp'))
    if not isinstance(pages, list) or any(not isinstance(page, list) for page in pages):
        raise ValueError('Cannot establish existing releases')
    matches = [release for page in pages for release in page if release.get('tag_name') == tag]
    if matches:
        if len(matches) != 1:
            raise ValueError('Multiple existing releases have the expected tag')
        verify_existing(args, repo, matches[0], source, tag, version)
        return
    with tempfile.TemporaryDirectory(prefix='loupe-auto-release-') as temp:
        root = Path(temp)
        output = root / 'bundle'
        deploy.prepare(argparse.Namespace(repo=repo, sdk=args.sdk, mode='main', ref=source,
                                          issue=None, output=output))
        expected_bundle(deploy.load(output / 'bundle.json'), source, version)
        notes = root / 'notes.md'
        notes.write_text(f'Loupe {version["version_name"]}\n\nSource: https://github.com/{REPOSITORY}/commit/{source}\n')
        publisher.publish(argparse.Namespace(repo=repo, sdk=args.sdk, gh=args.gh,
                          bundle=output / 'bundle.json', notes_file=notes, title=tag))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', default='.')
    parser.add_argument('--sdk', required=True)
    args = parser.parse_args()
    args.gh = 'gh'
    try:
        automatic_release(args)
    except (ValueError, OSError, subprocess.CalledProcessError, ET.ParseError) as error:
        print('Automatic release stopped: ' + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
