"""Independent exact-container deadline fence; hosted CI only, never imports Docker SDK.

Start after create and before start. Container identity is immutable; labels alone
are insufficient. The controller must also clean up in its finally block.
"""
import argparse
import json
import os
import re
import subprocess
import time
from cap_policy import caps_match


def validate_cleanup_scope(inspect, container_id, owner, image_id, *, network_none=False):
    if not re.fullmatch(r"[a-f0-9]{64}", container_id):
        raise ValueError("Container identity boundary")
    if not re.fullmatch(r"[a-f0-9]{16}", owner):
        raise ValueError("Owner boundary")
    if type(network_none) is not bool:raise ValueError('Network mode selector boundary')
    expected_name = ("/gameyfin-browser-cancel-probe-" if network_none else "/gameyfin-browser-") + owner
    if inspect.get("Id") != container_id or inspect.get("Name") != expected_name:
        raise ValueError("Container ownership mismatch")
    if inspect.get("Image") != image_id or not re.fullmatch(r"sha256:[a-f0-9]{64}", image_id):
        raise ValueError("Image mismatch")
    if inspect.get("Config", {}).get("Labels", {}).get("gameyfin.browser.owner") != owner:
        raise ValueError("Owner label mismatch")
    if network_none and inspect.get('Config',{}).get('Labels',{}).get('gameyfin.browser.purpose')!='cancel-probe-only':raise ValueError('Probe purpose label mismatch')
    host = inspect.get("HostConfig", {})
    mode = host.get("NetworkMode", "")
    if type(network_none) is not bool or mode != ('none' if network_none else 'gameyfin-browser-net-' + owner):
        raise ValueError("Network boundary")
    if network_none and host.get('RestartPolicy')!={'Name':'no','MaximumRetryCount':0}:raise ValueError('Probe restart boundary')
    if host.get("Privileged") or host.get('PidMode','')!='' or host.get('IpcMode','') not in ('','private') or host.get('UTSMode','')!='' or host.get('CgroupnsMode')!='private' or host.get('Init') is not True or not host.get("ReadonlyRootfs"):
        raise ValueError("Namespace boundary")
    if host.get('PortBindings') or host.get('PublishAllPorts'):
        raise ValueError('Browser exposure boundary')
    if inspect.get('Config',{}).get('User')!='10042:10042' or set(inspect.get('NetworkSettings',{}).get('Networks',{}))!={mode}:
        raise ValueError('Browser exclusive network/nonroot boundary')
    if any(m.get('Type')!='tmpfs' or m.get('Destination')!='/tmp' for m in inspect.get('Mounts',[])):
        raise ValueError('Browser mount boundary')
    return container_id


def validate(inspect,container_id,owner,image_id,*,network_none=False):
    identity=validate_cleanup_scope(inspect,container_id,owner,image_id,network_none=network_none)
    host=inspect['HostConfig']
    if host.get('NanoCpus')!=1000000000 or host.get('Memory')!=768*1024*1024 or host.get('MemorySwap')!=768*1024*1024 or host.get('PidsLimit')!=256 or not caps_match(host.get('CapDrop'),['ALL']) or not caps_match(host.get('CapAdd'),['SYS_ADMIN','SYS_CHROOT']):
        raise ValueError('Browser resource/capability boundary')
    return identity


def docker(*args):
    return subprocess.run(["docker", *args], check=True, timeout=5,
                          capture_output=True, text=True).stdout


def cleanup(container_id, owner, image_id, command=docker):
    rows = json.loads(command("inspect", container_id))
    if len(rows) != 1:
        raise ValueError("Inspection cardinality")
    # Runtime configuration is a separate acceptance gate. Removing the exact
    # owned isolated container does not grant extra resources/capabilities and
    # must remain possible after a configuration mismatch. Namespace/image/
    # immutable identity/nonroot/readonly/mount/exposure scope stays mandatory.
    exact = validate_cleanup_scope(rows[0], container_id, owner, image_id)
    command("rm", "--force", exact)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--container-id", required=True)
    parser.add_argument("--owner", required=True)
    parser.add_argument("--image-id", required=True)
    parser.add_argument("--deadline-seconds", type=int, default=90)
    args = parser.parse_args()
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted" or os.name != "posix":
        raise ValueError("Hosted Linux CI required")
    if not 1 <= args.deadline_seconds <= 90:
        raise ValueError("Deadline boundary")
    # No stdin commands or controller liveness dependency: it remains independent
    # when the main benchmark is killed. Only the exact owned container is removed.
    time.sleep(args.deadline_seconds)
    cleanup(args.container_id, args.owner, args.image_id)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        raise SystemExit("Owned browser watchdog failed; cleanup remains unproven")
