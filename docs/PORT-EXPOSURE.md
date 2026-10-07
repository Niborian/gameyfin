# Port exposure inventory and isolated rehearsal

Issue #29 remains open until the selected production exposure changes and required torrent
behavior have been verified. No TrueNAS configuration was changed by this work.

## Read-only inventory on 2026-10-07

The live host listens on IPv4 wildcard and IPv6 wildcard for all three ports below.
An unauthenticated request to `http://10.0.0.200:31005/` returned HTTP 302 with its
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

## Remaining user/environment choices

Record the intended proxy hostname, where the proxy runs, and which network/interface it
uses to reach Gameyfin. Confirm whether the built-in Gameyfin torrent plugin is used and
which LAN/public peer networks it must serve. Inventory IPv4/IPv6 firewall and router
forwarding rules, then prepare a reviewed bind/allow-list change with rollback. Apply
only through an explicitly authorized production change, followed by proxy/login and
required torrent validation. External SSO remains a later choice.
