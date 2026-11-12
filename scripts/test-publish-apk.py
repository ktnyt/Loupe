#!/usr/bin/env python3
"""Synthetic release tests; never contact GitHub or a device."""
import argparse
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('publish_apk', Path(__file__).with_name('publish-apk.py'))
publish = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publish)
deploy = publish.deploy


class PublishTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / 'loupe.apk').write_bytes(b'synthetic signed apk')
        (self.root / 'notes.md').write_text('Release notes')
        self.bundle = dict(schema=1, mode='main', source_commit='a'*40, source_tree='b'*40,
            issue=None, apk='loupe.apk', package=deploy.PACKAGE, version_code=167,
            version_name='0.31.3', certificates=[publish.CERTIFICATE], features=sorted(deploy.REQUIRED),
            apk_sha256=deploy.sha256(self.root / 'loupe.apk'))
        self.info = copy.deepcopy(self.bundle)
        self.info['provenance'] = {k: self.bundle[k] for k in ('schema', 'mode', 'source_commit', 'source_tree', 'issue')}
        self.args = argparse.Namespace(repo=self.root, sdk='fake', gh='fake-gh',
            bundle=self.root / 'bundle.json', notes_file=self.root / 'notes.md', title=None)
        self.calls = []
        self.corrupt = False
        self.metadata_bad = False
        self.exists = False
        self.release_exists = False
        self.network_error = False
        self.download_error = False
        self.main = 'a'*40

    def tearDown(self):
        self.temp.cleanup()

    def fake_git(self, repo, *parts):
        return {('rev-parse', '--show-toplevel'):str(self.root), ('fetch', 'origin', 'main'):'',
                ('rev-parse', 'origin/main'):self.main,
                ('rev-parse', 'a'*40+'^{tree}'):'b'*40}[parts]

    def fake_gh(self, args, *parts):
        self.calls.append(parts)
        if parts[0] == 'api':
            if self.exists or (self.release_exists and '/releases/' in parts[1]):
                return '{}'
            raise subprocess.CalledProcessError(1, 'gh', stderr='HTTP 503' if self.network_error else 'HTTP 404')
        if parts[:2] == ('release', 'create'):
            self.snapshot = Path(parts[-2]).parent
            self.assertNotEqual(self.snapshot, self.root)
            self.assertEqual(Path(parts[-2]).name, 'loupe.apk')
            self.assertEqual(Path(parts[-1]).name, 'bundle.json')
            self.assertEqual(parts[parts.index('--target')+1], 'a'*40)
            self.assertIn('--draft', parts)
            return ''
        if parts[:2] == ('release', 'download'):
            if self.download_error:
                raise subprocess.CalledProcessError(1, 'gh')
            target = Path(parts[parts.index('--dir')+1])
            for name in ('loupe.apk', 'bundle.json'):
                shutil.copyfile(self.snapshot/name, target/name)
            if self.corrupt:
                (target/'loupe.apk').write_bytes(b'wrong')
            return ''
        if parts[:2] == ('release', 'view'):
            return json.dumps(dict(tagName='v0.31.3', targetCommitish='a'*40, isDraft=True,
                assets=[dict(name='loupe.apk'), dict(name='bad.json' if self.metadata_bad else 'bundle.json')]))
        if parts[:2] == ('release', 'edit'):
            self.assertIn('--draft=false', parts)
            return ''
        raise AssertionError(parts)

    def apply(self):
        deploy.save(self.args.bundle, self.bundle)
        with patch.object(deploy, 'git', side_effect=self.fake_git), \
             patch.object(deploy, 'apk_info', return_value=self.info), \
             patch.object(publish, 'gh', side_effect=self.fake_gh), patch('builtins.print'):
            publish.publish(self.args)

    def assert_unpublished(self):
        self.assertFalse(any(x[:2] == ('release', 'edit') for x in self.calls))

    def test_success_publishes_only_verified_main_assets(self):
        self.apply()
        self.assertEqual(self.calls[-1], ('release', 'edit', 'v0.31.3', '--repo', 'ktnyt/Loupe', '--draft=false'))
        self.assertEqual(sum(x[:2] == ('release', 'create') for x in self.calls), 1)

    def test_mode_hash_signature_source_and_tree_rejected_before_github(self):
        original = copy.deepcopy(self.bundle)
        info = copy.deepcopy(self.info)
        for field, value in [('mode', 'recovery'), ('apk_sha256', 'wrong'),
                             ('certificates', ['other']), ('source_tree', 'c'*40)]:
            with self.subTest(field=field):
                self.bundle = copy.deepcopy(original)
                self.info = copy.deepcopy(info)
                self.bundle[field] = value
                if field in ('mode', 'source_tree'):
                    self.info['provenance'][field] = value
                if field == 'certificates':
                    self.info[field] = value
                with self.assertRaises(ValueError):
                    self.apply()
                self.assertEqual(self.calls, [])
        self.bundle = original
        self.info = info
        self.main = 'c'*40
        with self.assertRaisesRegex(ValueError, 'origin/main'):
            self.apply()
        self.assertEqual(self.calls, [])

    def test_missing_feature_bad_version_and_unsigned_provenance_rejected(self):
        for field, value in [('features', ['library']), ('version_name', '../0.31.3'),
                             ('version_code', 0), ('provenance', None)]:
            with self.subTest(field=field):
                saved = copy.deepcopy(self.info)
                bundle = copy.deepcopy(self.bundle)
                self.info[field] = value
                if field != 'provenance':
                    self.bundle[field] = value
                with self.assertRaises(ValueError):
                    self.apply()
                self.assertEqual(self.calls, [])
                self.info, self.bundle = saved, bundle

    def test_existing_tag_or_release_is_not_replaced(self):
        self.exists = True
        with self.assertRaisesRegex(ValueError, 'overwritten'):
            self.apply()
        self.assertFalse(any(x[0] == 'release' for x in self.calls))

    def test_existing_release_is_not_replaced_even_without_tag(self):
        self.release_exists = True
        with self.assertRaisesRegex(ValueError, 'overwritten'):
            self.apply()
        self.assertEqual(len(self.calls), 2)
        self.assert_unpublished()

    def test_errors_are_not_interpreted_as_absence(self):
        self.network_error = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.apply()
        self.assert_unpublished()
        self.assertFalse(any(x[:2] == ('release', 'create') for x in self.calls))

    def test_asset_or_metadata_mismatch_keeps_draft(self):
        for field in ('corrupt', 'metadata_bad', 'download_error'):
            with self.subTest(field=field):
                self.calls.clear()
                setattr(self, field, True)
                with self.assertRaises((ValueError, subprocess.CalledProcessError)):
                    self.apply()
                self.assertTrue(any(x[:2] == ('release', 'create') for x in self.calls))
                self.assert_unpublished()
                self.assertFalse(any('delete' in x for x in self.calls))
                setattr(self, field, False)


if __name__ == '__main__':
    unittest.main()
