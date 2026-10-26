import importlib.util
import shlex
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('talkback_check', Path(__file__).with_name('talkback-check.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RestorationTests(unittest.TestCase):
    def test_remote_empty_value_survives_shell_parsing(self):
        parts = ['settings', 'put', 'secure', module.KEYS[0], '']
        self.assertEqual(shlex.split(shlex.join(parts)), parts)

    def test_restore_failure_does_not_skip_remaining_keys_or_verification(self):
        calls = []
        def shell(*parts):
            calls.append(parts)
            if parts[1] == 'put' and parts[3] == module.KEYS[0]:
                raise RuntimeError('injected failure')
            if parts[1] == 'get':
                return module.SERVICE if parts[3] == module.KEYS[0] else '0'
            return ''
        with self.assertRaisesRegex(RuntimeError, 'restoration mismatch'):
            module.restore(shell, dict(zip(module.KEYS, ['', '0'])))
        self.assertIn(('settings', 'put', 'secure', module.KEYS[1], '0'), calls)
        self.assertEqual(sum(call[1] == 'get' for call in calls), 2)

    def test_restore_empty_and_absent_values(self):
        for value in ['', 'null']:
            state = dict(zip(module.KEYS, [module.SERVICE, '1']))
            def shell(*parts):
                if parts[1] == 'get': return state[parts[3]]
                state[parts[3]] = 'null' if parts[1] == 'delete' else parts[4]
                return ''
            module.restore(shell, dict(zip(module.KEYS, [value, '0'])))
            self.assertEqual(state, dict(zip(module.KEYS, [value, '0'])))


if __name__ == '__main__':
    unittest.main()
