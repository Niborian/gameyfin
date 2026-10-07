"""Explicitly acknowledged, disposable synthetic qB creator capability rehearsal.

Never accepts production paths/endpoints. Does not add or seed a torrent.
"""
import argparse
from contextlib import nullcontext
import base64
import hashlib
import http.cookiejar
import json
import os
import re
import secrets
import select
import shlex
import socket
import socketserver
import subprocess
import tempfile
import threading
import time
import urllib.parse
import urllib.error
import urllib.request
from pathlib import Path

IMAGE = "ghcr.io/hotio/qbittorrent@sha256:91d59985ed65fe3504b405b51e6d586524c2189a6cffd56324101b70fe094bde"


def bdecode(data):
    """Bounded v1 metadata parsing; reject duplicates, malformed and trailing data."""
    if len(data) > 1024 * 1024:
        raise ValueError("Torrent metadata exceeds fixture limit")
    pos, entries = 0, 0

    def parse(depth=0):
        nonlocal pos, entries
        entries += 1
        if depth > 16 or entries > 4096 or pos >= len(data):
            raise ValueError("Malformed or excessive metadata")
        marker = data[pos:pos + 1]
        if marker == b"i":
            end = data.index(b"e", pos)
            raw = data[pos + 1:end]
            if not re.fullmatch(rb"0|-?[1-9][0-9]{0,18}", raw):
                raise ValueError("Invalid integer")
            pos = end + 1
            return int(raw)
        if marker in (b"l", b"d"):
            pos += 1
            result = [] if marker == b"l" else {}
            while pos < len(data) and data[pos:pos + 1] != b"e":
                key = parse(depth + 1)
                if isinstance(result, list):
                    result.append(key)
                else:
                    if not isinstance(key, bytes) or key in result:
                        raise ValueError("Invalid or duplicate key")
                    result[key] = parse(depth + 1)
            if pos >= len(data):
                raise ValueError("Unterminated metadata")
            pos += 1
            return result
        colon = data.index(b":", pos)
        raw = data[pos:colon]
        if not re.fullmatch(rb"0|[1-9][0-9]{0,6}", raw):
            raise ValueError("Invalid byte length")
        length = int(raw)
        pos = colon + 1
        if pos + length > len(data):
            raise ValueError("Truncated bytes")
        result = data[pos:pos + length]
        pos += length
        return result

    result = parse()
    if pos != len(data):
        raise ValueError("Trailing metadata")
    return result


def verify_torrent(data, expected, root_name=None):
    info = bdecode(data)[b"info"]
    name = info.get(b"name")
    if not isinstance(name, bytes) or name in (b"", b".", b"..") or b"/" in name or b"\\" in name:
        raise ValueError("Unsafe torrent root name")
    if root_name is not None and name != root_name.encode():
        raise ValueError("Unexpected torrent root name")
    if info.get(b"private") != 1:
        raise ValueError("Fixture torrent must be private")
    piece_size = info[b"piece length"]
    if not isinstance(piece_size, int) or not 16384 <= piece_size <= 16 * 1024 * 1024:
        raise ValueError("Unexpected piece size")
    ordered, seen = [], set()
    for entry in info[b"files"]:
        parts = entry[b"path"]
        if not parts or any(not isinstance(p, bytes) or p in (b"", b".", b"..") or b"/" in p or b"\\" in p for p in parts):
            raise ValueError("Unsafe metadata path")
        name = "/".join(p.decode("utf-8") for p in parts)
        if name in seen or name not in expected or entry[b"length"] != len(expected[name]):
            raise ValueError("Unexpected selected member")
        seen.add(name)
        ordered.append(expected[name])
    if seen != set(expected):
        raise ValueError("Selected members missing")
    payload = b"".join(ordered)  # Fixture payload is deliberately tiny and fixed.
    pieces = b"".join(hashlib.sha1(payload[i:i + piece_size]).digest() for i in range(0, len(payload), piece_size))
    if info[b"pieces"] != pieces:
        raise ValueError("Torrent pieces differ from exact selected bytes")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--staging-ack", action="store_true", required=True)
    parser.add_argument("--docker-prefix", default="docker")
    args = parser.parse_args()
    command = shlex.split(args.docker_prefix)
    prefix = "gameyfin-qb-creator-" + secrets.token_hex(6)
    network, container = prefix + "-network", prefix + "-app"
    created_network = created_container = False
    relay = None
    temporary = tempfile.TemporaryDirectory(prefix=prefix)

    def docker(*values):
        return subprocess.run([*command, *values], check=True, capture_output=True, text=True, timeout=60).stdout.strip()

    try:
        with nullcontext(Path(temporary.name)) as root:  # Delete only after stopping the owned container.
            config = root / "config" / "qBittorrent" / "config"
            config.mkdir(parents=True)
            password = secrets.token_hex(32)
            salt = secrets.token_bytes(16)
            encoded = base64.b64encode(salt).decode() + ":" + base64.b64encode(hashlib.pbkdf2_hmac("sha512", password.encode(), salt, 100000, 64)).decode()
            (config / "qBittorrent.conf").write_text(
                '[LegalNotice]\nAccepted=true\n[Preferences]\nWebUI\\Address=*\nWebUI\\Username=fixture\n'
                'WebUI\\Password_PBKDF2="@ByteArray(' + encoded + ')"\nWebUI\\LocalHostAuth=true\n'
                'WebUI\\CSRFProtection=true\nWebUI\\SecureCookie=false\nWebUI\\HostHeaderValidation=false\nConnection\\UPnP=false\n'
                '[BitTorrent]\nSession\\DHTEnabled=false\nSession\\PeXEnabled=false\nSession\\LSDEnabled=false\n')
            fixtures = root / "fixtures"
            fixtures.mkdir()
            originals = root / "originals"
            originals.mkdir()
            members = {"Grouped/base-a.bin": b"first synthetic base", "Grouped/base-b.bin": b"second synthetic base", "Grouped/.hidden.bin": b"selected hidden content", "Optional/soundtrack.bin": b"synthetic soundtrack"}
            for name, data in members.items():
                target = originals / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
            source_hashes = {name: hashlib.sha256(data).hexdigest() for name, data in members.items()}
            visible = {name: data for name, data in members.items() if not name.endswith("/.hidden.bin")}
            cases = [{name: data for name, data in visible.items() if name.startswith("Grouped/")}, visible, members]
            for index, selected in enumerate(cases):
                for name, data in selected.items():
                    target = fixtures / str(index) / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(data)
            image_id = docker("image", "inspect", "--format", "{{.Id}}", IMAGE)
            docker("network", "create", "--internal", network)
            created_network = True
            if json.loads(docker("network", "inspect", network))[0]["Internal"] is not True:
                raise ValueError("Fixture network must be internal")
            docker("run", "-d", "--name", container, "--network", network, "--memory", "512m", "--cpus", "1",
                   "--user", str(os.getuid()) + ":" + str(os.getgid()),
                   "--mount", "type=bind,source=" + str(root / "config") + ",target=/config",
                   "--mount", "type=bind,source=" + str(fixtures) + ",target=/fixture,readonly",
                   "-e", "XDG_CONFIG_HOME=/config", "-e", "HOME=/config", "--entrypoint", "/app/qbittorrent-nox-lib2",
                   IMAGE, "--confirm-legal-notice", "--profile=/config", "--webui-port=8080")
            created_container = True
            state = json.loads(docker("inspect", container))[0]
            if state["HostConfig"].get("PortBindings") or len(state["NetworkSettings"]["Networks"]) != 1:
                raise ValueError("Fixture must not publish or attach external networks")
            address = state["NetworkSettings"]["Networks"][network]["IPAddress"]

            class Relay(socketserver.BaseRequestHandler):
                def handle(self):
                    try:
                        target = socket.create_connection((address, 8080), timeout=5)
                    except OSError:  # Normal while the synthetic application starts.
                        return
                    with target:
                        while True:
                            ready, _, _ = select.select([self.request, target], [], [], 15)
                            if not ready:
                                return
                            for source in ready:
                                data = source.recv(65536)
                                if not data:
                                    return
                                (target if source is self.request else self.request).sendall(data)

            relay = socketserver.ThreadingTCPServer(("127.0.0.1", 0), Relay)
            relay.daemon_threads = True
            threading.Thread(target=relay.serve_forever, daemon=True).start()
            base = "http://127.0.0.1:" + str(relay.server_address[1])
            jar = http.cookiejar.CookieJar()
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(jar))

            def request(path, form=None):
                body = None if form is None else urllib.parse.urlencode(form).encode()
                req = urllib.request.Request(base + "/api/v2/" + path, data=body, headers={"Referer": base + "/"})
                with opener.open(req, timeout=10) as response:
                    result = response.read(1024 * 1024 + 1)
                    if len(result) > 1024 * 1024:
                        raise ValueError("Response exceeds limit")
                    return result

            last_login = "unreachable"
            for _ in range(30):
                try:
                    login = request("auth/login", {"username": "fixture", "password": password})
                    last_login = "session established" if list(jar) else "no session cookie"
                    if list(jar):
                        break
                except urllib.error.HTTPError as error:
                    last_login = "HTTP " + str(error.code)
                except (OSError, urllib.error.URLError):
                    last_login = "unreachable"
                time.sleep(1)
            else:
                raise ValueError("Synthetic authenticated API did not become ready: " + last_login)
            version = request("app/version").decode()
            if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", version):
                raise ValueError("Unexpected version response")
            for index, selected in enumerate(cases):
                task = json.loads(request("torrentcreator/addTask", {"sourcePath": "/fixture/" + str(index), "private": "true", "format": "v1", "startSeeding": "false", "ignoreDotfiles": "false", "torrentFilePath": "/config/fixture-" + str(index) + ".torrent"}))["taskID"]
                for _ in range(30):
                    status = json.loads(request("torrentcreator/status?" + urllib.parse.urlencode({"taskID": task})))[0]
                    if status["status"] == "Finished":
                        break
                    if status["status"] == "Failed":
                        raise ValueError("Synthetic creator task failed")
                    time.sleep(1)
                else:
                    raise ValueError("Synthetic creator task exceeded time limit")
                if status.get("format") != "v1":
                    raise ValueError("Creator did not honor v1 format")
                metadata = request("torrentcreator/torrentFile?" + urllib.parse.urlencode({"taskID": task}))
                if index < 2:
                    verify_torrent(metadata, selected, str(index))
                else:
                    try:
                        verify_torrent(metadata, selected, str(index))
                    except ValueError as failure:
                        if str(failure) != "Selected members missing":
                            raise
                    else:
                        raise ValueError("Pinned missing-dotfile behavior changed; review before updating fixture")
            if json.loads(request("torrents/info")) != []:
                raise ValueError("Creation unexpectedly added a torrent")
            if source_hashes != {name: hashlib.sha256((originals / name).read_bytes()).hexdigest() for name in members}:
                raise ValueError("Synthetic source changed")
            for index, selected in enumerate(cases):
                if any((fixtures / str(index) / name).read_bytes() != data for name, data in selected.items()):
                    raise ValueError("Read-only selected snapshot changed")
            report = {"image": IMAGE, "imageId": image_id, "version": version, "exactSelectionCases": 2, "omittedHiddenMemberRejected": True, "originalHashesUnchanged": True, "noTorrentsAdded": True}
    finally:
        if relay:
            relay.shutdown()
            relay.server_close()
        if created_container:
            docker("rm", "-f", container)
        if created_network:
            docker("network", "rm", network)
        temporary.cleanup()
    report["ownedResourcesRemoved"] = True
    print(json.dumps(report, sort_keys=True))


if __name__ == "__main__":
    main()
