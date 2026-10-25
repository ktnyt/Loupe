import contextlib
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('progress', Path(__file__).resolve().parents[1] / 'scripts/issue-progress.py')
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


def event(status, attempt=1, at='2026-10-25T10:00:00+09:00'):
    return dict(run='test', phase='install', attempt=attempt, status=status, at=at)


class ProgressTest(unittest.TestCase):
    def test_duplicate_and_missing_start(self):
        with self.assertRaises(ValueError):
            p.validate([], event('complete'))
        with self.assertRaises(ValueError):
            p.validate([event('start')], event('start'))
        with self.assertRaises(ValueError):
            p.validate([event('start'), event('complete')], event('complete'))

    def test_retry_after_failure(self):
        history = [event('start'), event('fail')]
        p.validate(history, event('start', 2))
        p.validate(history + [event('start', 2)], event('complete', 2))

    def test_report_elapsed_and_unfinished(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertTrue(p.report([event('start'), event('complete', at='2026-10-25T10:01:30+09:00')], 'test'))
        self.assertEqual(json.loads(output.getvalue())['elapsed_seconds'], 90)
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertFalse(p.report([event('start')], 'test'))
            self.assertFalse(p.report([], 'missing'))
            self.assertTrue(p.report([event('skip')], 'test'))

    def test_post_and_read_back(self):
        saved = {}
        def api(path, payload=None):
            if payload:
                saved['body'] = payload['body']
                return {'id': 123}
            if path.endswith('/comments/123'):
                return {'body': saved['body'], 'html_url': 'https://example.test/comment/123'}
            return {'number': 42}
        args = ['progress', 'record', '--issue', '42', '--run', 'test', '--phase', 'install', '--status', 'start', '--note', 'test']
        with patch('sys.argv', args), patch.object(p, 'api', side_effect=api) as mock_api, patch.object(p, 'events', return_value=[]), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(p.main(), 0)
        self.assertEqual(mock_api.call_count, 3)
        self.assertIn('loupe-progress:v1', saved['body'])
        self.assertIn('+09:00', saved['body'])


if __name__ == '__main__':
    unittest.main()
