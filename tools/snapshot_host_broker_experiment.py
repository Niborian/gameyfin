"""Inactive synthetic host/helper bridge. No app endpoints or provider registration.

Trusted constructor configuration supplies roots and a fixed catalog; callers select
catalog IDs only. Linux fork/socketpair is experimental, not deployable JVM integration.
"""
import json
import ctypes
import multiprocessing
import os
import socket
import stat
import struct
import threading
import uuid
from snapshot_descriptor_prototype import OpenHow, copy_members, fingerprint, open_beneath, require_private_cache

MAX_FRAME = 65536


def send_frame(connection, value):
    payload = json.dumps(value, separators=(",", ":")).encode("utf-8")
    if len(payload) > MAX_FRAME:
        raise ValueError("IPC frame quota")
    connection.sendall(struct.pack("!I", len(payload)) + payload)


def receive_frame(connection):
    def exact(count):
        result = bytearray()
        while len(result) < count:
            chunk = connection.recv(count - len(result))
            if not chunk:
                raise EOFError("helper channel closed")
            result.extend(chunk)
        return bytes(result)
    size = struct.unpack("!I", exact(4))[0]
    if not 1 <= size <= MAX_FRAME:
        raise ValueError("IPC frame quota")
    return json.loads(exact(size))


def _open_configured_root(configured, expected):
    # Trusted configured roots may be mount points; prohibit symlinks/magic links
    # but allow crossing mounts only while establishing the configured root.
    if os.uname().machine not in ("x86_64", "aarch64"):
        raise ValueError("unsupported helper architecture")
    slash = os.open("/", os.O_RDONLY | os.O_DIRECTORY)
    try:
        how = OpenHow(os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC, 0, 0x08 | 0x04 | 0x02)
        libc = ctypes.CDLL(None, use_errno=True)
        libc.syscall.restype = ctypes.c_long
        descriptor = libc.syscall(ctypes.c_long(437), ctypes.c_int(slash),
                                  ctypes.c_char_p(os.fsencode(str(configured).lstrip("/"))),
                                  ctypes.byref(how), ctypes.c_size_t(ctypes.sizeof(how)))
        if descriptor < 0:
            raise OSError(ctypes.get_errno(), "configured root refused")
        try:
            actual = os.fstat(descriptor)
        except BaseException:
            os.close(descriptor)
            raise
        if not stat.S_ISDIR(actual.st_mode) or (actual.st_dev, actual.st_ino) != tuple(expected):
            os.close(descriptor)
            raise ValueError("configured root identity changed")
        return descriptor
    finally:
        os.close(slash)


def _helper(connection, parent_connection, configured_roots, configured_cache):
    parent_connection.close()
    roots = {}
    cache = None
    try:
        for configured, expected in configured_roots:
            descriptor = _open_configured_root(configured, expected)
            token = uuid.uuid4().hex
            roots[token] = descriptor
        cache = _open_configured_root(*configured_cache)
        require_private_cache(cache)
        send_frame(connection, {"session": uuid.uuid4().hex, "roots": list(roots)})
        while True:
            request = receive_frame(connection)
            if request == {"command": "close"}:
                send_frame(connection, {"closed": True})
                break
            copying = False
            try:
                if not isinstance(request, dict) or set(request) != {"command", "root", "members", "request"} or request["command"] != "copy":
                    raise ValueError("invalid command")
                request_id = request["request"]
                if not isinstance(request_id, str) or len(request_id) != 32 or any(c not in "0123456789abcdef" for c in request_id):
                    raise ValueError("invalid request identity")
                root_token = request["root"]
                if not isinstance(root_token, str) or len(root_token) != 32:
                    raise ValueError("invalid session root")
                source = roots.get(root_token)
                if source is None:
                    raise ValueError("unknown session root")
                names = request["members"]
                if not isinstance(names, list) or not 1 <= len(names) <= 64 or not all(isinstance(n, str) for n in names):
                    raise ValueError("invalid explicit members")
                members = []
                for index, relative in enumerate(names):
                    descriptor = open_beneath(source, relative)
                    try:
                        captured = fingerprint(os.fstat(descriptor))
                    finally:
                        os.close(descriptor)
                    members.append((relative, "member-%04d.bin" % index, captured))
                copying = True
                operation, manifest = copy_members(source, cache, members, 4 * 1024 * 1024, 64)
                send_frame(connection, {"request": request_id, "operation": operation, "manifest": manifest})
            except (OSError, ValueError, RuntimeError) as error:
                if copying:
                    # Once mutation of the private cache begins, even an ordinary
                    # exception may hide incomplete cleanup. Never reuse this session.
                    break
                # No source names, paths or traceback in protocol responses.
                send_frame(connection, {"request": request.get("request") if isinstance(request, dict) else None,
                                        "refused": type(error).__name__})
    except (OSError, ValueError, EOFError):
        pass
    finally:
        if cache is not None:
            os.close(cache)
        for descriptor in roots.values():
            os.close(descriptor)
        connection.close()


class HostCatalogBroker:
    """Trusted host fixture. Catalog: variant -> (root index, content ID -> members, required IDs).

    Copy callers cannot provide roots, filenames, fingerprints or verified evidence.
    Fixed in-memory catalog is a test stand-in, NOT production authorization/DB integration.
    """
    def __init__(self, configured_roots, configured_cache, catalog):
        self.catalog = {variant: (root, {content: tuple(names) for content, names in members.items()},
                                  frozenset(required)) for variant, (root, members, required) in catalog.items()}
        def bind_configured(path):
            if "\x00" in str(path) or not str(path).startswith("/") or not str(path).strip("/"):
                raise ValueError("invalid trusted configured root")
            value = os.stat(path, follow_symlinks=False)
            if not stat.S_ISDIR(value.st_mode) or "\x00" in str(path) or not str(path).startswith("/"):
                raise ValueError("invalid trusted configured root")
            return str(path), (value.st_dev, value.st_ino)
        roots = tuple(bind_configured(p) for p in configured_roots)
        cache = bind_configured(configured_cache)
        self.parent, child = socket.socketpair()
        self.parent.settimeout(5)
        self.process = multiprocessing.get_context("fork").Process(
            target=_helper, args=(child, self.parent, roots, cache))
        try:
            self.process.start()
        except BaseException:
            self.parent.close()
            child.close()
            raise
        child.close()
        self.closed = False
        self._request_lock = threading.Lock()
        try:
            hello = receive_frame(self.parent)
            self.session = hello["session"]
            self._root_tokens = tuple(hello["roots"])
        except BaseException:
            self._abort()
            raise

    def acquire_selection(self, variant_id, selected_content_ids):
        with self._request_lock:
            return self._acquire_selection(variant_id, selected_content_ids)

    def _acquire_selection(self, variant_id, selected_content_ids):
        if self.closed:
            raise ValueError("session unavailable")
        if not isinstance(variant_id, int) or isinstance(variant_id, bool) or variant_id not in self.catalog:
            raise ValueError("unknown authorized variant")
        if (not isinstance(selected_content_ids, (list, tuple, set, frozenset))
                or len(selected_content_ids) > 64
                or not all(isinstance(value, int) and not isinstance(value, bool) for value in selected_content_ids)):
            raise ValueError("invalid selected content IDs")
        root, contents, required = self.catalog[variant_id]
        selected = frozenset(selected_content_ids)
        if not selected.issubset(contents) or not required.issubset(contents):
            raise ValueError("content membership mismatch")
        names = [name for content in sorted(selected | required) for name in contents[content]]
        if len(names) != len(set(names)):
            raise ValueError("duplicate selected member")
        try:
            request_id = uuid.uuid4().hex
            send_frame(self.parent, {"command": "copy", "root": self._root_tokens[root], "members": names,
                                     "request": request_id})
            result = receive_frame(self.parent)
            if not isinstance(result, dict) or result.get("request") != request_id:
                raise ValueError("stale or malformed helper response")
        except BaseException:
            self._abort()
            raise RuntimeError("uncertain helper operation; session discarded") from None
        if "refused" in result:
            raise ValueError("helper refused selection")
        return result

    def _abort(self):
        self.closed = True
        self.parent.close()
        if self.process.is_alive():
            self.process.terminate()
        self.process.join(timeout=2)
        # An uninterruptible process may remain. No restart/retry/deletion is attempted.

    def close(self):
        with self._request_lock:
            self._close()

    def _close(self):
        if not self.closed:
            try:
                send_frame(self.parent, {"command": "close"})
                if receive_frame(self.parent) != {"closed": True}:
                    raise ValueError("invalid close response")
            finally:
                self._abort()
