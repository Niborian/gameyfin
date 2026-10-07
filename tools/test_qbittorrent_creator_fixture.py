import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('creator', Path(__file__).with_name('qbittorrent-creator-fixture.py'))
creator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(creator)


class MetadataBoundsTest(unittest.TestCase):
    def test_minimal_parse(self):
        self.assertEqual({b'key': [1, b'v']}, creator.bdecode(b'd3:keyli1e1:vee'))

    def test_reject_ambiguity_and_excess(self):
        for value in [b'd1:ai1e1:ai2ee', b'i01e', b'3:ab', b'0:extra', b'l' * 18 + b'e' * 18, b'x' * (1024 * 1024 + 1)]:
            with self.subTest(value=value[:30]), self.assertRaises(ValueError):
                creator.bdecode(value)

    def test_piece_mismatch(self):
        # Exact members with malicious piece data must not be accepted.
        data = b'd4:infod5:filesld6:lengthi1e4:pathl1:aeee4:name1:x12:piece lengthi16384e6:pieces20:xxxxxxxxxxxxxxxxxxxx7:privatei1eee'
        with self.assertRaisesRegex(ValueError, 'pieces differ'):
            creator.verify_torrent(data, {'a': b'x'})


if __name__ == '__main__':
    unittest.main()
