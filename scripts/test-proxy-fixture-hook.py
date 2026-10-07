import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import socket
import threading

spec = importlib.util.spec_from_file_location("hook", Path(__file__).with_name("proxy-fixture-hook.py"))
hook = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hook)


class HookTests(unittest.TestCase):
    def test_forwarder_is_loopback_and_closes(self):
        upstream, peer = socket.socketpair()
        original_connection = socket.create_connection
        def echo():
            with peer:
                peer.sendall(peer.recv(3).upper())
        echo_thread = threading.Thread(target=echo, daemon=True)
        echo_thread.start()
        with patch.object(hook.socket, 'create_connection', return_value=upstream) as connection:
            server, thread = hook.forwarder('192.0.2.1')
            address = server.server_address
            try:
                self.assertEqual(address[0], '127.0.0.1')
                with original_connection(address, timeout=3) as client:
                    client.sendall(b'abc')
                    self.assertEqual(client.recv(3), b'ABC')
                connection.assert_called_once_with(('192.0.2.1', 8080), timeout=10)
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=3)
        echo_thread.join(timeout=3)
        with self.assertRaises(OSError):
            original_connection(address, timeout=1)

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
