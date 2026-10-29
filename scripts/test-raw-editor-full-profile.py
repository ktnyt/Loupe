#!/usr/bin/env python3
"""Pure synthetic parser tests: no adb, files from a device, URI or photos."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('full_profile', Path(__file__).with_name('raw-editor-full-profile.py'))
profile = importlib.util.module_from_spec(spec)
spec.loader.exec_module(profile)


def event(name, ns, ms=0, w=65, h=43):
    return f'I/LoupeRawEdit( 123): {name} ms={ms} width={w} height={h} ns={ns}'


def sample(ticket, ns, latency=1000000):
    return f'I/LoupeRawEdit(123): adjust.input ticket={ticket} ns={ns}\nI/LoupeRawEdit(123): adjust.frame ticket={ticket} input_ns={ns} commit_ns={ns+latency}'


class ParserChecks(unittest.TestCase):
    def test_modes(self):
        self.assertEqual(profile.select_mode(), 'initial-preparation')
        self.assertEqual(profile.select_mode(True), 'after-prepared')
        self.assertEqual(profile.select_mode(False, True), 'require-preparation-overlap')
        with self.assertRaises(ValueError):
            profile.select_mode(True, True)

    def test_intervals_nulls_skipped_and_percentiles(self):
        logs = '\n'.join([event('full.prepare.start', 20000000), event('raw.load.complete', 15000000, 13),
                          event('full.prepare.complete', 50000000, 30), sample(1, 10000000),
                          sample(2, 20000000, 2000000), 'adjust.input ticket=3 ns=30000000',
                          sample(4, 50000000, 4000000), sample(5, 80000000, 6000000),
                          # Invalid, unmatched, or negative-latency commits cannot become samples.
                          'adjust.frame ticket=88 input_ns=30000000 commit_ns=31000000',
                          'adjust.frame ticket=3 input_ns=30000000 commit_ns=29000000'])
        result = profile.summarize(logs, 'initial-preparation', 'synthetic', 12000000, 70000000)
        self.assertEqual(result['all']['input_count'], 3)
        self.assertEqual(result['all']['frame_count'], 2)
        self.assertEqual(result['all']['skipped_input_tickets'], 1)
        self.assertEqual(result['all']['median_ms'], 3)
        self.assertEqual(result['all']['p95_ms'], 4)
        self.assertEqual(result['preparation_overlap']['input_count'], 2)
        self.assertEqual(result['preparation_overlap']['frame_count'], 1)
        self.assertEqual(result['after_prepared']['input_count'], 1)
        self.assertEqual(result['raw.load.complete']['ms'], 13)
        self.assertEqual(result['full.prepare.complete']['width'], 65)
        empty = profile.summarize('', 'initial-preparation', 'synthetic')
        self.assertEqual(empty['status'], 'FAIL')
        self.assertIsNone(empty['all']['median_ms'])
        self.assertIsNone(empty['all']['p95_ms'])
        self.assertIsNone(empty['full.prepare.complete'])
        self.assertIsNone(empty['preparation']['start_ns'])

    def test_overlap_requirement_and_in_progress_boundary(self):
        logs = event('full.prepare.start', 1000) + '\n' + '\n'.join(sample(i, 1000000 * i) for i in range(1, 101))
        result = profile.summarize(logs, 'require-preparation-overlap', 'selected RAW', 0, 200000000)
        self.assertEqual(result['status'], 'PASS')
        self.assertEqual(result['preparation_overlap']['input_count'], 100)
        self.assertFalse(result['preparation']['completion_observed'])
        self.assertIsNone(result['preparation']['complete_ns'])
        self.assertEqual(result['preparation']['observed_end_ns'], 200000000)
        too_short = profile.summarize(logs, 'require-preparation-overlap', 'selected RAW', 0, 50000000)
        self.assertEqual(too_short['status'], 'FAIL')
        no_start = profile.summarize(sample(1, 1000000), 'require-preparation-overlap', 'synthetic', 0, 2000000)
        self.assertEqual(no_start['status'], 'FAIL')

    def test_six_drags_last_input_not_rendered_is_null(self):
        drags = [{'start_ns': i * 100000000, 'end_ns': i * 100000000 + 50000000} for i in range(1, 7)]
        logs = [event('full.prepare.start', 100), event('full.prepare.complete', 50000000, 49), sample(99, 1000000)]
        for i, bounds in enumerate(drags, 1):
            logs.append(sample(i * 10, bounds['start_ns'] + 1000000))
            logs.append(f'adjust.input ticket={i*10+1} ns={bounds["start_ns"]+40000000}')
        result = profile.summarize('\n'.join(logs), 'after-prepared', 'synthetic', 0, 800000000, drags)
        self.assertEqual(result['status'], 'PASS')
        self.assertEqual(result['all']['input_count'], 12)
        self.assertEqual(result['collected_interval']['input_count'], 13)
        self.assertEqual(len(result['drags']), 6)
        self.assertTrue(all(d['final_ms'] is None for d in result['drags']))
        self.assertTrue(all(d['input_count'] == 2 and d['frame_count'] == 1 for d in result['drags']))
        self.assertEqual(result['preparation_overlap']['input_count'], 0)
        self.assertEqual(result['after_prepared']['input_count'], 12)

    def test_after_prepared_rejects_preparation_inputs(self):
        logs = '\n'.join([event('full.prepare.start', 0), event('full.prepare.complete', 5000000), sample(1, 1000000)])
        self.assertEqual(profile.summarize(logs, 'after-prepared', 'synthetic', end_ns=10000000)['status'], 'FAIL')

    def test_memory_and_temperature_anonymous_numbers_only(self):
        self.assertEqual(profile.parse_temperature('  temperature: 327\nvoltage: 4000'), 32.7)
        self.assertIsNone(profile.parse_temperature(''))
        self.assertEqual(profile.parse_kernel_memory('Name: private-name\nVmRSS: 123 kB\nVmHWM: 456 kB\n'), {'rss_kb': 123, 'hwm_kb': 456})
        self.assertEqual(profile.parse_kernel_memory(''), {'rss_kb': None, 'hwm_kb': None})
        values = profile.parse_meminfo('  Native Heap: 123\n  Graphics: 456\n TOTAL PSS: 789 TOTAL RSS: 900\nprivate text')
        self.assertEqual(values, {'native_heap_pss_kb': 123, 'graphics_pss_kb': 456, 'total_pss_kb': 789})
        self.assertTrue(all(v is None for v in profile.parse_meminfo('').values()))

    def test_no_arbitrary_log_content_in_summary(self):
        logs = 'private URI or photo name\n' + sample(1, 1000000)
        import json
        result = json.dumps(profile.summarize(logs, 'initial-preparation', 'synthetic', end_ns=3000000))
        self.assertNotIn('private URI', result)
        self.assertNotIn('photo name', result)


if __name__ == '__main__':
    unittest.main()
