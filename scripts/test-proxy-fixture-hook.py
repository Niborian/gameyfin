import importlib.util
import io
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("hook", Path(__file__).with_name("proxy-fixture-hook.py"))
hook = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hook)


class HookTests(unittest.TestCase):
    def test_full_manifest_above_64_kib(self):
        context = {'fixtureManifest': {'sha256': {f'file-{index}': 'a' * 64 for index in range(924)}}}
        encoded = json.dumps(context)
        self.assertGreater(len(encoded), 65536)
        self.assertEqual(hook.read_context(io.StringIO(encoded)), context)

    def test_context_over_one_mib_rejected(self):
        with self.assertRaises(ValueError):
            hook.read_context(io.StringIO(' ' * (1024 * 1024 + 1)))

    def test_only_actual_internal_boolean_accepted(self):
        hook.require_internal({'Internal': True})
        for network in ({}, {'Internal': False}, {'Internal': 'true'}, {'Internal': 1}):
            with self.assertRaises(ValueError):
                hook.require_internal(network)


if __name__ == '__main__':
    unittest.main()
