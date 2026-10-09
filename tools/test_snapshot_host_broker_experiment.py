import os
import socket
import struct
import sys
import tempfile
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch
from snapshot_host_broker_experiment import HostCatalogBroker, MAX_FRAME, _open_configured_root, receive_frame, send_frame


@unittest.skipUnless(sys.platform.startswith("linux"), "Linux fork/helper experiment")
class HostBrokerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.cache = self.root / "cache"
        self.source.mkdir(mode=0o700)
        self.cache.mkdir(mode=0o700)
        (self.source / "required").write_bytes(b"required source")
        (self.source / "optional").write_bytes(b"optional source")
        self.broker = HostCatalogBroker([self.source], self.cache,
                                       {22: (0, {1: ["required"], 2: ["optional"]}, {1})})

    def tearDown(self):
        self.broker.close()
        self.assertFalse(self.broker.process.is_alive())
        self.temporary.cleanup()

    def test_required_selection_excludes_optional_in_real_helper_process(self):
        result = self.broker.acquire_selection(22, [])
        operation = self.cache / result["operation"]
        self.assertEqual(list(p.name for p in operation.iterdir()), ["member-0000.bin"])
        self.assertEqual((operation / "member-0000.bin").read_bytes(), b"required source")

    def test_optional_and_required_exact_bytes(self):
        result = self.broker.acquire_selection(22, [2])
        operation = self.cache / result["operation"]
        self.assertEqual((operation / "member-0000.bin").read_bytes(), b"required source")
        self.assertEqual((operation / "member-0001.bin").read_bytes(), b"optional source")
        self.assertEqual((self.source / "required").read_bytes(), b"required source")

    def test_unknown_variant_or_content_cannot_supply_paths(self):
        for variant, contents in [(23, []), (22, ["../outside"]), (22, [999])]:
            with self.assertRaises(ValueError):
                self.broker.acquire_selection(variant, contents)
        self.assertEqual(list(self.cache.iterdir()), [])

    def test_root_path_replacement_remains_bound_to_original_descriptor(self):
        self.source.rename(self.root / "original")
        self.source.mkdir(mode=0o700)
        (self.source / "required").write_bytes(b"foreign replacement")
        result = self.broker.acquire_selection(22, [])
        self.assertEqual((self.cache / result["operation"] / "member-0000.bin").read_bytes(), b"required source")
        self.assertEqual((self.source / "required").read_bytes(), b"foreign replacement")

    def test_wrong_and_prior_session_root_tokens_fail_closed(self):
        for root in ["not-a-token", "0" * 32]:
            send_frame(self.broker.parent, {"command": "copy", "root": root, "members": ["required"],
                                             "request": uuid.uuid4().hex})
            self.assertIn("refused", receive_frame(self.broker.parent))
        self.assertEqual(list(self.cache.iterdir()), [])

    def test_symlink_member_cannot_escape_held_root(self):
        outside = self.root / "outside"
        outside.write_bytes(b"foreign")
        (self.source / "required").unlink()
        (self.source / "required").symlink_to(outside)
        with self.assertRaises(ValueError):
            self.broker.acquire_selection(22, [])
        self.assertEqual(list(self.cache.iterdir()), [])
        self.assertEqual(outside.read_bytes(), b"foreign")

    def test_descriptor_root_identity_substitution_refused(self):
        expected = self.source.stat()
        self.source.rename(self.root / "original")
        self.source.mkdir(mode=0o700)
        with self.assertRaisesRegex(ValueError, "identity changed"):
            _open_configured_root(str(self.source), (expected.st_dev, expected.st_ino))

    def test_oversize_ipc_header_closes_helper_and_no_retry(self):
        self.broker.parent.sendall(struct.pack("!I", MAX_FRAME + 1))
        with self.assertRaises(RuntimeError):
            self.broker.acquire_selection(22, [])
        self.assertTrue(self.broker.closed)
        with self.assertRaises(ValueError):
            self.broker.acquire_selection(22, [])

    def test_dead_helper_discards_session_and_refuses_retry(self):
        self.broker.process.terminate()
        self.broker.process.join(timeout=2)
        with self.assertRaises(RuntimeError):
            self.broker.acquire_selection(22, [])
        self.assertTrue(self.broker.closed)
        with self.assertRaises(ValueError):
            self.broker.acquire_selection(22, [])

    def test_sender_frame_budget_rejects_before_send(self):
        left, right = socket.socketpair()
        try:
            with self.assertRaises(ValueError):
                send_frame(left, {"oversized": "x" * (MAX_FRAME + 1)})
        finally:
            left.close()
            right.close()

    def test_concurrent_callers_are_serialized_and_responses_bound(self):
        with ThreadPoolExecutor(max_workers=2) as executor:
            first = executor.submit(self.broker.acquire_selection, 22, [])
            second = executor.submit(self.broker.acquire_selection, 22, [2])
            required_only = first.result(timeout=5)
            optional = second.result(timeout=5)
        self.assertEqual(len(required_only["manifest"]), 1)
        self.assertEqual(len(optional["manifest"]), 2)
        self.assertNotEqual(required_only["request"], optional["request"])

    def test_root_fstat_failure_closes_new_descriptors(self):
        expected = self.source.stat()
        before = len(os.listdir("/proc/self/fd"))
        with patch("snapshot_host_broker_experiment.os.fstat", side_effect=OSError("synthetic fstat failure")):
            with self.assertRaises(OSError):
                _open_configured_root(str(self.source), (expected.st_dev, expected.st_ino))
        self.assertEqual(len(os.listdir("/proc/self/fd")), before)

    def test_process_start_failure_closes_both_socket_descriptors(self):
        before = len(os.listdir("/proc/self/fd"))
        with patch("multiprocessing.process.BaseProcess.start", side_effect=OSError("synthetic fork failure")):
            with self.assertRaises(OSError):
                HostCatalogBroker([self.source], self.cache, {})
        self.assertEqual(len(os.listdir("/proc/self/fd")), before)

    def test_malformed_root_and_member_types_are_refused_without_poisoning_channel(self):
        for root, members in [([], ["required"]), ({}, ["required"]),
                              (self.broker._root_tokens[0], "required"),
                              (self.broker._root_tokens[0], [42])]:
            send_frame(self.broker.parent, {"command": "copy", "root": root, "members": members,
                                             "request": uuid.uuid4().hex})
            self.assertIn("refused", receive_frame(self.broker.parent))
        self.assertEqual(len(self.broker.acquire_selection(22, [])["manifest"]), 1)

    def test_host_selected_id_budget_and_types_refused(self):
        for members in [[True], [[1]], list(range(65)), iter([1])]:
            with self.assertRaises(ValueError):
                self.broker.acquire_selection(22, members)
        self.assertEqual(list(self.cache.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
