#!/usr/bin/env python3
"""Exercise version ordering using real, isolated git histories."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('release_version', Path(__file__).with_name('release-version.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class VersionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.repo = Path(self.temp.name) / 'repo'
        self.repo.mkdir()
        self.git('init', '-b', 'main')
        self.git('config', 'user.name', 'Synthetic')
        self.git('config', 'user.email', 'synthetic@example.invalid')
        self.base = self.commit('baseline')

    def tearDown(self):
        self.temp.cleanup()

    def git(self, *parts):
        return release.git(self.repo, *parts)

    def commit(self, title):
        self.git('commit', '--allow-empty', '-m', title)
        return self.git('rev-parse', 'HEAD')

    def version(self, ref='HEAD', base=None, main_ref='main'):
        return release.version(self.repo, ref, self.base if base is None else base, main_ref)

    def test_baseline_and_repeated_main_build_are_stable(self):
        expected = dict(version_code=167, version_name='0.31.3')
        self.assertEqual(self.version(), expected)
        self.assertEqual(self.version(self.base), expected)
        self.commit('next main')
        expected = dict(version_code=168, version_name='0.31.4')
        self.assertEqual(self.version(), expected)
        self.assertEqual(self.version(), expected)

    def test_feature_recovery_never_outruns_eventual_main_merge(self):
        self.git('checkout', '-b', 'feature')
        for i in range(5):
            self.commit('feature ' + str(i))
        recovery = self.version()
        self.assertEqual(recovery, dict(version_code=167, version_name='0.31.3'))
        self.git('checkout', 'main')
        self.git('merge', '--no-ff', 'feature', '-m', 'merge feature')
        merged = self.version()
        self.assertEqual(merged, dict(version_code=168, version_name='0.31.4'))
        self.assertGreater(merged['version_code'], recovery['version_code'])
        # The old feature ref stays at its ancestor generation after the merge.
        self.assertEqual(self.version('feature'), recovery)
        self.commit('next main')
        self.assertEqual(self.version(), dict(version_code=169, version_name='0.31.5'))

    def test_feature_merge_of_newer_main_uses_second_parent_ancestor(self):
        self.git('checkout', '-b', 'feature')
        self.commit('feature work')
        self.git('checkout', 'main')
        self.commit('main progress')
        self.commit('more main progress')
        self.git('checkout', 'feature')
        self.git('merge', '--no-ff', 'main', '-m', 'update feature from main')
        self.commit('more feature work')
        self.assertEqual(self.version(), dict(version_code=169, version_name='0.31.5'))
        self.git('checkout', 'main')
        self.git('merge', '--no-ff', 'feature', '-m', 'merge updated feature')
        self.assertEqual(self.version(), dict(version_code=170, version_name='0.31.6'))

    def test_noncanonical_baseline_reachable_as_second_parent_is_rejected(self):
        self.git('checkout', '--orphan', 'other')
        self.commit('different root')
        self.git('merge', '--no-ff', '--allow-unrelated-histories', 'main', '-m', 'join')
        with self.assertRaisesRegex(ValueError, 'first-parent'):
            self.version(main_ref='other')

    def test_source_without_baseline_ancestor_is_rejected(self):
        self.git('checkout', '--orphan', 'unrelated')
        self.commit('unrelated source')
        with self.assertRaisesRegex(ValueError, 'descend'):
            self.version()

    def test_missing_baseline_and_shallow_history_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'baseline'):
            self.version(base='0' * 40)
        clone = Path(self.temp.name) / 'shallow'
        subprocess.run(['git', 'clone', '--depth', '1', self.repo.as_uri(), str(clone)],
                       check=True, capture_output=True)
        with self.assertRaisesRegex(ValueError, 'full git history'):
            release.version(clone, base=self.base)

    def test_default_uses_origin_main_not_local_main(self):
        self.git('update-ref', 'refs/remotes/origin/main', self.base)
        self.commit('local only work')
        self.assertEqual(release.version(self.repo, base=self.base),
                         dict(version_code=167, version_name='0.31.3'))


if __name__ == '__main__':
    unittest.main()
