"""Focused actual scrape-helper tests; no connections, Docker or app execution."""
import socket
import tempfile
import unittest
import urllib.error
import urllib.request
from types import SimpleNamespace
from pathlib import Path
from unittest.mock import patch

from concurrent_scrape_probe import scrape
import run_one_app_probe as runner


class ScrapeTransportTests(unittest.TestCase):
    def exercise(self, chunks, *, kind='filtered', late_eof=False, native_socket=True):
        now = [0.0]
        owned = socket.socket()  # Unconnected, never bound or connected.
        captured = {}
        response = SimpleNamespace(status=200, fp=SimpleNamespace(raw=SimpleNamespace(
            _sock=owned if native_socket else object())))
        remaining = list(chunks)

        class Response:
            status = response.status
            fp = response.fp

            def __enter__(self):
                return self

            def __exit__(self, *args):
                pass

            def read1(self, bound):
                captured.setdefault('readBounds', []).append(bound)
                value = remaining.pop(0) if remaining else b''
                now[0] = 10.1 if late_eof and not value else now[0] + 0.1
                return value

        class Opener:
            def open(self, url, timeout):
                captured.update(url=url, timeout=timeout)
                return Response()

        def build(*handlers):
            captured['handlers'] = handlers
            return Opener()

        try:
            with patch('concurrent_scrape_probe.urllib.request.build_opener', side_effect=build):
                result = scrape('172.22.0.2', kind, clock=lambda: now[0])
            return result, captured
        finally:
            owned.close()

    def test_actual_opener_has_no_proxy_or_cookie_authority_and_refuses_redirect(self):
        row, seen = self.exercise([b'gameyfin_scans_active 1.0\n'])
        self.assertEqual(row['category'], 'observed')
        self.assertEqual(row['active'], 1.0)
        self.assertEqual(seen['timeout'], 10)
        self.assertEqual(seen['url'], 'http://172.22.0.2:8081/actuator/prometheus?includedNames=gameyfin_scans_active')
        self.assertEqual(len(seen['handlers']), 2)
        self.assertIsInstance(seen['handlers'][0], urllib.request.ProxyHandler)
        self.assertEqual(seen['handlers'][0].proxies, {})
        self.assertFalse(any(isinstance(h, urllib.request.HTTPCookieProcessor) for h in seen['handlers']))
        self.assertIsNone(seen['handlers'][1].redirect_request(None, None, 302, 'private', {}, 'https://example.invalid'))

    def test_late_eof_cannot_be_observed(self):
        row, _ = self.exercise([b'gameyfin_scans_active 1.0\n'], late_eof=True)
        self.assertEqual(row['category'], 'timeout')
        self.assertIsNone(row['active'])
        self.assertGreater(row['completed'], 10)

    def test_byte_bounds_and_duplicate_gauge_remain_invalid(self):
        for chunks, kind in [([b'x' * 8193], 'filtered'),
                             ([b'x' * (1024 * 1024 + 1)], 'unfiltered'),
                             ([b'gameyfin_scans_active 1\ngameyfin_scans_active 2\n'], 'filtered')]:
            row, _ = self.exercise(chunks, kind=kind)
            self.assertEqual(row['category'], 'invalid-or-unavailable')
            self.assertIsNone(row['active'])

    def test_missing_owned_socket_is_not_an_observation(self):
        row, _ = self.exercise([b'gameyfin_scans_active 1\n'], native_socket=False)
        self.assertEqual(row['category'], 'invalid-or-unavailable')
        self.assertIsNone(row['active'])

    def test_http_rejection_keeps_only_status_not_private_error_text(self):
        error = urllib.error.HTTPError('http://private.invalid', 302, 'private-detail', {}, None)
        opener = SimpleNamespace(open=lambda *a, **k: (_ for _ in ()).throw(error))
        with patch('concurrent_scrape_probe.urllib.request.build_opener', return_value=opener):
            row = scrape('172.22.0.2', 'filtered')
        self.assertEqual(row['httpStatus'], 302)
        self.assertEqual(row['category'], 'http-rejected')
        self.assertNotIn('private', str(row))

    def test_dangling_pending_link_is_rejected_before_owned_mutations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'must-not-exist'
            output = Path(directory) / 'result.json'
            pending = output.with_suffix('.pending')
            # Model a dangling link without requiring Windows symlink privileges.
            with patch.object(runner, 'hosted_only'), patch.object(runner, 'input_root', return_value=root), \
                 patch.object(Path, 'is_symlink', lambda path: path == pending), \
                 patch.object(runner, 'create') as create:
                with self.assertRaises(ValueError):
                    runner.execute('sha256:' + '1' * 64, output)
                create.assert_not_called()
            self.assertFalse(root.exists())


if __name__ == '__main__':
    unittest.main()
