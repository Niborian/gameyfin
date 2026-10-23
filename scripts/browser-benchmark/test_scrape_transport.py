"""Focused actual scrape-helper tests; no connections, Docker or app execution."""
import socket
import http.client
import io
import tempfile
import unittest
import urllib.error
import urllib.request
from types import SimpleNamespace
from pathlib import Path
from unittest.mock import patch

from concurrent_scrape_probe import scrape,safe_probe,safe_telemetry,scan_snapshot_classification
import run_one_app_probe as runner


class ScrapeTransportTests(unittest.TestCase):
    def exercise(self, chunks, *, kind='filtered', late_eof=False, native_socket=True, close_on_content=False):
        now = [0.0]
        owned = socket.socket()  # Unconnected, never bound or connected.
        captured = {}
        response = SimpleNamespace(status=200, fp=SimpleNamespace(raw=SimpleNamespace(
            _sock=owned if native_socket else object())))
        remaining = list(chunks)

        class Response:
            status = response.status
            fp = response.fp
            closed = False

            def isclosed(self):return self.closed

            def __enter__(self):
                return self

            def __exit__(self, *args):
                pass

            def read1(self, bound):
                captured.setdefault('readBounds', []).append(bound)
                value = remaining.pop(0) if remaining else b''
                if close_on_content and value and not remaining:
                    self.closed=True;self.fp=None;owned.close()
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

    def test_content_length_completion_closes_socket_before_redundant_eof_read(self):
        row, seen = self.exercise([b'gameyfin_scans_active 1.0\n'],close_on_content=True)
        self.assertEqual(row['category'],'observed')
        self.assertEqual(row['active'],1.0)
        self.assertEqual(len(seen['readBounds']),1)

    def test_real_httpresponse_closes_native_socket_at_content_length(self):
        owned=socket.socket();body=b'gameyfin_scans_active 1.0\n'
        class Stream(io.BytesIO):
            raw=SimpleNamespace(_sock=owned)
            def close(self):owned.close();super().close()
        stream=Stream(b'HTTP/1.1 200 OK\r\nContent-Length: '+str(len(body)).encode()+b'\r\n\r\n'+body)
        response=http.client.HTTPResponse(SimpleNamespace(makefile=lambda *args:stream));response.begin()
        opener=SimpleNamespace(open=lambda *args,**kwargs:response)
        try:
            with patch('concurrent_scrape_probe.urllib.request.build_opener',return_value=opener):
                row=scrape('172.22.0.2','filtered')
            self.assertTrue(response.isclosed());self.assertEqual(owned.fileno(),-1)
            self.assertEqual(row['category'],'observed');self.assertEqual(row['active'],1.0)
        finally:response.close();owned.close()

    def test_real_httpresponse_rejects_truncated_declared_length_and_accepts_chunked_eof(self):
        body=b'gameyfin_scans_active 1.0\n'
        cases=[(b'Content-Length: '+str(len(body)+10).encode()+b'\r\n',body,'invalid-or-unavailable'),
               (b'Transfer-Encoding: chunked\r\n',hex(len(body))[2:].encode()+b'\r\n'+body+b'\r\n0\r\n\r\n','observed')]
        for headers,payload,expected in cases:
            with self.subTest(expected=expected):
                owned=socket.socket()
                class Stream(io.BytesIO):
                    raw=SimpleNamespace(_sock=owned)
                    def close(self):owned.close();super().close()
                stream=Stream(b'HTTP/1.1 200 OK\r\n'+headers+b'\r\n'+payload)
                response=http.client.HTTPResponse(SimpleNamespace(makefile=lambda *args:stream));response.begin()
                try:
                    with patch('concurrent_scrape_probe.urllib.request.build_opener',return_value=SimpleNamespace(open=lambda *a,**k:response)):
                        row=scrape('172.22.0.2','filtered')
                    self.assertEqual(row['category'],expected)
                    self.assertEqual(row['active'],1.0 if expected=='observed'else None)
                finally:response.close();owned.close()

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
        self.assertEqual(row['stage'], 'socket')

    def test_singleton_metric_grammar_and_safe_failure_stages(self):
        for body in (b'gameyfin_scans_active 0.0\r\n', b'gameyfin_scans_active{scope="scan"} 1.0E0\n'):
            row, _ = self.exercise([body])
            self.assertEqual(row['category'], 'observed')
            self.assertEqual(row['activeGaugeLineCount'], 1)
        for body, stage, count in ((b'# HELP unrelated\n', 'gauge-selection', 0),
                                   (b'gameyfin_scans_active 5.0\n', 'gauge-value', 1),
                                   (b'gameyfin_scans_active 1.5\n', 'gauge-value', 1),
                                   (b'gameyfin_scans_active NaN\n', 'gauge-value', 1),
                                   (b'gameyfin_scans_active 0\ngameyfin_scans_active{x="y"} 1\n', 'gauge-selection', 2)):
            row, _ = self.exercise([body])
            self.assertEqual(row['category'], 'invalid-or-unavailable')
            self.assertIsNone(row['active'])
            self.assertEqual(row['stage'], stage)
            self.assertEqual(row['activeGaugeLineCount'], count)

    def test_unfiltered_reuses_body_for_safe_jvm_and_scan_projection(self):
        body=b'gameyfin_scans_active 0\ngameyfin_scans_draining 0\ngameyfin_scans_completed_total{type="full"} 4\ngameyfin_scans_failed_total{type="full"} 0\njvm_memory_used_bytes{area="heap"} 123\njvm_gc_pause_seconds_count 2\njvm_gc_pause_seconds_sum 0.01\n'
        row,seen=self.exercise([body],kind='unfiltered')
        self.assertEqual(row['telemetryStatus'],'observed');self.assertEqual(row['telemetry']['completed'],4)
        self.assertEqual(row['telemetry']['heapBytes'],123)
        projected=safe_probe({**row,'telemetry':{**row['telemetry'],'private':'never'}},'unfiltered')
        self.assertNotIn('private',str(projected));self.assertEqual(projected['telemetryStatus'],'observed')
        row,_=self.exercise([b'gameyfin_scans_active 0\n'],kind='unfiltered')
        self.assertEqual(row['category'],'observed');self.assertEqual(row['telemetryStatus'],'unavailable')
        for invalid in(float('nan'),float('inf'),-1):
            with self.assertRaises(ValueError):safe_telemetry({**projected['telemetry'],'heapBytes':invalid})
        before={**projected['telemetry'],'completed':0}
        self.assertEqual(scan_snapshot_classification(before,[projected]),'four-completions-quiescent-at-snapshot')
        pending={**projected,'telemetry':before}
        self.assertEqual(scan_snapshot_classification(before,[pending]),'completion-not-yet-proven')
        self.assertEqual(scan_snapshot_classification(before,[]),'unknown')
        for change in({'category':'timeout'},{'httpStatus':503},{'active':1},{'telemetry':{'private':'forged'}}):
            self.assertEqual(scan_snapshot_classification(before,[{**projected,**change}]),'unknown')

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
