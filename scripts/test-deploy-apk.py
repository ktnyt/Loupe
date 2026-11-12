#!/usr/bin/env python3
"""Failure-before-install and read-back tests; no connected device or real APK modifications."""
import argparse
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('deploy_apk', Path(__file__).with_name('deploy-apk.py'))
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)

XML = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="jp.nano.rawgallery" android:versionCode="165" android:versionName="0.31.1">
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO"/>
<application>
<activity android:name=".MainActivity"/>
<activity android:name=".VideoActivity"/>
<activity android:name=".NanoBananaActivity"/>
<activity android:name=".NanoBananaSettingsActivity"/>
<activity android:name=".CameraImportActivity"/>
<activity android:name=".camera.CameraAttachActivity" android:exported="true">
<intent-filter><action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"/></intent-filter>
<meta-data android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" android:resource="@xml/camera_import_devices"/>
</activity>
<service android:name=".camera.ImportService" android:foregroundServiceType="connectedDevice"/>
</application></manifest>'''


class DeployTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.apk = self.root / 'loupe.apk'
        self.apk.write_bytes(b'synthetic apk bytes')
        self.info = dict(deploy.manifest_info(XML), certificates=['abc123'], apk_sha256=deploy.sha256(self.apk))
        self.bundle = dict(self.info, schema=1, mode='main', apk='loupe.apk', issue=None,
                           source_commit='a'*40, source_tree='b'*40)
        self.bundle_path = self.root / 'bundle.json'
        self.previous = dict(self.info, version_code=164, version_name='0.31.0', apk_sha256='prior-hash')
        self.args = argparse.Namespace(repo=str(self.root), sdk='synthetic-sdk', bundle=str(self.bundle_path),
            serial='fake-phone', adb='fake-adb', ack_recovery=False, records=str(self.root/'records'))
        self.calls = []

    def tearDown(self):
        self.temp.cleanup()

    def fake_git(self, repo, *args):
        if args == ('rev-parse', '--show-toplevel'):
            return str(self.root)
        if args == ('rev-parse', 'a'*40+'^{tree}'):
            return 'b'*40
        if args == ('fetch', 'origin', 'main'):
            return ''
        if args == ('rev-parse', '--path-format=absolute', '--git-common-dir'):
            return str(self.root/'.git')
        raise AssertionError(args)

    def fake_adb(self, args, *parts):
        self.calls.append(parts)
        if parts == ('get-state',):
            return 'device'
        if parts[:2] == ('install', '-r'):
            return 'Success'
        raise AssertionError(parts)

    def apply(self, after=None, in_main=True):
        deploy.save(self.bundle_path, self.bundle)
        with patch.object(deploy, 'git', side_effect=self.fake_git), \
             patch.object(deploy, 'contains', return_value=in_main), \
             patch.object(deploy, 'apk_info', return_value=self.info), \
             patch.object(deploy, 'installed', side_effect=[self.previous, after or self.info]), \
             patch.object(deploy, 'adb', side_effect=self.fake_adb), patch('builtins.print'):
            deploy.install(self.args)

    def assert_not_installed(self):
        self.assertFalse(any(x[0] == 'install' for x in self.calls))

    def test_manifest_complete(self):
        self.assertEqual(set(self.info['features']), deploy.REQUIRED)
        deploy.check_features(self.info)

    def test_camera_missing_even_when_current_install_lacks_it(self):
        self.info['features'].remove('camera-import')
        self.bundle['features'] = list(self.info['features'])
        self.previous['features'] = list(self.info['features'])
        with self.assertRaisesRegex(ValueError, 'camera-import'):
            self.apply()
        self.assert_not_installed()

    def test_existing_capability_cannot_disappear(self):
        self.info['features'].remove('nano-banana')
        self.bundle['features'] = list(self.info['features'])
        with self.assertRaisesRegex(ValueError, 'nano-banana'):
            self.apply()
        self.assert_not_installed()

    def test_usb_filter_service_and_enable_state_are_required(self):
        for xml in [XML.replace('USB_DEVICE_ATTACHED', 'OTHER'),
                    XML.replace('connectedDevice', 'dataSync'),
                    XML.replace('android:name=".camera.CameraAttachActivity"',
                                'android:name=".camera.CameraAttachActivity" android:enabled="false"')]:
            self.assertNotIn('camera-import', deploy.manifest_info(xml)['features'])

    def test_hash_and_source_mismatch_stop_before_adb(self):
        self.bundle['apk_sha256'] = 'wrong'
        with self.assertRaisesRegex(ValueError, 'identity mismatch'):
            self.apply()
        self.assertEqual(self.calls, [])
        self.bundle['apk_sha256'] = self.info['apk_sha256']
        with self.assertRaisesRegex(ValueError, 'not in origin/main'):
            self.apply(in_main=False)
        self.assertEqual(self.calls, [])

    def test_dirty_source_is_not_built(self):
        args = argparse.Namespace(repo=str(self.root))
        with patch.object(deploy, 'git', side_effect=[str(self.root), ' M app/example.java']), \
             patch.object(subprocess, 'run') as command:
            with self.assertRaisesRegex(ValueError, 'clean worktree'):
                deploy.prepare(args)
            command.assert_not_called()

    def test_unmerged_daily_and_unlabelled_recovery_are_rejected(self):
        with self.assertRaises(ValueError):
            deploy.source_allowed('main', 'feature-sha', 'main-sha')
        with self.assertRaises(ValueError):
            deploy.source_allowed('recovery', 'feature-sha', 'main-sha')
        deploy.source_allowed('recovery', 'feature-sha', 'main-sha', 87)
        self.bundle.update(mode='recovery', issue=87)
        with self.assertRaisesRegex(ValueError, 'ack-recovery'):
            self.apply(in_main=False)
        self.assert_not_installed()

    def test_signature_and_version_downgrade_refuse_install(self):
        for field, value in [('certificates', ['other']), ('version_code', 999)]:
            original = copy.deepcopy(self.previous)
            self.previous[field] = value
            with self.assertRaises(ValueError):
                self.apply()
            self.assert_not_installed()
            self.previous = original

    def test_success_reads_back_exact_apk(self):
        self.apply()
        record = deploy.load(self.root/'records/fake-phone.json')
        self.assertEqual(record['status'], 'installed-main')
        self.assertEqual(record['apk_sha256'], self.info['apk_sha256'])
        installs = [x for x in self.calls if x[0] == 'install']
        self.assertEqual(installs, [('install', '-r', str(self.apk))])

    def test_readback_mismatch_keeps_pending(self):
        wrong = dict(self.info, apk_sha256='different-installed-apk')
        with self.assertRaisesRegex(ValueError, 'read-back mismatch'):
            self.apply(after=wrong)
        self.assertEqual(deploy.load(self.root/'records/fake-phone.json')['status'], 'pending-readback')

    def test_disconnect_keeps_pending(self):
        deploy.save(self.bundle_path, self.bundle)
        with patch.object(deploy, 'git', side_effect=self.fake_git), \
             patch.object(deploy, 'contains', return_value=True), \
             patch.object(deploy, 'apk_info', return_value=self.info), \
             patch.object(deploy, 'installed', side_effect=[self.previous, subprocess.CalledProcessError(1, 'fake-adb')]), \
             patch.object(deploy, 'adb', side_effect=self.fake_adb), patch('builtins.print'):
            with self.assertRaises(subprocess.CalledProcessError):
                deploy.install(self.args)
        self.assertEqual(deploy.load(self.root/'records/fake-phone.json')['status'], 'pending-readback')

    def test_recovery_stays_pending_and_cannot_be_replaced_by_unintegrated_main(self):
        self.bundle.update(mode='recovery', issue=87)
        self.args.ack_recovery = True
        self.apply(in_main=False)
        record = deploy.load(self.root/'records/fake-phone.json')
        self.assertEqual(record['status'], 'pending-main-integration')
        self.bundle['mode'] = 'main'
        deploy.save(self.bundle_path, self.bundle)
        with patch.object(deploy, 'git', side_effect=self.fake_git), \
             patch.object(deploy, 'contains', side_effect=[True, False]), \
             patch.object(deploy, 'apk_info', return_value=self.info):
            with self.assertRaisesRegex(ValueError, 'recovery source'):
                deploy.install(self.args)

    def test_default_records_are_shared_across_worktrees(self):
        self.args.records = None
        with patch.object(deploy, 'git', side_effect=self.fake_git):
            self.assertEqual(deploy.record_directory(self.args, self.root), self.root/'outputs/deployments')

    def test_status_requires_an_actual_verified_main_record(self):
        with patch.object(deploy, 'git', side_effect=self.fake_git), patch('builtins.print'):
            with self.assertRaisesRegex(ValueError, 'No device'):
                deploy.status(self.args)
            deploy.save(self.root/'records/fake-phone.json', {'status':'pending-main-integration'})
            with self.assertRaisesRegex(ValueError, 'pending'):
                deploy.status(self.args)
            deploy.save(self.root/'records/fake-phone.json', {'status':'installed-main'})
            deploy.status(self.args)


if __name__ == '__main__':
    unittest.main()
