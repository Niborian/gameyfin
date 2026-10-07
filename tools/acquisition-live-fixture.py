"""Disposable real provider acceptance. Intentionally CI-only, no operator endpoints/mounts."""
import base64
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import select
import shutil
import socket
import socketserver
import subprocess
import tempfile
import threading
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

if os.environ.get("GITHUB_ACTIONS") != "true":
    raise SystemExit("This fixture is restricted to disposable GitHub Actions runners")

QB = "linuxserver/qbittorrent@sha256:b522f9f4b769f8f36d49d22d5eb6a92e9aa18904c6a1830b1439df511ec21983"
PROWLARR = "linuxserver/prowlarr@sha256:f9151e5bc1025c6d0a630d503210cdcb6bb55a7cc098562609d96a408d838902"
prefix = "gameyfin-acquisition-" + secrets.token_hex(6)
root = Path(tempfile.mkdtemp(prefix=prefix))
key, password = secrets.token_hex(24), secrets.token_hex(24)
for secret in (key, password):
    print("::add-mask::" + secret, flush=True)
qbconfig = root / "qb" / "qBittorrent"
qbconfig.mkdir(parents=True)
salt = secrets.token_bytes(16)
encoded = base64.b64encode(salt).decode() + ":" + base64.b64encode(hashlib.pbkdf2_hmac("sha512", password.encode(), salt, 100000, 64)).decode()
(qbconfig / "qBittorrent.conf").write_text(
    '[Preferences]\nWebUI\\Username=fixture\nWebUI\\Password_PBKDF2="@ByteArray(' + encoded + ')"\n'
    'WebUI\\LocalHostAuth=true\nWebUI\\CSRFProtection=true\nWebUI\\HostHeaderValidation=false\n'
    'Connection\\PortRangeMin=39694\n[BitTorrent]\nSession\\DHTEnabled=false\nSession\\PeXEnabled=false\nSession\\LSDEnabled=false\n'
)
prowlarr = root / "prowlarr"
prowlarr.mkdir()
(prowlarr / "config.xml").write_text('<Config><BindAddress>*</BindAddress><Port>9696</Port><ApiKey>' + key + '</ApiKey><AuthenticationMethod>External</AuthenticationMethod><AuthenticationRequired>DisabledForLocalAddresses</AuthenticationRequired></Config>')

class Fixture(BaseHTTPRequestHandler):
    def do_GET(self):
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
        if query.get("t") == ["caps"]:
            body = '<caps><server title="Lawful synthetic fixture"/><limits max="100" default="100"/><searching><search available="yes" supportedParams="q"/><tv-search available="no"/><movie-search available="no"/></searching><categories><category id="1000" name="Console"/><category id="4000" name="PC"/></categories></caps>'
        else:
            # Invented identity: no tracker, seed, depot, public indexer or payload exists.
            body = '<rss version="2.0" xmlns:torznab="http://torznab.com/schemas/2015/feed"><channel><title>Fixture</title><item><title>Lawful synthetic fixture</title><guid>magnet:?xt=urn:btih:' + 'a' * 40 + '</guid><link>magnet:?xt=urn:btih:' + 'a' * 40 + '</link><pubDate>Wed, 07 Oct 2026 12:00:00 GMT</pubDate><category>4000</category><size>1</size><torznab:attr name="category" value="4000"/><torznab:attr name="seeders" value="0"/><torznab:attr name="magneturl" value="magnet:?xt=urn:btih:' + 'a' * 40 + '"/></item></channel></rss>'
        data = body.encode()
        self.send_response(200); self.send_header("Content-Type", "application/rss+xml"); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
    def log_message(self, *_):
        pass

server = ThreadingHTTPServer(("0.0.0.0", 39697), Fixture)
threading.Thread(target=server.serve_forever, daemon=True).start()
containers = []
forwarders = []
network_created = False

def forward_loopback(port, address, target_port):
    class Relay(socketserver.BaseRequestHandler):
        def handle(self):
            with socket.create_connection((address, target_port), timeout=10) as target:
                self.request.settimeout(30); target.settimeout(30)
                while True:
                    readable, _, _ = select.select([self.request, target], [], [], 30)
                    if not readable: return
                    for source in readable:
                        data = source.recv(65536)
                        if not data: return
                        (target if source is self.request else self.request).sendall(data)
    relay = socketserver.ThreadingTCPServer(("127.0.0.1", port), Relay)
    relay.daemon_threads = True
    threading.Thread(target=relay.serve_forever, daemon=True).start()
    forwarders.append(relay)

def docker(*args):
    return subprocess.run(["docker", *args], check=True, capture_output=True, text=True).stdout.strip()

def request(url, body=None, headers=None, opener=None, timeout=10):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, headers=headers or {})
    with (opener or urllib.request.build_opener()).open(req, timeout=timeout) as response:
        result = response.read().decode()
        return json.loads(result) if result and result[0] in "[{" else result

try:
    docker("network", "create", "--internal", prefix)
    network_created = True
    gateway = json.loads(docker("network", "inspect", prefix))[0]["IPAM"]["Config"][0]["Gateway"]
    for role, image, port, target, config in (("qb", QB, "39695:8080", "/downloads", root / "qb"), ("prowlarr", PROWLARR, "39696:9696", None, prowlarr)):
        name = prefix + "-" + role
        args = ["run", "-d", "--name", name, "--network", prefix, "--add-host", "host.docker.internal:" + gateway, "--memory", "512m", "--cpus", "1", "-e", "PUID=" + str(os.getuid()), "-e", "PGID=" + str(os.getgid()), "-v", str(config) + ":/config"]
        if target:
            downloads = root / "downloads"; downloads.mkdir()
            args += ["-v", str(downloads) + ":" + target]
        container_id = docker(*args, image)
        if not container_id or any(c not in "0123456789abcdef" for c in container_id):
            raise RuntimeError("Unexpected created container identity")
        containers.append(container_id)
        inspection = json.loads(docker("inspect", container_id))[0]
        address = inspection["NetworkSettings"]["Networks"][prefix]["IPAddress"]
        local_port, container_port = map(int, port.split(":"))
        # Internal networks intentionally have no published ports. The runner can
        # reach its own bridge; only exact fixture targets get a loopback relay.
        forward_loopback(local_port, address, container_port)
    api_headers = {"X-Api-Key": key, "Content-Type": "application/json"}
    readiness_failure = None
    for _ in range(90):
        try:
            request("http://127.0.0.1:39696/api/v1/system/status", headers=api_headers)
            break
        except Exception as failure:
            readiness_failure = str(failure)
            time.sleep(2)
    else:
        print("Prowlarr readiness failure: " + str(readiness_failure).replace(key, "[masked]").replace(password, "[masked]"))
        inspection = json.loads(docker("inspect", containers[1]))[0]
        print("Own Prowlarr state/port bindings:", inspection["State"]["Status"], inspection["NetworkSettings"]["Ports"])
        diagnostic = docker("logs", "--tail", "80", containers[1])
        print(diagnostic.replace(key, "[masked]").replace(password, "[masked]"))
        raise RuntimeError("Prowlarr did not become ready")
    # First schema load may wait for the vendor definition-update attempt to
    # fail on the deliberately no-egress network. Bound setup, not app calls.
    schemas = request("http://127.0.0.1:39696/api/v1/indexer/schema", headers=api_headers, timeout=90)
    indexer = next(x for x in schemas if x["implementation"] == "Torznab")
    indexer.update(name="Lawful synthetic fixture", enable=True, priority=25)
    for field in indexer["fields"]:
        if field["name"] == "baseUrl": field["value"] = "http://host.docker.internal:39697"
        if field["name"] == "apiPath": field["value"] = "/api"
        if field["name"] == "apiKey": field["value"] = "synthetic-only"
    created = request("http://127.0.0.1:39696/api/v1/indexer", indexer, api_headers)
    cookies = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    origin = "http://127.0.0.1:39695"
    for _ in range(60):
        try:
            form = urllib.parse.urlencode({"username": "fixture", "password": password}).encode()
            req = urllib.request.Request(origin + "/api/v2/auth/login", data=form, headers={"Referer": origin})
            with opener.open(req, timeout=10) as response:
                if response.read().decode().strip() != "Ok.": raise RuntimeError("Fixture authentication refused")
            break
        except Exception:
            time.sleep(2)
    else:
        raise RuntimeError("qBittorrent did not become ready")
    form = urllib.parse.urlencode({"category": "fixture-acquisition", "savePath": "/downloads/fixture-acquisition"}).encode()
    opener.open(urllib.request.Request(origin + "/api/v2/torrents/createCategory", data=form, headers={"Referer": origin}), timeout=10).close()
    env = dict(os.environ, GAMEYFIN_ACQUISITION_FIXTURE="true", FIXTURE_PROWLARR_KEY=key, FIXTURE_QB_PASSWORD=password, FIXTURE_INDEXER_ID=str(created["id"]))
    subprocess.run(["./gradlew", ":app:test", "--tests", "*AcquisitionLiveProviderTest", "--no-daemon", "--console=plain"], check=True, env=env)
    report = ET.parse("app/build/test-results/test/TEST-org.gameyfin.app.requests.AcquisitionLiveProviderTest.xml").getroot()
    if int(report.get("tests", "0")) != 1 or any(int(report.get(x, "0")) for x in ("skipped", "failures", "errors")):
        raise RuntimeError("Real-pair test must execute without skips, failures or errors")
    # Restart qB without changing its isolated state; same persisted identity must survive.
    docker("restart", containers[0])
    for _ in range(60):
        try:
            # Old SID may expire; authenticate again after restart.
            login = urllib.parse.urlencode({"username": "fixture", "password": password}).encode()
            opener.open(urllib.request.Request(origin + "/api/v2/auth/login", data=login, headers={"Referer": origin}), timeout=10).close()
            torrents = request(origin + "/api/v2/torrents/info", opener=opener)
            if len(torrents) == 1 and torrents[0]["hash"] == "a" * 40 and torrents[0]["state"] == "stoppedDL": break
        except Exception:
            pass
        time.sleep(2)
    else:
        raise RuntimeError("Owned stopped identity did not survive isolated client restart")
    if any((root / "downloads").rglob("*.*")):
        raise RuntimeError("Synthetic magnet unexpectedly created payload files")
    print("Real isolated qB5/Prowlarr scoped search/add/stop/resume/restart passed; no public provider or payload")
finally:
    for relay in forwarders:
        relay.shutdown(); relay.server_close()
    cleanup_failures = []
    for container_id in containers:
        removed = subprocess.run(["docker", "rm", "-f", container_id], check=False, capture_output=True)
        present = subprocess.run(["docker", "inspect", container_id], check=False, capture_output=True)
        if removed.returncode != 0 or present.returncode == 0:
            cleanup_failures.append("created container removal was not confirmed: " + container_id)
    if network_created:
        removed = subprocess.run(["docker", "network", "rm", prefix], check=False, capture_output=True)
        present = subprocess.run(["docker", "network", "inspect", prefix], check=False, capture_output=True)
        if removed.returncode != 0 or present.returncode == 0:
            cleanup_failures.append("created network removal was not confirmed: " + prefix)
    server.shutdown()
    if cleanup_failures:
        # Never remove configuration under a container whose removal is uncertain.
        raise RuntimeError("; ".join(cleanup_failures))
    shutil.rmtree(root)
    if root.exists():
        raise RuntimeError("Fixture temporary leaf removal was not confirmed")
    print("Exact created container IDs, network and temporary leaf cleanup confirmed")
