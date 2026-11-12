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


def download_asset(args, asset_id, output):
    # Keep APK bytes binary end to end; the text-oriented gh helper cannot be used here.
    if type(asset_id) is not int or asset_id <= 0:
        raise ValueError('Draft asset REST ID is unavailable')
    with Path(output).open('wb') as target:
        subprocess.run([args.gh, 'api', f'repos/{REPOSITORY}/releases/assets/{asset_id}',
                        '-H', 'Accept: application/octet-stream'],
                       check=True, stdout=target, stderr=subprocess.PIPE)


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
        official_main = json.loads(gh(args, 'api', f'repos/{REPOSITORY}/git/ref/heads/main'))
        if (official_main.get('ref') != 'refs/heads/main'
                or official_main.get('object', {}).get('type') != 'commit'
                or official_main.get('object', {}).get('sha') != bundle['source_commit']):
            raise ValueError('Publication source must equal the official ktnyt/Loupe main commit')
        require_absent(args, f'repos/{REPOSITORY}/git/ref/tags/{tag}')
        # The tag lookup excludes drafts whose pending tag does not exist yet.
        pages = json.loads(gh(args, 'api', f'repos/{REPOSITORY}/releases?per_page=100',
                              '--paginate', '--slurp'))
        if not isinstance(pages, list) or any(not isinstance(page, list) for page in pages):
            raise ValueError('Cannot establish existing draft and published releases')
        if any(release.get('tag_name') == tag for page in pages for release in page):
            raise ValueError('Existing GitHub tag or release will not be overwritten')
        deploy.save(root / 'release.json', dict(tag_name=tag, target_commitish=bundle['source_commit'],
                    name=args.title or tag, body=(root / 'notes.md').read_text(), draft=True, prerelease=False))
        created = gh(args, 'api', f'repos/{REPOSITORY}/releases', '--method', 'POST',
                     '--input', root / 'release.json')
        # Keep the identity of this draft, rather than publishing by a mutable tag lookup.
        try:
            draft = json.loads(created)
            draft_id = draft.get('id')
            if type(draft_id) is not int or draft_id <= 0:
                raise ValueError('Draft release REST ID is unavailable')
            # Bind both uploads to the created release ID, never to a mutable tag lookup.
            for asset, content_type in ((snapshot, 'application/vnd.android.package-archive'),
                                        (root / 'bundle.json', 'application/json')):
                gh(args, 'api',
                   f'https://uploads.github.com/repos/{REPOSITORY}/releases/{draft_id}/assets?name={asset.name}',
                   '--method', 'POST', '--input', asset,
                   '-H', 'Content-Type: ' + content_type, '-H', f'Content-Length: {asset.stat().st_size}')
            download = root / 'download'
            download.mkdir()
            metadata = json.loads(gh(args, 'api', f'repos/{REPOSITORY}/releases/{draft_id}'))
            if (metadata.get('id') != draft_id or metadata.get('tag_name') != tag
                    or metadata.get('target_commitish') != bundle['source_commit']
                    or metadata.get('draft') is not True
                    or sorted(x.get('name', '') for x in metadata.get('assets', [])) != ['bundle.json', 'loupe.apk']):
                raise ValueError('Draft release metadata or asset names differ')
            verified_assets = {asset['name']: asset.get('id') for asset in metadata['assets']}
            # Download only asset IDs belonging to the draft created by this invocation.
            # A different release appearing under the same tag must never supply these bytes.
            for asset in metadata['assets']:
                download_asset(args, asset.get('id'), download / asset['name'])
            if (set(x.name for x in download.iterdir()) != {'loupe.apk', 'bundle.json'}
                    or deploy.sha256(download / 'loupe.apk') != deploy.sha256(snapshot)
                    or deploy.sha256(download / 'bundle.json') != deploy.sha256(root / 'bundle.json')):
                raise ValueError('Downloaded release asset hash differs')
            # Atomic create-ref fails if another actor created this tag during asset verification.
            # Never PATCH an existing tag, even when it happens to point at our source.
            gh(args, 'api', f'repos/{REPOSITORY}/git/refs', '--method', 'POST',
               '--raw-field', f'ref=refs/tags/{tag}', '--raw-field', 'sha=' + bundle['source_commit'])
            tag_ref = json.loads(gh(args, 'api', f'repos/{REPOSITORY}/git/ref/tags/{tag}'))
            if (tag_ref.get('ref') != 'refs/tags/' + tag
                    or tag_ref.get('object', {}).get('type') != 'commit'
                    or tag_ref.get('object', {}).get('sha') != bundle['source_commit']):
                raise ValueError('Created tag does not point at the verified source commit')
            current = json.loads(gh(args, 'api', f'repos/{REPOSITORY}/releases/{draft_id}'))
            if (current.get('id') != draft_id or current.get('tag_name') != tag
                    or current.get('draft') is not True
                    or current.get('target_commitish') != bundle['source_commit']):
                raise ValueError('Draft release identity changed before publication')
            current_assets = current.get('assets', [])
            if (len(current_assets) != len(verified_assets)
                    or {asset.get('name'): asset.get('id') for asset in current_assets} != verified_assets):
                raise ValueError('Draft release assets changed after verified read-back')
            gh(args, 'api', f'repos/{REPOSITORY}/releases/{draft_id}', '--method', 'PATCH',
               '--field', 'draft=false')
        except (ValueError, OSError, subprocess.CalledProcessError):
            print('Draft release and any created tag have been retained; nothing is deleted or overwritten.',
                  file=sys.stderr)
            raise
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
