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
import warnings
import zipfile
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('deploy_apk', Path(__file__).with_name('deploy-apk.py'))
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)

XML = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="jp.nano.rawgallery" android:versionCode="165" android:versionName="0.31.1">
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
<application>
<activity android:name=".MainActivity"/>
<activity android:name=".VideoActivity"/>
<service android:name=".NanoBananaService" android:exported="false" android:foregroundServiceType="dataSync"/>
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
        self.info = dict(deploy.manifest_info(XML), certificates=[deploy.CERTIFICATE], apk_sha256=deploy.sha256(self.apk))
        self.bundle = dict(self.info, schema=1, mode='main', apk='loupe.apk', issue=None,
                           source_commit='a'*40, source_tree='b'*40)
        self.stamp()
        self.bundle_path = self.root / 'bundle.json'
        self.previous = dict(self.info, provenance=None, version_code=164, version_name='0.31.0', apk_sha256='prior-hash')
        self.args = argparse.Namespace(repo=str(self.root), sdk='synthetic-sdk', bundle=str(self.bundle_path),
            serial='fake-phone', adb='fake-adb', ack_recovery=False, records=str(self.root/'records'))
        self.calls = []

    def record_path(self):
        return deploy.record_path(self.args, self.root)

    def stamp(self):
        self.info['provenance'] = {k: self.bundle.get(k) for k in ('schema','source_commit','source_tree','mode','issue')}

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

    def confirm(self, after=None, ancestry=None):
        deploy.save(self.bundle_path, self.bundle)
        with patch.object(deploy, 'git', side_effect=self.fake_git), \
             patch.object(deploy, 'contains', side_effect=ancestry or [True, True, True]), \
             patch.object(deploy, 'apk_info', return_value=self.info), \
             patch.object(deploy, 'installed', return_value=after or self.info), \
             patch.object(deploy, 'adb', side_effect=self.fake_adb), patch('builtins.print'):
            deploy.confirm(self.args)

    def test_ota_confirm_closes_integrated_recovery_without_install(self):
        deploy.save(self.record_path(), dict(serial=self.args.serial, mode='recovery',
                    source_commit='c'*40, apk_sha256='recovery-hash', status='pending-main-integration'))
        self.confirm()
        record = deploy.load(self.record_path())
        self.assertEqual(record['status'], 'installed-main')
        self.assertEqual(record['method'], 'ota-readback')
        self.assertEqual(record['previous_apk_sha256'], 'recovery-hash')
        self.assert_not_installed()

    def test_ota_mismatch_preserves_pending(self):
        pending = dict(serial=self.args.serial, mode='recovery', source_commit='c'*40,
                       status='pending-main-integration')
        deploy.save(self.record_path(), pending)
        with self.assertRaisesRegex(ValueError, 'identity mismatch'):
            self.confirm(after=dict(self.info, apk_sha256='wrong'))
        self.assertEqual(deploy.load(self.record_path()), pending)
        self.assert_not_installed()

    def test_ota_rejects_recovery_and_unintegrated_pending(self):
        self.bundle.update(mode='recovery', issue=87)
        self.stamp()
        with self.assertRaisesRegex(ValueError, 'main bundle'):
            self.confirm()
        self.bundle.update(mode='main', issue=None)
        self.stamp()
        deploy.save(self.record_path(), dict(serial=self.args.serial, mode='recovery',
                    source_commit='c'*40, status='pending-main-integration'))
        with self.assertRaisesRegex(ValueError, 'recovery source'):
            self.confirm(ancestry=[True, False])
        self.assert_not_installed()

    def test_ota_disconnect_preserves_pending(self):
        pending = dict(serial=self.args.serial, mode='recovery', source_commit='c'*40,
                       status='pending-main-integration')
        deploy.save(self.record_path(), pending)
        deploy.save(self.bundle_path, self.bundle)
        with patch.object(deploy, 'git', side_effect=self.fake_git), \
             patch.object(deploy, 'contains', return_value=True), \
             patch.object(deploy, 'apk_info', return_value=self.info), \
             patch.object(deploy, 'installed', side_effect=subprocess.CalledProcessError(1, 'fake-adb')), \
             patch.object(deploy, 'adb', side_effect=self.fake_adb):
            with self.assertRaises(subprocess.CalledProcessError):
                deploy.confirm(self.args)
        self.assertEqual(deploy.load(self.record_path()), pending)
        self.assert_not_installed()

    def test_ota_alternate_signature_on_candidate_and_device_preserves_record(self):
        pending = dict(serial=self.args.serial, mode='recovery', source_commit='c'*40,
                       status='pending-main-integration')
        deploy.save(self.record_path(), pending)
        self.info['certificates'] = ['alternate-certificate']
        self.bundle['certificates'] = ['alternate-certificate']
        with self.assertRaisesRegex(ValueError, 'Loupe signing certificate'):
            self.confirm(after=self.info)
        self.assertEqual(deploy.load(self.record_path()), pending)
        self.assertEqual(self.calls, [])

    def test_ota_pending_newer_main_cannot_be_closed_with_older_installed_main(self):
        pending = dict(serial=self.args.serial, mode='main', source_commit='c'*40,
                       status='pending-readback')
        deploy.save(self.record_path(), pending)
        def ancestry(repo, ancestor, descendant='origin/main'):
            # Candidate A is in main, but pending B is a descendant of A.
            return ancestor == 'a'*40
        deploy.save(self.bundle_path, self.bundle)
        with patch.object(deploy, 'git', side_effect=self.fake_git), \
             patch.object(deploy, 'contains', side_effect=ancestry), \
             patch.object(deploy, 'apk_info', return_value=self.info), \
             patch.object(deploy, 'installed', return_value=self.info), \
             patch.object(deploy, 'adb', side_effect=self.fake_adb):
            with self.assertRaisesRegex(ValueError, 'pending main source'):
                deploy.confirm(self.args)
        self.assertEqual(deploy.load(self.record_path()), pending)
        self.assert_not_installed()

    def test_ota_serial_lock_and_main_checks_are_preserved(self):
        with self.assertRaisesRegex(ValueError, 'not in origin/main'):
            self.confirm(ancestry=[False])
        self.args.serial = 'invalid serial'
        with self.assertRaisesRegex(ValueError, 'serial'):
            self.confirm()
        self.args.serial = 'fake-phone'
        with deploy.device_lock(self.record_path().parent / 'deployment'):
            with self.assertRaisesRegex(ValueError, 'Another deployment'):
                self.confirm()
        deploy.save(self.record_path().parent / 'other.json', dict(status='pending-readback'))
        with self.assertRaisesRegex(ValueError, 'original serial'):
            self.confirm()
        self.assert_not_installed()

    def assert_not_installed(self):
        self.assertFalse(any(x[0] == 'install' for x in self.calls))

    def test_manifest_complete(self):
        self.assertEqual(set(self.info['features']), deploy.REQUIRED)
        deploy.check_features(self.info)

    def test_nano_requires_private_data_sync_service(self):
        for xml in (
            XML.replace('android:exported="false"', 'android:exported="true"'),
            XML.replace('android:foregroundServiceType="dataSync"', ''),
            XML.replace('<service android:name=".NanoBananaService"', '<activity android:name=".NanoBananaService"'),
            XML.replace('<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>', ''),
        ):
            self.assertNotIn('nano-banana', deploy.manifest_info(xml)['features'])

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

    def test_camera_requires_foreground_permissions_and_component_types(self):
        for permission in ('FOREGROUND_SERVICE', 'FOREGROUND_SERVICE_CONNECTED_DEVICE'):
            xml = XML.replace('<uses-permission android:name="android.permission.'+permission+'"/>', '')
            self.assertNotIn('camera-import', deploy.manifest_info(xml)['features'])
        xml = XML.replace('<service android:name=".camera.ImportService"',
                          '<activity android:name=".camera.ImportService"')
        self.assertNotIn('camera-import', deploy.manifest_info(xml)['features'])
        xml = XML.replace('<activity android:name=".CameraImportActivity"',
                          '<service android:name=".CameraImportActivity"')
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
        self.stamp()
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
        record = deploy.load(self.record_path())
        self.assertEqual(record['status'], 'installed-main')
        self.assertEqual(record['apk_sha256'], self.info['apk_sha256'])
        installs = [x for x in self.calls if x[0] == 'install']
        self.assertEqual(len(installs), 1)
        self.assertEqual(installs[0][:2], ('install', '-r'))
        self.assertNotEqual(installs[0][2], str(self.apk))

    def test_readback_mismatch_keeps_pending(self):
        wrong = dict(self.info, apk_sha256='different-installed-apk')
        with self.assertRaisesRegex(ValueError, 'read-back mismatch'):
            self.apply(after=wrong)
        self.assertEqual(deploy.load(self.record_path())['status'], 'pending-readback')

    def test_disconnect_keeps_pending(self):
        deploy.save(self.bundle_path, self.bundle)
        with patch.object(deploy, 'git', side_effect=self.fake_git), \
             patch.object(deploy, 'contains', return_value=True), \
             patch.object(deploy, 'apk_info', return_value=self.info), \
             patch.object(deploy, 'installed', side_effect=[self.previous, subprocess.CalledProcessError(1, 'fake-adb')]), \
             patch.object(deploy, 'adb', side_effect=self.fake_adb), patch('builtins.print'):
            with self.assertRaises(subprocess.CalledProcessError):
                deploy.install(self.args)
        self.assertEqual(deploy.load(self.record_path())['status'], 'pending-readback')

    def test_recovery_stays_pending_and_cannot_be_replaced_by_unintegrated_main(self):
        self.bundle.update(mode='recovery', issue=87)
        self.stamp()
        self.args.ack_recovery = True
        self.apply(in_main=False)
        record = deploy.load(self.record_path())
        self.assertEqual(record['status'], 'pending-main-integration')
        self.bundle['mode'] = 'main'
        self.stamp()
        deploy.save(self.bundle_path, self.bundle)
        with patch.object(deploy, 'git', side_effect=self.fake_git), \
             patch.object(deploy, 'contains', side_effect=[True, False]), \
             patch.object(deploy, 'apk_info', return_value=self.info):
            with self.assertRaisesRegex(ValueError, 'recovery source'):
                deploy.install(self.args)

    def test_signed_provenance_cannot_be_relabelled(self):
        for field, value in [('source_commit', 'c'*40), ('source_tree', 'd'*40),
                             ('mode', 'recovery'), ('issue', 87)]:
            original = copy.deepcopy(self.bundle)
            self.bundle[field] = value
            with self.assertRaisesRegex(ValueError, 'Signed APK source provenance'):
                self.apply()
            self.assertEqual(self.calls, [])
            self.bundle = original
        self.info['provenance'] = None
        with self.assertRaisesRegex(ValueError, 'Signed APK source provenance'):
            self.apply()

    def test_unrelated_recovery_cannot_replace_pending_recovery(self):
        self.bundle.update(mode='recovery', issue=87)
        self.stamp()
        self.args.ack_recovery = True
        self.apply(in_main=False)
        self.calls.clear()
        # Recovery B would overwrite A's obligation and allow main(B) if accepted.
        deploy.save(self.record_path(), dict(mode='recovery', source_commit='c'*40, serial=self.args.serial))
        with self.assertRaisesRegex(ValueError, 'recovery source'):
            self.apply(in_main=False)
        self.assert_not_installed()
        self.assertEqual(deploy.load(self.record_path())['source_commit'], 'c'*40)

    def test_installed_recovery_is_enforced_without_a_host_record(self):
        self.previous['provenance'] = dict(schema=1, mode='recovery', source_commit='c'*40,
                                           source_tree='d'*40, issue=87)
        deploy.save(self.bundle_path, self.bundle)
        with patch.object(deploy, 'git', side_effect=self.fake_git), \
             patch.object(deploy, 'contains', side_effect=[True, False]), \
             patch.object(deploy, 'apk_info', return_value=self.info), \
             patch.object(deploy, 'installed', return_value=self.previous), \
             patch.object(deploy, 'adb', side_effect=self.fake_adb):
            with self.assertRaisesRegex(ValueError, 'installed APK source'):
                deploy.install(self.args)
        self.assert_not_installed()
        self.assertFalse((self.record_path()).exists())
        self.apply(in_main=True)

    def test_stale_main_cannot_replace_newer_main_with_equal_version(self):
        self.previous['provenance'] = dict(schema=1, mode='main', source_commit='c'*40)
        deploy.save(self.bundle_path, self.bundle)
        with patch.object(deploy, 'git', side_effect=self.fake_git), \
             patch.object(deploy, 'contains', side_effect=[True, False]), \
             patch.object(deploy, 'apk_info', return_value=self.info), \
             patch.object(deploy, 'installed', return_value=self.previous), \
             patch.object(deploy, 'adb', side_effect=self.fake_adb):
            with self.assertRaisesRegex(ValueError, 'installed APK source'):
                deploy.install(self.args)
        self.assert_not_installed()

    def test_candidate_snapshot_survives_external_file_replacement(self):
        def inspect_snapshot(apk, sdk):
            self.assertNotEqual(apk, self.apk)
            self.apk.write_bytes(b'replaced after verification')
            self.assertEqual(Path(apk).read_bytes(), b'synthetic apk bytes')
            return self.info
        deploy.save(self.bundle_path, self.bundle)
        with patch.object(deploy, 'git', side_effect=self.fake_git), \
             patch.object(deploy, 'contains', return_value=True), \
             patch.object(deploy, 'apk_info', side_effect=inspect_snapshot), \
             patch.object(deploy, 'installed', side_effect=[self.previous,self.info]), \
             patch.object(deploy, 'adb', side_effect=self.fake_adb), patch('builtins.print'):
            deploy.install(self.args)
        self.assertEqual(deploy.load(self.record_path())['status'], 'installed-main')

    def test_provenance_size_duplicates_and_object_type_are_bounded(self):
        for payloads in [[b'x'*4097], [b'{}', b'{}'], [b'[]']]:
            with warnings.catch_warnings():
                warnings.simplefilter('ignore', UserWarning)
                with zipfile.ZipFile(self.apk, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                    for payload in payloads:
                        archive.writestr(deploy.PROVENANCE, payload)
            with self.assertRaises(ValueError):
                deploy.read_provenance(self.apk)
        with zipfile.ZipFile(self.apk, 'w') as archive:
            archive.writestr(deploy.PROVENANCE, b'{"schema":1}')
        self.assertEqual(deploy.read_provenance(self.apk), {'schema':1})

    def test_serial_filenames_are_distinct_and_records_checked(self):
        self.args.serial = 'phone:123'
        a = self.record_path()
        self.args.serial = 'phone_123'
        self.assertNotEqual(a, self.record_path())
        deploy.save(self.record_path(), dict(serial='other-phone', mode='main'))
        with self.assertRaisesRegex(ValueError, 'another device serial'):
            self.apply()
        self.assert_not_installed()

    def test_recovery_must_close_with_original_transport_serial(self):
        self.bundle.update(mode='recovery', issue=87)
        self.stamp()
        self.args.ack_recovery = True
        self.apply()
        original = self.args.serial
        self.args.serial = 'wireless-phone:123'
        self.bundle.update(mode='main', issue=None)
        self.stamp()
        self.calls.clear()
        with self.assertRaisesRegex(ValueError, 'original serial'):
            self.apply()
        self.assert_not_installed()
        with patch.object(deploy, 'git', side_effect=self.fake_git), patch('builtins.print'):
            with self.assertRaisesRegex(ValueError, 'pending'):
                deploy.status(self.args)
        self.args.serial = original
        self.apply()
        with patch.object(deploy, 'git', side_effect=self.fake_git), patch('builtins.print'):
            deploy.status(self.args)

    def test_same_device_overlapping_installs_are_rejected(self):
        state = self.record_path()
        with deploy.device_lock(state.parent / 'deployment'):
            with self.assertRaisesRegex(ValueError, 'Another deployment'):
                self.apply()
        self.assert_not_installed()
        self.apply()

    def test_default_records_are_shared_across_worktrees(self):
        self.args.records = None
        with patch.object(deploy, 'git', side_effect=self.fake_git):
            self.assertEqual(deploy.record_directory(self.args, self.root), self.root/'outputs/deployments')

    def test_status_requires_an_actual_verified_main_record(self):
        with patch.object(deploy, 'git', side_effect=self.fake_git), patch('builtins.print'):
            with self.assertRaisesRegex(ValueError, 'No device'):
                deploy.status(self.args)
            deploy.save(self.record_path(), {'status':'pending-main-integration'})
            with self.assertRaisesRegex(ValueError, 'pending'):
                deploy.status(self.args)
            deploy.save(self.record_path(), {'status':'installed-main'})
            deploy.status(self.args)



class InspectSigningPinTests(unittest.TestCase):
    def test_cli_inspection_compares_the_expected_test_signer(self):
        info = dict(deploy.manifest_info(XML), certificates=[deploy.CERTIFICATE])
        for expected, result in ((deploy.CERTIFICATE, 0), ('0' * 64, 1)):
            with self.subTest(expected=expected), \
                 patch.object(deploy.sys, 'argv', ['deploy-apk.py', 'inspect', '--apk', 'fake.apk', '--certificate', expected]), \
                 patch.object(deploy, 'apk_info', return_value=info), patch('builtins.print'):
                self.assertEqual(deploy.main(), result)


class SdkToolSelectionTests(unittest.TestCase):
    def test_pinned_signer_is_used_even_when_runner_has_newer_sdk(self):
        with tempfile.TemporaryDirectory() as temp:
            sdk = Path(temp)
            analyzer = sdk / 'cmdline-tools/latest/bin/apkanalyzer'
            pinned = sdk / 'build-tools/35.0.0/apksigner'
            future = sdk / 'build-tools/37.0.0/apksigner'
            for path in (analyzer, pinned, future):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.touch()
            self.assertEqual(deploy.tools(sdk), (analyzer, pinned))
            pinned.unlink()
            with self.assertRaisesRegex(ValueError, '35.0.0'):
                deploy.tools(sdk)

if __name__ == '__main__':
    unittest.main()
