"""Synthetic primitive prototype tests; no production paths or Docker."""
import errno
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from snapshot_descriptor_prototype import copy_members, fingerprint, open_beneath


@unittest.skipUnless(sys.platform.startswith("linux"), "Linux-only prototype")
class SnapshotPrototypeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.cache = self.root / "cache"
        self.source.mkdir(mode=0o700)
        self.cache.mkdir(mode=0o700)
        (self.source / "selected").write_bytes(b"A" * 131072)
        self.source_fd = os.open(self.source, os.O_RDONLY | os.O_DIRECTORY)
        self.cache_fd = os.open(self.cache, os.O_RDONLY | os.O_DIRECTORY)
        self.expected = fingerprint((self.source / "selected").stat())
        self.members = [("selected", "output", self.expected)]

    def tearDown(self):
        os.close(self.source_fd)
        os.close(self.cache_fd)
        self.temporary.cleanup()

    def copy(self, **kwargs):
        return copy_members(self.source_fd, self.cache_fd, self.members,
                            kwargs.pop("max_bytes", 200000), kwargs.pop("max_files", 1), **kwargs)

    def test_exact_copy(self):
        operation, manifest = self.copy()
        self.assertEqual((self.cache / operation / "output").read_bytes(), b"A" * 131072)
        self.assertEqual(manifest[0][1], 131072)
        self.assertEqual(fingerprint((self.source / "selected").stat()), self.expected)

    def test_cancel_after_first_chunk_cleans_partial(self):
        chunks = []
        with self.assertRaises(InterruptedError):
            self.copy(after_chunk=lambda: chunks.append(True), cancelled=lambda: bool(chunks))
        self.assertEqual(len(chunks), 1)
        self.assertEqual(list(self.cache.iterdir()), [])

    def test_actual_byte_quota_cleans_partial(self):
        with self.assertRaisesRegex(ValueError, "byte quota"):
            self.copy(max_bytes=65536)
        self.assertEqual(list(self.cache.iterdir()), [])

    def test_file_quota_preflight_creates_nothing(self):
        with self.assertRaisesRegex(ValueError, "quota"):
            self.copy(max_files=0)
        self.assertEqual(list(self.cache.iterdir()), [])

    def test_in_place_mutation_refused_and_cleaned(self):
        def mutate():
            (self.source / "selected").write_bytes(b"B" * 131072)
        with self.assertRaisesRegex(ValueError, "source changed|unstable source"):
            self.copy(after_chunk=mutate)
        self.assertEqual(list(self.cache.iterdir()), [])

    def test_a_stat_b_open_a_restore_rejected(self):
        (self.source / "substitute").write_bytes(b"B" * 131072)
        def substitute():
            (self.source / "selected").rename(self.source / "saved")
            (self.source / "substitute").rename(self.source / "selected")
        def restore():
            (self.source / "selected").rename(self.source / "substitute")
            (self.source / "saved").rename(self.source / "selected")
        with self.assertRaisesRegex(ValueError, "opened identity"):
            self.copy(before_open=substitute, after_open=restore)
        self.assertEqual(list(self.cache.iterdir()), [])
        self.assertEqual((self.source / "selected").read_bytes(), b"A" * 131072)

    def test_ancestor_symlink_escape_refused(self):
        outside = self.root / "outside"
        outside.mkdir()
        (outside / "sentinel").write_bytes(b"foreign")
        (self.source / "link").symlink_to(outside, target_is_directory=True)
        with self.assertRaises(OSError):
            open_beneath(self.source_fd, "link/sentinel")

    def test_unsupported_syscall_fails_closed(self):
        class UnsupportedLibc:
            class Syscall:
                def __call__(self, *args):
                    return -1
            syscall = Syscall()
        with patch("snapshot_descriptor_prototype.ctypes.CDLL", return_value=UnsupportedLibc()), \
                patch("snapshot_descriptor_prototype.ctypes.get_errno", return_value=errno.ENOSYS):
            with self.assertRaises(OSError) as failure:
                self.copy()
        self.assertEqual(failure.exception.errno, errno.ENOSYS)
        self.assertEqual(list(self.cache.iterdir()), [])

    def test_cache_path_replacement_does_not_redirect_copy(self):
        moved = self.root / "moved"
        def replace_once():
            if self.cache.exists() and not moved.exists():
                self.cache.rename(moved)
                self.cache.mkdir(mode=0o700)
                (self.cache / "foreign").write_bytes(b"preserve")
        operation, _ = self.copy(after_chunk=replace_once)
        self.assertEqual((moved / operation / "output").read_bytes(), b"A" * 131072)
        self.assertEqual((self.cache / "foreign").read_bytes(), b"preserve")
        self.assertEqual(len(list(self.cache.iterdir())), 1)

    def test_operation_substitution_preserves_foreign_entry_and_marks_uncertain(self):
        chunks = []
        def substitute_operation():
            if chunks:
                return
            chunks.append(True)
            operation = next(self.cache.iterdir())
            operation.rename(self.cache / "relocated-operation")
            operation.mkdir(mode=0o700)
            (operation / "foreign").write_bytes(b"never delete")
        with self.assertRaisesRegex(RuntimeError, "uncertain operation name"):
            self.copy(after_chunk=substitute_operation, cancelled=lambda: bool(chunks))
        foreign_operation = next(p for p in self.cache.iterdir() if p.name.startswith("copy-"))
        self.assertEqual((foreign_operation / "foreign").read_bytes(), b"never delete")
        self.assertEqual(list((self.cache / "relocated-operation").iterdir()), [])

    def test_world_accessible_cache_refused(self):
        self.cache.chmod(0o755)
        with self.assertRaisesRegex(ValueError, "mode 0700"):
            self.copy()
        self.assertEqual(list(self.cache.iterdir()), [])

    def test_source_hardlink_alias_refused(self):
        os.link(self.source / "selected", self.source / "alias")
        self.members = [("selected", "output", fingerprint((self.source / "selected").stat()))]
        with self.assertRaisesRegex(ValueError, "unsupported file"):
            self.copy()
        self.assertEqual(list(self.cache.iterdir()), [])

    def test_output_traversal_refused_before_creation(self):
        self.members = [("selected", "../foreign", self.expected)]
        with self.assertRaisesRegex(ValueError, "unsafe output"):
            self.copy()
        self.assertEqual(list(self.cache.iterdir()), [])

    def test_destination_corruption_refused_and_cleaned(self):
        corrupted = []
        def corrupt_once():
            if not corrupted:
                corrupted.append(True)
                operation = next(self.cache.iterdir())
                with (operation / "output").open("r+b") as destination:
                    destination.write(b"CORRUPTED")
        with self.assertRaisesRegex(ValueError, "destination verification"):
            self.copy(after_chunk=corrupt_once)
        self.assertEqual(list(self.cache.iterdir()), [])

    def test_operation_open_failure_cleans_owned_empty_directory(self):
        actual_open = os.open
        def fail_operation_open(path, *args, **kwargs):
            if isinstance(path, str) and path.startswith("copy-"):
                raise OSError(errno.EIO, "synthetic open failure")
            return actual_open(path, *args, **kwargs)
        with patch("snapshot_descriptor_prototype.os.open", side_effect=fail_operation_open):
            with self.assertRaises(OSError):
                self.copy()
        self.assertEqual(list(self.cache.iterdir()), [])

    def test_mkdir_failure_creates_nothing(self):
        with patch("snapshot_descriptor_prototype.os.mkdir", side_effect=OSError(errno.ENOSPC, "full")):
            with self.assertRaises(OSError):
                self.copy()
        self.assertEqual(list(self.cache.iterdir()), [])

    def test_fsync_failure_cleans_actual_partial_copy(self):
        copied = []
        with patch("snapshot_descriptor_prototype.os.fsync", side_effect=OSError(errno.EIO, "fsync failure")):
            with self.assertRaises(OSError):
                self.copy(after_chunk=lambda: copied.append(True))
        self.assertTrue(copied)
        self.assertEqual(list(self.cache.iterdir()), [])

    def test_open_failure_cleanup_failure_marks_uncertain(self):
        actual_open = os.open
        def fail_operation_open(path, *args, **kwargs):
            if isinstance(path, str) and path.startswith("copy-"):
                raise OSError(errno.EIO, "synthetic open failure")
            return actual_open(path, *args, **kwargs)
        with patch("snapshot_descriptor_prototype.os.open", side_effect=fail_operation_open), \
                patch("snapshot_descriptor_prototype.os.rmdir", side_effect=OSError(errno.EACCES, "blocked")):
            with self.assertRaisesRegex(RuntimeError, "uncertain operation cleanup"):
                self.copy()
        self.assertEqual(len(list(self.cache.iterdir())), 1)


if __name__ == "__main__":
    unittest.main()
