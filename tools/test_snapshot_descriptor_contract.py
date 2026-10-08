"""Synthetic Linux descriptor contract tests, NOT a production snapshot copier.

These prove only opened identity and directory anchoring primitives. They do not
implement openat2 traversal, authorization, copy stability, publication or cleanup.
Run on Linux with: python3 -m unittest discover -s tools -p test_snapshot_descriptor_contract.py
"""

import os
import stat
import sys
import tempfile
import unittest
from pathlib import Path


def fingerprint(value):
    return (value.st_dev, value.st_ino, stat.S_IFMT(value.st_mode), value.st_size,
            value.st_mtime_ns, value.st_ctime_ns)


def open_bound_member(directory_fd, name, expected, before_open=lambda: None,
                      after_open=lambda: None):
    """Single-component test primitive; intentionally not a traversal implementation."""
    if not name or name in (".", "..") or "/" in name:
        raise ValueError("not a single relative member")
    before_open()
    descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=directory_fd)
    try:
        after_open()
        actual = os.fstat(descriptor)
        if not stat.S_ISREG(actual.st_mode) or fingerprint(actual) != expected:
            raise ValueError("opened source identity or metadata changed")
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


@unittest.skipUnless(sys.platform.startswith("linux"), "Linux descriptor contract")
class DescriptorContractTest(unittest.TestCase):
    def test_stat_a_open_b_restore_a_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "selected").write_bytes(b"authorized A")
            (root / "substitute").write_bytes(b"substitute B")
            directory_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
            try:
                expected = fingerprint(os.stat("selected", dir_fd=directory_fd,
                                               follow_symlinks=False))

                def substitute():
                    os.rename("selected", "saved", src_dir_fd=directory_fd,
                              dst_dir_fd=directory_fd)
                    os.rename("substitute", "selected", src_dir_fd=directory_fd,
                              dst_dir_fd=directory_fd)

                def restore():
                    os.rename("selected", "substitute", src_dir_fd=directory_fd,
                              dst_dir_fd=directory_fd)
                    os.rename("saved", "selected", src_dir_fd=directory_fd,
                              dst_dir_fd=directory_fd)

                with self.assertRaisesRegex(ValueError, "opened source identity"):
                    open_bound_member(directory_fd, "selected", expected, substitute, restore)
                # The original pathname again points to A; inode alone would look right.
                self.assertEqual(os.stat("selected", dir_fd=directory_fd).st_ino, expected[1])
                self.assertEqual((root / "selected").read_bytes(), b"authorized A")
            finally:
                os.close(directory_fd)

    def test_same_size_in_place_write_changes_descriptor_fingerprint(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "selected").write_bytes(b"AAAA")
            directory_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
            try:
                expected = fingerprint(os.stat("selected", dir_fd=directory_fd))
                descriptor = open_bound_member(directory_fd, "selected", expected)
                try:
                    (root / "selected").write_bytes(b"BBBB")
                    # Force a distinct mtime without depending on filesystem clock precision.
                    os.utime(root / "selected", ns=(expected[4] + 1_000_000,
                                                    expected[4] + 1_000_000))
                    self.assertNotEqual(fingerprint(os.fstat(descriptor)), expected)
                    self.assertEqual(os.read(descriptor, 4), b"BBBB")
                finally:
                    os.close(descriptor)
            finally:
                os.close(directory_fd)

    def test_directory_fd_write_stays_in_original_after_path_replacement(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "owned").mkdir(mode=0o700)
            directory_fd = os.open(root / "owned", os.O_RDONLY | os.O_DIRECTORY)
            try:
                original_identity = os.fstat(directory_fd).st_ino
                (root / "owned").rename(root / "relocated")
                (root / "owned").mkdir(mode=0o700)
                (root / "owned" / "foreign").write_bytes(b"must stay unchanged")
                output = os.open("member", os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                                 0o600, dir_fd=directory_fd)
                try:
                    os.write(output, b"synthetic copy")
                finally:
                    os.close(output)
                self.assertEqual(os.fstat(directory_fd).st_ino, original_identity)
                self.assertEqual((root / "relocated" / "member").read_bytes(), b"synthetic copy")
                self.assertFalse((root / "owned" / "member").exists())
                self.assertEqual((root / "owned" / "foreign").read_bytes(), b"must stay unchanged")
            finally:
                os.close(directory_fd)


if __name__ == "__main__":
    unittest.main()
