"""Linux authorized staging hook; stdin context and child-only credential environment.

Context: backend/network (owned synthetic runner names), provider, fixtureManifest
object from seed-scan-fixture.py, and immutable cached proxyImage. Creates only one
uniquely named proxy, no production mounts or published backend ports. Never pulls.
"""
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request


def main():
    context = json.loads(sys.stdin.read(65537))
    for field in ("backend", "network"):
        if not re.fullmatch(r"gameyfin-scan-(?:net-)?[a-f0-9]{16}", context[field]):
            raise ValueError("Only disposable synthetic runner resources are accepted")
    if not re.fullmatch(r"sha256:[a-f0-9]{64}", context["proxyImage"]):
        raise ValueError("Require immutable cached proxy image ID")
    manifest = context["fixtureManifest"]
    if manifest.get("scope") != "synthetic offline fixture":
        raise ValueError("Require generated synthetic manifest")
    game = manifest["games"][0]
    cases = []
    for optional in (False, True):
        ids = [game["requiredContentId"]] + ([game["optionalContentId"]] if optional else [])
        query = urllib.parse.urlencode({"provider": context["provider"], "variantId": game["variantId"],
            "contentIds": ",".join(map(str, ids)), "explicitSelection": "true"})
        members = {"Grouped base/" + Path(member).name: manifest["sha256"][member] for member in game["requiredMembers"]}
        if optional:
            if len(game["optionalMembers"]) != 1:
                raise ValueError("Fixture optional selection must be one seeded patch")
            members["Optional patch.bin"] = manifest["sha256"][game["optionalMembers"][0]]
        cases.append({"path": "/download/" + str(game["gameId"]) + "?" + query, "members": members})
    command = shlex.split(os.environ.get("GAMEYFIN_DOCKER_PREFIX", "")) + ["docker"]
    name = "gameyfin-proxy-smoke-" + secrets.token_hex(8)
    def docker(*args):
        return subprocess.check_output(command + list(args), text=True).strip()
    # Fail closed if a supposedly isolated runner backend publishes ports.
    backend = json.loads(docker("inspect", context["backend"]))[0]
    if backend["HostConfig"].get("PortBindings") or context["network"] not in backend["NetworkSettings"]["Networks"]:
        raise ValueError("Backend isolation differs from fixture context")
    with tempfile.TemporaryDirectory(prefix="gameyfin-proxy-smoke-") as leaf:
        root = Path(leaf)
        config = Path(__file__).parent.parent / "docker" / "exposure-fixture" / "nginx.conf"
        proxy_config = root / "nginx.conf"
        proxy_config.write_text(config.read_text().replace("http://gameyfin:8080", "http://" + context["backend"] + ":8080"))
        selected = root / "manifest.json"
        selected.write_text(json.dumps({"cases": cases}))
        try:
            docker("run", "-d", "--name", name, "--network", context["network"], "--restart", "no",
                "--cpus", "0.5", "--memory", "128m", "--memory-swap", "128m", "--publish", "127.0.0.1::8080",
                "--mount", f"type=bind,src={proxy_config},dst=/etc/nginx/conf.d/default.conf,readonly", context["proxyImage"])
            proxy = json.loads(docker("inspect", name))[0]
            bindings = proxy["NetworkSettings"]["Ports"]
            bindings = {port: values for port, values in bindings.items() if values}
            if list(bindings) != ["8080/tcp"] or len(bindings["8080/tcp"]) != 1 or bindings["8080/tcp"][0]["HostIp"] != "127.0.0.1":
                raise ValueError("Proxy must publish exactly one loopback port")
            base = "http://127.0.0.1:" + bindings["8080/tcp"][0]["HostPort"]
            for attempt in range(30):
                try:
                    with urllib.request.urlopen(base + "/login", timeout=2) as response:
                        if response.status == 200:
                            break
                except OSError:
                    pass
                time.sleep(1)
            else:
                raise RuntimeError("Synthetic proxy did not become ready")
            # Password exists only in this process environment and its child; never argv/files.
            completed = subprocess.run([sys.executable, "-B", str(Path(__file__).with_name("proxy-download-smoke.py")),
                "--base-url", base, "--manifest", str(selected), "--isolated-synthetic-staging"],
                env=os.environ.copy(), text=True, capture_output=True, timeout=180)
            if completed.returncode:
                # Do not propagate tracebacks containing fixture IDs or response URLs.
                raise RuntimeError("Synthetic authenticated proxy download acceptance failed")
            print(json.dumps({"proxyDownloadSmoke": "PASS", "caseCount": len(cases),
                "exactMembersAndHashes": True, "authenticatedIdentity": True, "logoutAnonymous": True,
                "proxyImage": context["proxyImage"]}))
        finally:
            subprocess.run(command + ["rm", "-f", name], capture_output=True)
            if docker("ps", "-a", "--filter", "name=^/" + name + "$", "--format", "{{.Names}}"):
                raise RuntimeError("Exact synthetic proxy cleanup could not be verified")


if __name__ == "__main__":
    main()
