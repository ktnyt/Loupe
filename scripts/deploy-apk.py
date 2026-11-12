#!/usr/bin/env python3
"""Build recorded Loupe APKs and refuse updates that lose required features or main provenance."""
import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
import xml.etree.ElementTree as ET
import zipfile

PACKAGE = 'jp.nano.rawgallery'
ANDROID = '{http://schemas.android.com/apk/res/android}'
REQUIRED = {'library', 'video', 'nano-banana', 'camera-import'}
SCHEMA = 1
PROVENANCE = "assets/loupe-deployment.json"


def run(parts, cwd=None, env=None):
    result = subprocess.run([str(x) for x in parts], cwd=cwd, env=env,
                            check=True, capture_output=True, text=True)
    return result.stdout.strip()


def git(repo, *args):
    return run(['git', '-C', repo, *args])


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def save(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + '.tmp')
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
    temp.replace(path)


def load(path):
    return json.loads(Path(path).read_text())


def qualified(name):
    return PACKAGE + name if name.startswith('.') else PACKAGE + '.' + name if '.' not in name else name


def manifest_info(xml):
    if '<!DOCTYPE' in xml or '<!ENTITY' in xml:
        raise ValueError('Manifest declarations are not supported')
    root = ET.fromstring(xml)
    app = root.find('application')
    if app is None or app.get(ANDROID + 'enabled', 'true') == 'false':
        raise ValueError('Application is absent or disabled')
    entries = {qualified(x.get(ANDROID + 'name', '')): x for x in app
               if x.tag in {'activity', 'service'} and x.get(ANDROID + 'enabled', 'true') != 'false'}
    activities = {name: node for name, node in entries.items() if node.tag == 'activity'}
    services = {name: node for name, node in entries.items() if node.tag == 'service'}
    permissions = {x.get(ANDROID + 'name') for x in root.findall('uses-permission')}
    features = set()
    if PACKAGE + '.MainActivity' in activities:
        features.add('library')
    if PACKAGE + '.VideoActivity' in activities and 'android.permission.READ_MEDIA_VIDEO' in permissions:
        features.add('video')
    if {PACKAGE + '.NanoBananaActivity', PACKAGE + '.NanoBananaSettingsActivity'} <= activities.keys():
        features.add('nano-banana')
    attach = activities.get(PACKAGE + '.camera.CameraAttachActivity')
    service = services.get(PACKAGE + '.camera.ImportService')
    if (PACKAGE + '.CameraImportActivity' in activities and attach is not None and service is not None
            and {'android.permission.FOREGROUND_SERVICE',
                 'android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE'} <= permissions):
        action = 'android.hardware.usb.action.USB_DEVICE_ATTACHED'
        actions = {x.get(ANDROID + 'name') for x in attach.findall('./intent-filter/action')}
        metadata = any(x.get(ANDROID + 'name') == action and x.get(ANDROID + 'resource')
                       for x in attach.findall('meta-data'))
        foreground = service.get(ANDROID + 'foregroundServiceType', '')
        connected = 'connectedDevice' in foreground.split('|')
        try:
            connected = connected or bool(int(foreground, 0) & 16)
        except ValueError:
            pass
        if action in actions and metadata and connected and attach.get(ANDROID + 'exported') == 'true':
            features.add('camera-import')
    return {'package': root.get('package'),
            'version_code': int(root.get(ANDROID + 'versionCode', '0'), 0),
            'version_name': root.get(ANDROID + 'versionName', ''),
            'features': sorted(features)}


def tools(sdk):
    sdk = Path(sdk)
    analyzer = sdk / 'cmdline-tools/latest/bin/apkanalyzer'
    versions = [x for x in (sdk / 'build-tools').iterdir() if re.fullmatch(r'\d+(\.\d+)*', x.name)]
    versions.sort(key=lambda x: tuple(map(int, x.name.split('.'))))
    if not analyzer.is_file() or not versions:
        raise ValueError('Android apkanalyzer/build-tools are required')
    return analyzer, versions[-1] / 'apksigner'


def environment():
    env = os.environ.copy()
    if not env.get('JAVA_HOME'):
        env['JAVA_HOME'] = run(['/usr/libexec/java_home'])
    return env


def apk_info(apk, sdk):
    analyzer, signer = tools(sdk)
    env = environment()
    info = manifest_info(run([analyzer, 'manifest', 'print', apk], env=env))
    output = run([signer, 'verify', '--print-certs', apk], env=env)
    certificates = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', output, re.M)
    if not certificates:
        raise ValueError('APK signing certificate is unavailable')
    info['certificates'] = sorted(x.lower() for x in certificates)
    info['apk_sha256'] = sha256(apk)
    info['provenance'] = read_provenance(apk)
    return info


def read_provenance(apk):
    with zipfile.ZipFile(apk) as archive:
        entries = [x for x in archive.infolist() if x.filename == PROVENANCE]
        if not entries:
            return None
        if len(entries) != 1 or entries[0].file_size > 4096:
            raise ValueError('Duplicate or oversized APK provenance')
        with archive.open(entries[0]) as source:
            data = source.read(4097)
        if len(data) > 4096:
            raise ValueError('Oversized APK provenance')
        value = json.loads(data)
        if not isinstance(value, dict):
            raise ValueError('Invalid APK provenance object')
        return value


def check_features(info, installed=None):
    if info['package'] != PACKAGE:
        raise ValueError('This installer only accepts the Loupe application ID')
    expected = REQUIRED | set((installed or {}).get('features', []))
    missing = expected - set(info['features'])
    if missing:
        raise ValueError('Required features missing: ' + ', '.join(sorted(missing)))


def source_allowed(mode, source, main, issue=None):
    if mode == 'main' and source != main:
        raise ValueError('Daily preparation must build the fetched origin/main commit')
    if mode == 'recovery' and (not isinstance(issue, int) or issue <= 0):
        raise ValueError('Recovery preparation requires its issue number')


def contains(repo, ancestor, descendant='origin/main'):
    result = subprocess.run(['git', '-C', str(repo), 'merge-base', '--is-ancestor', ancestor, descendant],
                            capture_output=True)
    if result.returncode not in (0, 1):
        raise ValueError('Cannot establish source ancestry')
    return result.returncode == 0


def prepare(args):
    repo = Path(git(args.repo, 'rev-parse', '--show-toplevel'))
    if git(repo, 'status', '--porcelain', '--untracked-files=all'):
        raise ValueError('Commit changes or use a clean worktree before preparation')
    git(repo, 'fetch', 'origin', 'main')
    main = git(repo, 'rev-parse', 'origin/main')
    source = git(repo, 'rev-parse', (args.ref or 'origin/main') + '^{commit}')
    source_allowed(args.mode, source, main, args.issue)
    tree = git(repo, 'rev-parse', source + '^{tree}')
    # Fail before an expensive build if the required feature declarations are absent.
    source_manifest = git(repo, 'show', source + ':app/src/main/AndroidManifest.xml')
    source_info = manifest_info(source_manifest)
    source_info['package'] = source_info['package'] or PACKAGE
    check_features(source_info)
    output = Path(args.output).resolve()
    if output.exists():
        raise ValueError('Use a new output directory; existing bundles are not overwritten')
    with tempfile.TemporaryDirectory(prefix='loupe-apk-') as temp:
        checkout = Path(temp) / 'source'
        git(repo, 'worktree', 'add', '--detach', str(checkout), source)
        try:
            env = environment()
            env['ANDROID_HOME'] = str(Path(args.sdk).resolve())
            provenance = dict(schema=SCHEMA, source_commit=source, source_tree=tree, mode=args.mode, issue=args.issue)
            assets = Path(temp) / 'assets'
            save(assets / 'loupe-deployment.json', provenance)
            init = Path(temp) / 'deployment.init.gradle'
            init.write_text('allprojects { p -> p.plugins.withId("com.android.application") { p.android.sourceSets.main.assets.srcDir(' + json.dumps(str(assets)) + ') } }\n')
            subprocess.run(['./gradlew', '-I', str(init), ':app:assembleDebug', '--console=plain'], cwd=checkout,
                           env=env, check=True)
            apk = checkout / 'app/build/outputs/apk/debug/app-debug.apk'
            info = apk_info(apk, args.sdk)
            check_features(info)
            if info.get('provenance') != provenance:
                raise ValueError('Signed APK does not contain the requested source provenance')
            output.mkdir(parents=True)
            shutil.copyfile(apk, output / 'loupe.apk')
            bundle = dict(info, schema=SCHEMA, source_commit=source, source_tree=tree,
                          mode=args.mode, issue=args.issue, apk='loupe.apk')
            save(output / 'bundle.json', bundle)
        finally:
            # This unique checkout was created by this invocation; it contains only build outputs.
            git(repo, 'worktree', 'remove', '--force', str(checkout))
    print(output / 'bundle.json')


def adb(args, *parts):
    return run([args.adb, '-s', args.serial, *parts])


def installed(args):
    paths = adb(args, 'shell', 'pm', 'path', PACKAGE).splitlines()
    paths = [x.removeprefix('package:') for x in paths if x.startswith('package:')]
    if len(paths) != 1 or not paths[0].startswith('/data/app/') or not paths[0].endswith('.apk'):
        raise ValueError('An existing single-APK Loupe install must be identifiable; no uninstall is attempted')
    with tempfile.TemporaryDirectory(prefix='loupe-installed-') as temp:
        apk = Path(temp) / 'installed.apk'
        adb(args, 'pull', paths[0], str(apk))
        return apk_info(apk, args.sdk)


def verify_bundle(bundle, actual):
    if bundle.get('schema') != SCHEMA or bundle.get('mode') not in {'main', 'recovery'}:
        raise ValueError('Unsupported bundle')
    for name in ('package', 'version_code', 'version_name', 'certificates', 'apk_sha256', 'features'):
        if bundle.get(name) != actual.get(name):
            raise ValueError('Prepared APK identity mismatch: ' + name)
    expected = {key: bundle.get(key) for key in ('schema', 'source_commit', 'source_tree', 'mode', 'issue')}
    if actual.get('provenance') != expected:
        raise ValueError('Signed APK source provenance differs from bundle')
    check_features(actual)


def validate_update(candidate, previous):
    check_features(candidate, previous)
    if candidate['package'] != previous['package'] or candidate['certificates'] != previous['certificates']:
        raise ValueError('Package/signature differs; refusing an update that could require data removal')
    if candidate['version_code'] < previous['version_code']:
        raise ValueError('versionCode downgrade is forbidden')


def confirm(args):
    """Read back an OTA update without installing or changing application data."""
    args.confirm_only = True
    install(args)


def install(args):
    repo = Path(git(args.repo, 'rev-parse', '--show-toplevel'))
    bundle_path = Path(args.bundle).resolve()
    bundle = load(bundle_path)
    apk = (bundle_path.parent / bundle.get('apk', '')).resolve()
    if apk.parent != bundle_path.parent or not apk.is_file():
        raise ValueError('Bundle APK must be inside its bundle directory')
    with tempfile.TemporaryDirectory(prefix='loupe-candidate-') as temp:
        snapshot = Path(temp) / 'loupe.apk'
        shutil.copyfile(apk, snapshot)
        install_candidate(args, repo, bundle, snapshot)


def install_candidate(args, repo, bundle, apk):
    actual = apk_info(apk, args.sdk)
    verify_bundle(bundle, actual)
    if getattr(args, 'confirm_only', False) and bundle['mode'] != 'main':
        raise ValueError('OTA confirmation requires a main bundle')
    source = bundle.get('source_commit', '')
    if not re.fullmatch(r'[0-9a-f]{40}', source):
        raise ValueError('Invalid source commit')
    git(repo, 'fetch', 'origin', 'main')
    if git(repo, 'rev-parse', source + '^{tree}') != bundle.get('source_tree'):
        raise ValueError('Source tree does not match the preparation record')
    if bundle['mode'] == 'main':
        if not contains(repo, source):
            raise ValueError('The prepared source is not in origin/main')
    elif not args.ack_recovery or not bundle.get('issue'):
        raise ValueError('Recovery is not a daily build; explicit --ack-recovery and issue are required')
    if not re.fullmatch(r'[A-Za-z0-9._:-]{1,128}', args.serial):
        raise ValueError('Invalid explicit device serial')
    state_path = record_path(args, repo)
    with device_lock(state_path.parent / 'deployment'):
        install_locked(args, repo, bundle, actual, apk, source, state_path)


@contextmanager
def device_lock(state_path):
    state_path.parent.mkdir(parents=True, exist_ok=True)
    with state_path.with_suffix('.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise ValueError('Another deployment is active for this device') from error
        try:
            yield
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)


def install_locked(args, repo, bundle, actual, apk, source, state_path):
    for other in state_path.parent.glob('*.json'):
        if other != state_path and load(other).get('status') != 'installed-main':
            raise ValueError('Pending deployment on another ADB serial; finish using its original serial')
    previous_record = load(state_path) if state_path.exists() else None
    if previous_record and previous_record.get('serial') != args.serial:
        raise ValueError('Deployment record belongs to another device serial')
    if previous_record and previous_record.get('mode') == 'recovery':
        if not contains(repo, previous_record['source_commit'], source):
            raise ValueError('The installed recovery source is not integrated into this successor build')
    if adb(args, 'get-state') != 'device':
        raise ValueError('The selected device is unavailable')
    previous = installed(args)
    installed_source = previous.get('provenance') or {}
    if installed_source.get('mode') in {'main', 'recovery'}:
        recovery = installed_source.get('source_commit', '')
        if not re.fullmatch(r'[0-9a-f]{40}', recovery) or not contains(repo, recovery, source):
            raise ValueError('The installed APK source is not integrated into this successor build')
    confirming = getattr(args, 'confirm_only', False)
    if confirming:
        # previous is already the OTA-installed APK, not the pre-update version.
        verify_bundle(bundle, previous)
    else:
        validate_update(actual, previous)
    record = dict(schema=SCHEMA, mode=bundle['mode'], source_commit=source, issue=bundle.get('issue'),
                  apk_sha256=actual['apk_sha256'], previous_apk_sha256=previous['apk_sha256'],
                  version_code=actual['version_code'], features=actual['features'],
                  serial=args.serial, at=datetime.now(timezone.utc).isoformat(), status='pending-readback')
    if confirming:
        record['previous_apk_sha256'] = (previous_record or {}).get('apk_sha256')
        record['status'] = 'installed-main'
        record['method'] = 'ota-readback'
        save(state_path, record)
        print(json.dumps(record, ensure_ascii=False))
        return
    save(state_path, record)
    # No downgrade flags, uninstall, preference/database reads, or application-data writes.
    adb(args, 'install', '-r', str(apk))
    after = installed(args)
    for field in ('apk_sha256', 'package', 'certificates', 'version_code', 'features'):
        if after[field] != actual[field]:
            raise ValueError('Installed APK read-back mismatch; pending record retained: ' + field)
    record['status'] = 'installed-main' if bundle['mode'] == 'main' else 'pending-main-integration'
    save(state_path, record)
    print(json.dumps(record, ensure_ascii=False))


def record_path(args, repo):
    device = hashlib.sha256(args.serial.encode('utf-8')).hexdigest()
    return record_directory(args, repo) / (device + '.json')


def record_directory(args, repo):
    if args.records:
        return Path(args.records).resolve()
    common = Path(git(repo, 'rev-parse', '--path-format=absolute', '--git-common-dir'))
    return common.parent / 'outputs/deployments'


def status(args):
    repo = Path(git(args.repo, 'rev-parse', '--show-toplevel'))
    records = [load(x) for x in record_directory(args, repo).glob('*.json')]
    if not records:
        raise ValueError('No device deployment has been recorded yet')
    for value in records:
        print(json.dumps(value, ensure_ascii=False))
    if any(x.get('status') != 'installed-main' for x in records):
        raise ValueError('A deployment is pending read-back or integration into main')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', default='.')
    parser.add_argument('--sdk', default='/opt/homebrew/share/android-commandlinetools')
    commands = parser.add_subparsers(dest='command', required=True)
    p = commands.add_parser('prepare')
    p.add_argument('--mode', choices=['main', 'recovery'], default='main')
    p.add_argument('--ref')
    p.add_argument('--issue', type=int)
    p.add_argument('--output', required=True)
    p.set_defaults(action=prepare)
    p = commands.add_parser('inspect')
    p.add_argument('--apk', required=True)
    def inspect(args):
        info = apk_info(args.apk, args.sdk)
        print(json.dumps(info, ensure_ascii=False))
        check_features(info)
    p.set_defaults(action=inspect)
    for command, action in [('install', install), ('confirm', confirm), ('status', status)]:
        p = commands.add_parser(command)
        p.add_argument('--records', help='Explicit test/isolated record directory; default is shared across worktrees')
        if command in {'install', 'confirm'}:
            p.add_argument('--bundle', required=True)
            p.add_argument('--serial', required=True)
            p.add_argument('--adb', default='/opt/homebrew/share/android-commandlinetools/platform-tools/adb')
            if command == 'install':
                p.add_argument('--ack-recovery', action='store_true')
        p.set_defaults(action=action)
    args = parser.parse_args()
    try:
        args.action(args)
    except (ValueError, OSError, subprocess.CalledProcessError, ET.ParseError, json.JSONDecodeError) as error:
        print('Deployment stopped: ' + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
