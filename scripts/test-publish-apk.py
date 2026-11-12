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
        self.bundle['provenance'] = copy.deepcopy(self.info['provenance'])
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
        self.official_main = 'a'*40
        self.tag_race = False
        self.tag_wrong = False
        self.draft_swapped = False
        self.publish_error = False
        self.upload_error = False
        self.extra_release_page = False

    def tearDown(self):
        self.temp.cleanup()

    def fake_git(self, repo, *parts):
        return {('rev-parse', '--show-toplevel'):str(self.root), ('fetch', 'origin', 'main'):'',
                ('rev-parse', 'origin/main'):self.main,
                ('rev-parse', 'a'*40+'^{tree}'):'b'*40}[parts]

    def fake_gh(self, args, *parts):
        self.calls.append(parts)
        if parts[0] == 'api':
            endpoint = parts[1]
            if self.network_error:
                raise subprocess.CalledProcessError(1, 'gh', stderr='HTTP 503')
            if endpoint.endswith('/git/ref/heads/main'):
                return json.dumps(dict(ref='refs/heads/main', object=dict(type='commit', sha=self.official_main)))
            if endpoint == 'repos/ktnyt/Loupe/releases?per_page=100':
                self.assertEqual(parts[2:], ('--paginate', '--slurp'))
                existing = [dict(id=88, tag_name='v0.31.3', draft=True)] if self.release_exists else []
                return json.dumps([[], existing] if self.extra_release_page else [existing])
            if endpoint == 'repos/ktnyt/Loupe/releases':
                self.assertEqual(parts[2:5], ('--method', 'POST', '--input'))
                request = deploy.load(parts[5])
                self.assertEqual(request, dict(tag_name='v0.31.3', target_commitish='a'*40,
                    name='v0.31.3', body='Release notes', draft=True, prerelease=False))
                self.draft_created = True
                return json.dumps(dict(id=101, tag_name='v0.31.3', draft=True))
            if '/releases/tags/' in endpoint:
                # REST cannot resolve a draft's pending tag before the Git ref exists.
                raise subprocess.CalledProcessError(1, 'gh', stderr='HTTP 404')
            if endpoint.endswith('/git/refs'):
                self.assertEqual(parts[2:], ('--method', 'POST', '--raw-field', 'ref=refs/tags/v0.31.3',
                                           '--raw-field', 'sha=' + 'a'*40))
                if self.tag_race:
                    raise subprocess.CalledProcessError(1, 'gh', stderr='HTTP 422 Reference already exists')
                self.tag_created = True
                return '{}'
            if endpoint.endswith('/git/ref/tags/v0.31.3') and self.tag_created:
                return json.dumps(dict(ref='refs/tags/v0.31.3', object=dict(type='commit',
                    sha=('c' if self.tag_wrong else 'a')*40)))
            if '/releases/' in endpoint and self.draft_created:
                if '--method' in parts:
                    self.assertEqual(parts, ('api', 'repos/ktnyt/Loupe/releases/101', '--method', 'PATCH',
                                             '--field', 'draft=false'))
                    if self.publish_error:
                        raise subprocess.CalledProcessError(1, 'gh', stderr='HTTP 503')
                    return '{}'
                return json.dumps(dict(id=102 if self.tag_created and self.draft_swapped else 101,
                    tag_name='v0.31.3', target_commitish='a'*40, draft=True,
                    assets=[dict(name='loupe.apk'), dict(name='bad.json' if self.metadata_bad else 'bundle.json')]))
            if self.exists or (self.release_exists and '/releases/' in endpoint):
                return '{}'
            raise subprocess.CalledProcessError(1, 'gh', stderr='HTTP 404')
        if parts[:2] == ('release', 'upload'):
            self.assertTrue(self.draft_created)
            if self.upload_error:
                raise subprocess.CalledProcessError(1, 'gh', stderr='HTTP 503')
            self.snapshot = Path(parts[-2]).parent
            self.assertNotEqual(self.snapshot, self.root)
            self.assertEqual(Path(parts[-2]).name, 'loupe.apk')
            self.assertEqual(Path(parts[-1]).name, 'bundle.json')
            self.assertNotIn('--clobber', parts)
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
        raise AssertionError(parts)

    def apply(self):
        self.tag_created = False
        self.draft_created = False
        deploy.save(self.args.bundle, self.bundle)
        with patch.object(deploy, 'git', side_effect=self.fake_git), \
             patch.object(deploy, 'apk_info', return_value=self.info), \
             patch.object(publish, 'gh', side_effect=self.fake_gh), patch('builtins.print'):
            publish.publish(self.args)

    def assert_unpublished(self):
        self.assertFalse(any('--method' in x and x[x.index('--method')+1] == 'PATCH' for x in self.calls))

    def test_success_publishes_only_verified_main_assets(self):
        self.apply()
        self.assertEqual(self.calls[-1], ('api', 'repos/ktnyt/Loupe/releases/101', '--method', 'PATCH', '--field', 'draft=false'))
        self.assertTrue(self.tag_created)
        self.assertEqual(sum(x[:2] == ('api', 'repos/ktnyt/Loupe/releases') for x in self.calls), 1)
        self.assertFalse(any('/releases/tags/' in str(x) for x in self.calls))

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

    def test_private_extra_field_and_forged_bundle_provenance_never_reach_github(self):
        original = copy.deepcopy(self.bundle)
        for field, value in [('private_api_key', 'synthetic-secret'),
                             ('provenance', dict(self.info['provenance'], private='synthetic-secret'))]:
            with self.subTest(field=field):
                self.bundle = copy.deepcopy(original)
                self.bundle[field] = value
                with self.assertRaises(ValueError):
                    self.apply()
                self.assertEqual(self.calls, [])
                self.assert_unpublished()

    def test_fork_main_cannot_publish_to_official_repository(self):
        self.official_main = 'c'*40
        with self.assertRaisesRegex(ValueError, 'official ktnyt/Loupe main'):
            self.apply()
        self.assertEqual(len(self.calls), 1)
        self.assertFalse(self.draft_created)
        self.assert_unpublished()

    def test_tag_appearing_during_download_keeps_draft_unpublished(self):
        self.tag_race = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.apply()
        self.assertTrue(self.draft_created)
        self.assert_unpublished()
        self.assertFalse(any('delete' in x for x in self.calls))

    def test_wrong_created_tag_or_changed_draft_id_cannot_be_published(self):
        for field in ('tag_wrong', 'draft_swapped'):
            with self.subTest(field=field):
                self.calls.clear()
                setattr(self, field, True)
                with self.assertRaises(ValueError):
                    self.apply()
                self.assertTrue(self.tag_created)
                self.assert_unpublished()
                setattr(self, field, False)

    def test_publish_failure_preserves_created_tag_and_draft(self):
        self.publish_error = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.apply()
        self.assertTrue(self.tag_created)
        self.assertTrue(self.draft_created)
        self.assertFalse(any('delete' in x for x in self.calls))

    def test_existing_tag_or_release_is_not_replaced(self):
        self.exists = True
        with self.assertRaisesRegex(ValueError, 'overwritten'):
            self.apply()
        self.assertFalse(any(x[0] == 'release' for x in self.calls))

    def test_existing_release_is_not_replaced_even_without_tag(self):
        self.release_exists = True
        with self.assertRaisesRegex(ValueError, 'overwritten'):
            self.apply()
        self.assertEqual(len(self.calls), 3)
        self.assert_unpublished()

    def test_existing_pending_tag_draft_on_later_page_is_not_overwritten(self):
        self.release_exists = True
        self.extra_release_page = True
        with self.assertRaisesRegex(ValueError, 'overwritten'):
            self.apply()
        self.assertFalse(self.draft_created)
        self.assert_unpublished()

    def test_pending_tag_rest_lookup_404_does_not_block_success(self):
        # The fake API rejects every /releases/tags/ lookup; success must use create-response ID.
        self.apply()
        self.assertTrue(self.draft_created)
        self.assertTrue(self.tag_created)
        self.assertFalse(any('/releases/tags/' in str(x) for x in self.calls))
        self.assertEqual(self.calls[-1][1], 'repos/ktnyt/Loupe/releases/101')

    def test_errors_are_not_interpreted_as_absence(self):
        self.network_error = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.apply()
        self.assert_unpublished()
        self.assertFalse(self.draft_created)

    def test_asset_or_metadata_mismatch_keeps_draft(self):
        for field in ('corrupt', 'metadata_bad', 'download_error', 'upload_error'):
            with self.subTest(field=field):
                self.calls.clear()
                setattr(self, field, True)
                with self.assertRaises((ValueError, subprocess.CalledProcessError)):
                    self.apply()
                self.assertTrue(self.draft_created)
                self.assert_unpublished()
                self.assertFalse(any('delete' in x for x in self.calls))
                setattr(self, field, False)


if __name__ == '__main__':
    unittest.main()
