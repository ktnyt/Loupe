#!/usr/bin/env python3
"""Synthetic orchestration tests; no GitHub, SDK, device or build access."""
import argparse
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('auto_release', Path(__file__).with_name('auto-release.py'))
auto = importlib.util.module_from_spec(spec)
spec.loader.exec_module(auto)
deploy = auto.deploy


class AutomaticReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.source = 'a' * 40
        self.main = self.source
        self.official = self.source
        self.version = dict(version_code=168, version_name='0.31.4')
        self.tag = 'v0.31.4'
        self.args = argparse.Namespace(repo=self.repo, sdk='/synthetic/android-sdk', gh='fake-gh')
        self.apk = b'synthetic signed apk'
        self.bundle = dict(schema=1, mode='main', source_commit=self.source, source_tree='b'*40,
            issue=None, apk='loupe.apk', package=deploy.PACKAGE, **self.version,
            certificates=[deploy.CERTIFICATE], features=sorted(deploy.REQUIRED))
        (self.repo / 'hash-input').write_bytes(self.apk)
        self.bundle['apk_sha256'] = deploy.sha256(self.repo / 'hash-input')
        self.bundle['provenance'] = {k: self.bundle[k] for k in ('schema', 'mode', 'source_commit', 'source_tree', 'issue')}
        self.release = dict(id=101, tag_name=self.tag, target_commitish=self.source,
            draft=False, prerelease=False, assets=[dict(name='bundle.json', id=201), dict(name='loupe.apk', id=202)])
        self.existing = False
        self.corrupt = False
        self.replaced = False
        self.calls = []
        self.downloads = []
        self.preparations = []
        self.publications = []
        self.sdks = []
        for owner, name, replacement in ((deploy, 'git', self.git),
                (deploy, 'prepare', self.prepare), (deploy, 'apk_info', self.apk_info),
                (auto.publisher, 'gh', self.gh), (auto.publisher, 'download_asset', self.download),
                (auto.publisher, 'publish', self.publish)):
            patcher = patch.object(owner, name, side_effect=replacement)
            patcher.start()
            self.addCleanup(patcher.stop)
        patcher = patch.object(auto.versions, 'version', return_value=self.version)
        patcher.start()
        self.addCleanup(patcher.stop)

    def git(self, repo, *parts):
        return {('rev-parse', '--show-toplevel'): str(self.repo), ('rev-parse', 'HEAD'): self.source,
                ('fetch', 'origin', 'main'): '', ('rev-parse', 'origin/main'): self.main,
                ('rev-parse', self.source + '^{tree}'): 'b'*40}[parts]

    def gh(self, args, *parts):
        self.calls.append(parts)
        self.assertEqual(parts[0], 'api')
        self.assertNotIn('--method', parts, 'Orchestrator must not mutate existing releases')
        endpoint = parts[1]
        base = 'repos/ktnyt/Loupe'
        if endpoint == base + '/git/ref/heads/main':
            return json.dumps(dict(ref='refs/heads/main', object=dict(type='commit', sha=self.official)))
        if endpoint == base + '/releases?per_page=100':
            self.assertEqual(parts[2:], ('--paginate', '--slurp'))
            return json.dumps([[], [self.release] if self.existing else []])
        if endpoint == base + '/git/ref/tags/' + self.tag:
            return json.dumps(dict(ref='refs/tags/' + self.tag, object=dict(type='commit', sha=self.source)))
        if endpoint == base + '/releases/101':
            current = copy.deepcopy(self.release)
            if self.replaced:
                current['assets'][0]['id'] = 999
            return json.dumps(current)
        self.fail('Unexpected network endpoint: ' + endpoint)

    def download(self, args, asset_id, output):
        self.downloads.append(asset_id)
        if asset_id == 201:
            deploy.save(output, self.bundle)
        elif asset_id == 202:
            Path(output).write_bytes(b'corrupt' if self.corrupt else self.apk)
        else:
            self.fail('Unexpected asset ID')

    def apk_info(self, apk, sdk):
        self.sdks.append(sdk)
        actual = copy.deepcopy(self.bundle)
        actual['apk_sha256'] = deploy.sha256(apk)
        return actual

    def prepare(self, args):
        self.preparations.append(args)
        self.assertEqual(args.mode, 'main')
        self.assertEqual(args.ref, self.source)
        self.assertFalse(args.output.exists())
        self.assertFalse(args.output.is_relative_to(self.repo))
        args.output.mkdir()
        deploy.save(args.output / 'bundle.json', self.bundle)
        (args.output / 'loupe.apk').write_bytes(self.apk)

    def publish(self, args):
        self.publications.append(args)
        self.assertEqual(auto.publisher.verify_candidate(args, self.repo,
                         deploy.load(args.bundle), args.bundle.parent / 'loupe.apk'), self.tag)
        self.assertIn('https://github.com/ktnyt/Loupe/commit/' + self.source,
                      args.notes_file.read_text())
        self.assertEqual(args.title, self.tag)

    def test_new_release_prepares_and_publishes_with_sdk(self):
        auto.automatic_release(self.args)
        self.assertEqual(len(self.preparations), 1)
        self.assertEqual(len(self.publications), 1)
        self.assertEqual(self.preparations[0].sdk, self.args.sdk)
        self.assertEqual(self.publications[0].sdk, self.args.sdk)
        self.assertEqual(self.sdks, [self.args.sdk])

    def test_rerun_verifies_binary_assets_without_mutation(self):
        self.existing = True
        output = io.StringIO()
        with redirect_stdout(output):
            auto.automatic_release(self.args)
        self.assertIn('https://github.com/ktnyt/Loupe/releases/tag/' + self.tag, output.getvalue())
        self.assertEqual(self.downloads, [201, 202])
        self.assertEqual(self.sdks, [self.args.sdk])
        self.assertEqual(self.preparations, [])
        self.assertEqual(self.publications, [])

    def test_stale_source_skips_without_network_or_build(self):
        self.main = 'c'*40
        with redirect_stdout(io.StringIO()) as output:
            auto.automatic_release(self.args)
        self.assertIn('Stale source skipped', output.getvalue())
        self.assertEqual(self.calls, [])
        self.assertEqual(self.preparations, [])
        self.assertEqual(self.publications, [])

    def test_unofficial_main_is_rejected_before_build(self):
        self.official = 'c'*40
        with self.assertRaisesRegex(ValueError, 'official'):
            auto.automatic_release(self.args)
        self.assertEqual(self.preparations, [])

    def test_corrupt_asset_rejected(self):
        self.existing = self.corrupt = True
        with self.assertRaisesRegex(ValueError, 'apk_sha256'):
            auto.automatic_release(self.args)
        self.assertEqual(self.publications, [])

    def test_different_source_draft_and_prerelease_rejected(self):
        self.existing = True
        for field, bad in [('target_commitish', 'c'*40), ('draft', True), ('prerelease', True)]:
            with self.subTest(field=field):
                previous = self.release[field]
                self.release[field] = bad
                with self.assertRaisesRegex(ValueError, 'published stable'):
                    auto.automatic_release(self.args)
                self.release[field] = previous
        self.assertEqual(self.downloads, [])
        self.assertEqual(self.preparations, [])
        self.assertEqual(self.publications, [])

    def test_replaced_asset_id_rejected(self):
        self.existing = self.replaced = True
        with self.assertRaisesRegex(ValueError, 'assets changed'):
            auto.automatic_release(self.args)
        self.assertEqual(self.publications, [])

    def test_wrong_expected_version_rejected(self):
        self.existing = True
        self.bundle['version_code'] += 1
        with self.assertRaisesRegex(ValueError, 'expected main'):
            auto.automatic_release(self.args)
        self.assertEqual(self.publications, [])

    def test_missing_asset_rejected_before_download(self):
        self.existing = True
        self.release['assets'].pop()
        with self.assertRaisesRegex(ValueError, 'malformed'):
            auto.automatic_release(self.args)
        self.assertEqual(self.downloads, [])


if __name__ == '__main__':
    unittest.main()
