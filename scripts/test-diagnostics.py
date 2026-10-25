import importlib.util
import json
import pathlib
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('diagnostics', pathlib.Path(__file__).with_name('analyze-diagnostics.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

class TimelineTest(unittest.TestCase):
    def test_bind_is_not_frame_and_reselection_is_not_first(self):
        def e(seq, ns, event, request, **extra):
            return dict(session='test', seq=seq, ns=ns*1000000, event=event, request=request, **extra)
        events = [e(1, 0, 'burst.open', 1, count=6),
                  e(2, 10, 'image.request', 2, burst=1, size=1600, format='RAW'),
                  e(3, 2000, 'image.bind', 2),
                  e(4, 2100, 'image.draw', 2),
                  e(5, 2200, 'image.frame_commit', 2),
                  e(6, 3000, 'image.request', 3, burst=1, size=1600, format='RAW'),
                  e(7, 3010, 'image.bind', 3),
                  e(8, 3020, 'image.frame_commit', 3)]
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d)/'events.jsonl'
            p.write_text('\n'.join(json.dumps(e) for e in reversed(events)) + '\n{')
            result = m.analyze([p, p])
        self.assertEqual(result['events'], 8)
        self.assertEqual(result['malformed_lines'], 2)
        self.assertEqual(result['images'][0]['tap_to_first_frame_ms'], 2200)
        self.assertEqual(result['images'][0]['bind_ms'], 1990)
        self.assertIsNone(result['images'][1]['tap_to_first_frame_ms'])

    def test_missing_frame_stays_unknown(self):
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d)/'events.jsonl'
            p.write_text(json.dumps(dict(session='test', seq=1, ns=0, event='image.request',
                                       request=1, burst=2, size=1600, format='JPEG', dropped=3)))
            result = m.analyze([p])
        self.assertIsNone(result['images'][0]['frame_commit_ms'])
        self.assertEqual(result['dropped'], 3)

if __name__ == '__main__': unittest.main()
