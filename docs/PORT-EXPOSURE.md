# Port exposure inventory and isolated rehearsal

Issue #29 remains open until the selected production exposure changes and required torrent
behavior have been verified. No TrueNAS configuration was changed by this work.

## Read-only inventory on 2026-10-07

The live host listens on IPv4 wildcard and IPv6 wildcard for all three ports below.
An unauthenticated request to the observed direct HTTP endpoint returned HTTP 302 with its
login location on the same direct address. This confirms application login, not a
network restriction or an Authelia check. The observation does not establish public
Internet reachability or router/firewall state.

| Host port | Purpose in repository | Exposure decision |
| --- | --- | --- |
| 31005 | Gameyfin HTTP UI/API, mapped to app port 8080 | Restrict to the intended proxy network/interface after choosing the proxy route |
| 6969 TCP | Built-in torrent plugin HTTP tracker (`/announce`, `/scrape`) | Needed only if that plugin is used; allow only intended torrent-client networks |
| 6881 | Built-in torrent plugin peer listener | Needed only if those clients require incoming peer connections; verify TCP/UDP and intended networks before changing bindings |

The torrent tracker uses its own HTTP server and is not protected by Gameyfin's web
login. The peer listener binds both IPv4 and IPv6 wildcard in the plugin. Torrent
protocols cannot be assumed to pass through a browser authentication gateway. Keep
their access decision separate from the UI login/SSO decision. Do not change source
paths or torrent files to rehearse network exposure.

The normal Compose example now binds app HTTP to IPv4 loopback. This suits a proxy on
the same host or an SSH tunnel. A proxy on a separate host needs an explicitly chosen
reachable interface and firewall allow-list; blindly changing the live bind could
disconnect that proxy. Port 8081 remains unpublished for internal health/metrics.

## Isolated fixture

`docker/exposure-fixture/compose.yml` creates a separate project with only staging named
volumes, no library mounts, no backend host ports, and one loopback-only proxy port.
Set `GAMEYFIN_STAGING_IMAGE` to a reviewed candidate digest and generate a separate
`GAMEYFIN_STAGING_APP_KEY`. Start it with `docker compose -f docker/exposure-fixture/compose.yml up -d`.
The loopback proxy port is configurable with `GAMEYFIN_STAGING_PORT` (default 39080).
`GAMEYFIN_STAGING_BASE_URL` optionally overrides the application URL for the test proxy.
Neither value changes production settings. Run this fixture only on an authorized
staging server or in CI; local Docker is not required for development checks.
Complete initial setup with a staging-only administrator and keep anonymous access disabled.
Run `./scripts/verify-exposure-fixture.ps1` after setup. Its checks verify the proxy login
page, anonymous root redirect, management denial, and backend port bindings. Use
`-ConfigurationOnly` to validate isolation before starting containers.

The nginx fixture forwards the browser host, scheme, and session cookies without an
SSO gateway. Complete authenticated browse, grouped/exact download, logout, and
WebSocket checks against this fixture before claiming proxy behavior unchanged. If
the built-in torrent plugin is required, enable it only in a separate synthetic torrent
fixture, route tracker/peer traffic on an isolated network, and verify announce, peer
discovery, byte transfer, and unchanged source hashes before selecting firewall rules.

The local Docker daemon was unavailable during this initial run. Configuration checks
and existing application security tests do not substitute for the proxy/torrent rehearsal.
On 2026-10-07, Compose configuration validation passed and 19 existing actuator,
authentication-entry-point, and dynamic-public-access tests passed under JDK 25.
These test administrator/CSRF behavior and application login decisions, not a running
proxy or torrent client. No production bind/firewall change is included in that evidence.

### Authorized disposable-server rehearsal on 2026-10-07

The nginx configuration was exercised on the authorized server in two separate,
synthetic-only Compose projects. No production volume, library, plugin configuration,
credential, or torrent path was mounted. Each backend was limited to one CPU and
1536 MiB, with a separate generated AES application key and staging-only administrator.
The nginx image was the server's cached image
`sha256:7bc5ba2f958a043e123135f456af857350673b64eaddcf811698239f3a53d6e6`
(reported nginx 1.31.4), not a claim that the example's pinned 1.28 image was tested.

| Rehearsal | Application image | Isolated project / proxy bind |
| --- | --- | --- |
| Fresh baseline 2.4.0 | `sha256:b031128b79ce56a7a7ea59498d1894ae5a28b0309952bd917ee7c3f9936d3729` | `gameyfin-exposure29-20261007`, `127.0.0.1:39081` |
| Reviewed PR #111 artifact, image label 2.4.3, revision `0f152220a5c73b938312f43e464291fd1dfb9511` | `sha256:e0508fd21796cac8b1b9d34d750dd9ec1fdd1f126d19d69460185a78d02e1258` | `gameyfin-exposure29-candidate-20261007`, `127.0.0.1:39082` |

Both fresh instances completed setup through the proxy with separately generated
credentials held only in the test process. Administrator form login, session-authenticated
`UserEndpoint/getUserInfo`, authenticated root browse, and logout passed. After setup,
anonymous `/login` returned 200, anonymous `/` returned 302, and `/actuator/health`
returned 404 through nginx. Docker inspection showed `{}` backend port bindings and
only the four project-owned named volumes. The candidate proxy published only
`127.0.0.1:39082`; the host listener inspection agreed.

The rehearsal initially generated an incorrectly encoded application key; the isolated
baseline rejected it safely at startup. Recreating only that disposable backend with a
base64-encoded 32-byte key resolved it before setup. No live key or configuration changed.

The two exposure projects, their eight synthetic named volumes and two networks were
removed after testing. The shared candidate image and unrelated staging/production
containers were retained. This proves basic application proxy/authentication isolation,
not authenticated grouped downloads, WebSockets, torrent-client traffic, live firewall
rules, or production cutover readiness. Issue #29 remains open for those criteria.

### Read-only inventory refresh and change boundary

A later read-only inventory confirmed that the running application publishes all three
ports as TCP on IPv4 and IPv6 wildcard addresses; no UDP host mapping was shown.
The host INPUT policies were ACCEPT for both address families. That observation is
not a complete firewall audit: Docker published traffic may traverse FORWARD,
DOCKER and DOCKER-USER chains instead, and upstream router rules are still unknown.
Do not infer Internet reachability, torrent usage, or a safe replacement binding from it.

Before proposing a live change, record the existing proxy's Docker network and backend
target without printing environment variables or secrets. Check the torrent plugin's
enabled status and configured tracker URL through authenticated read-only application
settings, then inventory active tracker/peer connections and the intended clients.
Inspect IPv4 and IPv6 FORWARD/DOCKER-USER rules and any upstream forwarding separately.
Preserve the existing production hostname; it is operator configuration, not an app default.

The remaining operator choices are whether the direct UI port must remain reachable
from an administration LAN, whether torrent tracker/peer access is actually required,
and the allowed client networks for each. A Docker-network reverse proxy does not
require a published backend UI port. Any proposed restriction needs the exact current
configuration saved for rollback, explicit production-change approval, and validation
of that existing proxy plus required synthetic torrent announce/peer/byte-transfer tests.
No application source path should be changed for a network test.

For the proxy's remaining functional evidence, initialize a real authenticated browser
session and exercise its actual Vaadin push connection, including reconnect; a made-up
WebSocket upgrade request does not prove the application protocol works. Use a small
synthetic grouped download manifest through that same proxy and verify exact archive
members and hashes both with and without the optional content. Never use a production
game or a large real download as the fixture.

`scripts/proxy-download-smoke.py` provides a reusable bounded archive check after
seeding an isolated staging fixture. Supply `--base-url`, `--manifest`, and the explicit
`--isolated-synthetic-staging` acknowledgement. A manifest contains two to eight
`cases`, each with a same-origin `/download/...` `path` and a `members` object mapping
each expected archive member name to its SHA256. Include required-group-only and
required-plus-optional selections. It rejects unexpected/duplicate members, wrong
hashes, redirects to another origin, and responses or expanded archives over one MiB.
Use HTTPS or an isolated loopback proxy; pass staging-only username/password through
`GAMEYFIN_SMOKE_USERNAME`/`GAMEYFIN_SMOKE_PASSWORD` in the test process environment,
not command arguments, repository files, or saved shell history. The script authenticates
the expected identity before downloads and logs out after successful checks. If a check
fails, destroy the disposable instance/session during cleanup. No credentials are written.
Run `python scripts/test-proxy-download-smoke.py` for daemon-free validator tests.
Passing these tests validates the harness, not a live proxy download; actual fixture
execution and browser WebSocket evidence remain separate acceptance requirements.

## Remaining user/environment choices

Record the intended proxy hostname, where the proxy runs, and which network/interface it
uses to reach Gameyfin. Confirm whether the built-in Gameyfin torrent plugin is used and
which LAN/public peer networks it must serve. Inventory IPv4/IPv6 firewall and router
forwarding rules, then prepare a reviewed bind/allow-list change with rollback. Apply
only through an explicitly authorized production change, followed by proxy/login and
required torrent validation. External SSO remains a later choice.
