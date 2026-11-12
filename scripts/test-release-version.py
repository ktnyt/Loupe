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

    def test_baseline_and_repeated_build_are_stable(self):
        expected = dict(version_code=167, version_name='0.31.3')
        self.assertEqual(release.version(self.repo, base=self.base), expected)
        self.assertEqual(release.version(self.repo, self.base, self.base), expected)

    def test_main_merge_counts_once_not_feature_commits(self):
        self.git('checkout', '-b', 'feature')
        for i in range(5):
            self.commit('feature ' + str(i))
        self.git('checkout', 'main')
        self.git('merge', '--no-ff', 'feature', '-m', 'merge feature')
        self.assertEqual(release.version(self.repo, base=self.base),
                         dict(version_code=168, version_name='0.31.4'))
        self.commit('next main')
        self.assertEqual(release.version(self.repo, base=self.base),
                         dict(version_code=169, version_name='0.31.5'))

    def test_baseline_reachable_only_as_second_parent_is_rejected(self):
        self.git('checkout', '--orphan', 'other')
        self.commit('different root')
        self.git('merge', '--no-ff', '--allow-unrelated-histories', 'main', '-m', 'join')
        with self.assertRaisesRegex(ValueError, 'first-parent'):
            release.version(self.repo, base=self.base)

    def test_missing_baseline_and_shallow_history_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'baseline'):
            release.version(self.repo, base='0' * 40)
        clone = Path(self.temp.name) / 'shallow'
        subprocess.run(['git', 'clone', '--depth', '1', self.repo.as_uri(), str(clone)],
                       check=True, capture_output=True)
        with self.assertRaisesRegex(ValueError, 'full git history'):
            release.version(clone, base=self.base)


if __name__ == '__main__':
    unittest.main()
