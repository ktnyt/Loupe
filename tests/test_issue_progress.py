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

    def test_new_phases_record_and_report(self):
        for phase in ('design', 'baseline-test', 'claude-design', 'ui-review',
                      'cold-review', 'security-review', 'delivery'):
            with self.subTest(phase=phase):
                args = ['progress', 'record', '--issue', '18', '--run', 'test',
                        '--phase', phase, '--status', 'start', '--note', 'test', '--dry-run']
                output = io.StringIO()
                with patch('sys.argv', args), contextlib.redirect_stdout(output):
                    self.assertEqual(p.main(), 0)
                line = next(line for line in output.getvalue().splitlines()
                            if line.startswith('<!-- ' + p.MARKER))
                start = json.loads(line[len('<!-- ' + p.MARKER):-4])
                self.assertEqual(start['phase'], phase)
                end = dict(start, status='complete')
                p.validate([start], end)
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    self.assertTrue(p.report([start, end], 'test'))
                self.assertEqual(json.loads(output.getvalue())['status'], 'complete')

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

    def test_then_requires_verified_start(self):
        base=['progress','record','--issue','42','--run','test','--phase','install','--status','start','--note','test']
        for failure in ('request','mismatch',None):
            saved={};order=[]
            def api(path,payload=None):
                if failure=='request':raise ValueError('request failed')
                if payload:saved['body']=payload['body'];return {'id':123}
                if path.endswith('/comments/123'):
                    order.append('verified')
                    return {'body':'mismatch' if failure=='mismatch' else saved['body'],'html_url':'https://example.test/123'}
                return {'number':42}
            def child(command):
                self.assertEqual(order,['verified']);self.assertEqual(command,['fixture-command','--literal']);return type('Result',(),{'returncode':7})()
            with self.subTest(failure=failure),patch('sys.argv',base+['--then','fixture-command','--literal']),patch.object(p,'api',side_effect=api),patch.object(p,'events',return_value=[]),patch.object(p.subprocess,'run',side_effect=child) as run,contextlib.redirect_stdout(io.StringIO()):
                if failure:
                    with self.assertRaises(ValueError):p.main()
                    run.assert_not_called()
                else:self.assertEqual(p.main(),7);run.assert_called_once()

    def test_then_rejects_invalid_or_non_start_records(self):
        for phase,status,extra in [('baseline','start',[]),('install','complete',[]),('install','start',['--dry-run'])]:
            args=['progress','record','--issue','42','--run','test','--phase',phase,'--status',status,'--note','test']+extra+['--then','fixture-command']
            with self.subTest(phase=phase,status=status,extra=extra),patch('sys.argv',args),patch.object(p,'api') as api,patch.object(p.subprocess,'run') as run,contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as error:p.main()
                self.assertEqual(error.exception.code,2);api.assert_not_called();run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
