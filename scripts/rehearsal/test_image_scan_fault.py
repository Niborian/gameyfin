"""Fault orchestration tests only; no Docker, H2 or application execution."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location("image_scan", Path(__file__).with_name("run-image-scan.py"))
image_scan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(image_scan)


class MissingRootFaultTest(unittest.TestCase):
    def exercise(self, *, completed=0, failed=1, changed=False, request_error=False, timeout=180):
        with tempfile.TemporaryDirectory(prefix="gameyfin-fault-unit-") as leaf:
            root = Path(leaf)
            source = root / "fixture" / "sources" / "lib1"
            source.mkdir(parents=True)
            sentinel = source / "original.bin"
            sentinel.write_bytes(b"unchanged synthetic source")
            reads = 0

            def request(path, body):
                nonlocal reads
                if path.endswith("triggerScan"):
                    self.assertEqual(body, {"scanType": "FULL", "libraryIds": [27001]})
                    self.assertFalse(source.exists())
                    self.assertTrue((root / "fixture" / "fault-lib1").is_dir())
                    if request_error:
                        raise OSError("synthetic request failure")
                    return b"null"
                reads += 1
                return json.dumps([{"id": 1, "variants": [] if changed and reads > 1 else [{"id": 2}]}]).encode()

            def metric(name, text=None):
                if text is None:
                    return 0
                return {"gameyfin_scans_completed_total": completed,
                    "gameyfin_scans_failed_total": failed, "gameyfin_scans_active": 0}[name]

            try:
                return image_scan.verify_missing_root_fault(root, request, lambda: "snapshot", metric, timeout)
            finally:
                self.assertTrue(source.is_dir(), "Failure must restore the hidden source")
                self.assertFalse((root / "fixture" / "fault-lib1").exists())
                self.assertEqual(sentinel.read_bytes(), b"unchanged synthetic source")

    def test_failed_scan_records_unchanged_and_sources_restored(self):
        self.assertTrue(self.exercise()["recordsUnchanged"])

    def test_successful_scan_cannot_be_called_failure_evidence(self):
        with self.assertRaisesRegex(RuntimeError, "exactly one failed"):
            self.exercise(completed=1, failed=0)

    def test_record_mutation_rejected_and_sources_restored(self):
        with self.assertRaisesRegex(RuntimeError, "changed synthetic"):
            self.exercise(changed=True)

    def test_request_failure_restores_sources(self):
        with self.assertRaises(OSError):
            self.exercise(request_error=True)

    def test_unobserved_failure_rejected_and_sources_restored(self):
        with self.assertRaisesRegex(RuntimeError, "not observed"):
            self.exercise(timeout=0)


if __name__ == "__main__":
    unittest.main()
