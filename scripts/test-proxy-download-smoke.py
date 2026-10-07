import hashlib
import importlib.util
import io
from pathlib import Path
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("smoke", Path(__file__).with_name("proxy-download-smoke.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class SmokeTests(unittest.TestCase):
    def archive(self, members):
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            for name, body in members:
                archive.writestr(name, body)
        return output.getvalue()

    def test_grouped_and_optional_members(self):
        for members in ((('base-a', b'a'), ('base-b', b'b')), (('base-a', b'a'), ('base-b', b'b'), ('patch', b'p'))):
            smoke.verify_archive(self.archive(members), {name: hashlib.sha256(body).hexdigest() for name, body in members})

    def test_extra_or_duplicate_members_rejected(self):
        expected = {'base-a': hashlib.sha256(b'a').hexdigest()}
        for members in ((('base-a', b'a'), ('extra', b'e')), (('base-a', b'a'), ('base-a', b'a'))):
            with self.assertRaises(ValueError):
                smoke.verify_archive(self.archive(members), expected)

    def test_hash_mismatch_rejected(self):
        with self.assertRaises(ValueError):
            smoke.verify_archive(self.archive((('base', b'a'),)), {'base': '0' * 64})

    def test_expanded_size_bounded(self):
        with self.assertRaises(ValueError):
            smoke.verify_archive(self.archive((('base', b'a' * (smoke.LIMIT + 1)),)), {'base': '0' * 64})

    def test_cross_origin_and_other_endpoints_rejected(self):
        for path in ('https://example.invalid/download/1', '//example.invalid/download/1', '/connect/OtherEndpoint/write', '/download/%2e%2e/other'):
            with self.assertRaises(ValueError):
                smoke.local_path(path)

    def test_response_size_bounded(self):
        with self.assertRaises(ValueError):
            smoke.bounded_read(io.BytesIO(b'a' * (smoke.LIMIT + 1)))


if __name__ == '__main__':
    unittest.main()
