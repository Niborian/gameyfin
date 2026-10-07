"""Bounded, synthetic-only authenticated proxy download acceptance.

Credentials are read from GAMEYFIN_SMOKE_USERNAME/PASSWORD, never persisted.
Manifest: {"cases": [{"path": "/download/1?...", "members": {"base.bin": "sha256"}}]}.
Use existing staging-only accounts and tiny generated files; this does not seed data.
"""
import argparse
import hashlib
import http.cookiejar
import io
import json
import os
import re
import urllib.parse
import urllib.request
import zipfile

LIMIT = 1024 * 1024


def bounded_read(response):
    body = response.read(LIMIT + 1)
    if len(body) > LIMIT:
        raise ValueError("Synthetic response exceeds one MiB limit")
    return body


def verify_archive(body, expected):
    if not expected or not isinstance(expected, dict):
        raise ValueError("Expected nonempty member/hash mapping")
    with zipfile.ZipFile(io.BytesIO(body)) as archive:
        entries = archive.infolist()
        if len(entries) != len(expected) or {entry.filename for entry in entries} != set(expected):
            raise ValueError("Archive members differ from synthetic manifest")
        if sum(entry.file_size for entry in entries) > LIMIT:
            raise ValueError("Expanded synthetic archive exceeds one MiB limit")
        for entry in entries:
            digest = expected[entry.filename]
            if not re.fullmatch(r"[0-9a-f]{64}", digest):
                raise ValueError("Expected SHA256 hex digest")
            if hashlib.sha256(archive.read(entry)).hexdigest() != digest:
                raise ValueError("Synthetic archive member hash mismatch")


def local_path(path):
    parsed = urllib.parse.urlsplit(path)
    if parsed.scheme or parsed.netloc or not path.startswith("/") or path.startswith("//"):
        raise ValueError("Manifest paths must be same-origin relative paths")
    if not parsed.path.startswith("/download/"):
        raise ValueError("Manifest may only request synthetic download endpoints")
    decoded = urllib.parse.unquote(parsed.path)
    if "\\" in decoded or any(part in (".", "..") for part in decoded.split("/")):
        raise ValueError("Manifest paths cannot contain traversal segments")
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--isolated-synthetic-staging", action="store_true", required=True)
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    origin = urllib.parse.urlsplit(base)
    if origin.path or origin.query or origin.fragment or origin.username or origin.password:
        raise ValueError("Base URL must be an origin without embedded credentials")
    if origin.scheme != "https" and not (origin.scheme == "http" and origin.hostname in ("localhost", "127.0.0.1", "::1")):
        raise ValueError("Use HTTPS or an isolated loopback HTTP proxy")
    with open(args.manifest, encoding="utf-8") as source:
        encoded = source.read(65537)
        if len(encoded) > 65536:
            raise ValueError("Synthetic manifest exceeds 64 KiB")
        manifest = json.loads(encoded)
    cases = manifest["cases"]
    if not 2 <= len(cases) <= 8:
        raise ValueError("Supply two to eight grouped/optional synthetic cases")
    for case in cases:
        local_path(case["path"])
    username = os.environ["GAMEYFIN_SMOKE_USERNAME"]
    password = os.environ["GAMEYFIN_SMOKE_PASSWORD"]

    class SameOriginRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, request, fp, code, msg, headers, newurl):
            if urllib.parse.urlsplit(newurl)[:2] != origin[:2]:
                raise ValueError("Proxy redirected outside staging origin")
            return super().redirect_request(request, fp, code, msg, headers, newurl)

    jar = http.cookiejar.CookieJar()
    client = urllib.request.build_opener(SameOriginRedirect(), urllib.request.HTTPCookieProcessor(jar))

    def request(path, data=None):
        with client.open(urllib.request.Request(base + path, data=data), timeout=30) as response:
            return bounded_read(response)

    html = request("/login").decode()
    csrf = re.search(r'<meta name="_csrf" content="([^"]+)"', html)
    if not csrf:
        raise ValueError("Staging login CSRF token absent")
    login = urllib.parse.urlencode({"username": username, "password": password, "_csrf": csrf.group(1)}).encode()
    request("/login", login)
    # Confirm the authenticated identity, not merely a successful login-page response.
    html = request("/").decode()
    csrf = re.search(r'<meta name="_csrf" content="([^"]+)"', html)
    identity = urllib.request.Request(base + "/connect/UserEndpoint/getUserInfo", data=b"{}",
        headers={"Content-Type": "application/json", "X-CSRF-TOKEN": csrf.group(1) if csrf else ""})
    with client.open(identity, timeout=30) as response:
        if json.loads(bounded_read(response))["username"] != username:
            raise ValueError("Staging authenticated identity differs")
    for index, case in enumerate(cases, 1):
        verify_archive(request(case["path"]), case["members"])
        print(f"PASS: synthetic grouped download case {index}, exact members and SHA256")
    request("/logout", urllib.parse.urlencode({"_csrf": csrf.group(1)}).encode())
    print("PASS: staging-only authentication and logout; credentials not persisted")


if __name__ == "__main__":
    main()
