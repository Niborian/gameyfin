# Optional qBittorrent-backed library distribution (issue #112)

This is a new optional download/seeding feature, not acquisition automation. The original
Torrent Download plugin stays installed and available. No running configuration, source
path, host mapping, release tag, or production deployment is changed by this foundation.

## Selection-aware provider foundation

The existing PF4J `DownloadProvider.download(Path)` accepts one path. Core download handling
currently produces its own ZIP for grouped/optional content rather than dispatching that
selection to a provider. `SelectionAwareDownloadProvider` adds an explicit capability that
receives immutable logical content names and every canonical path in the authorized selection.
Core variant/content membership, retirement-state and content-root checks still run first.
Required content is included; unchecked optional content is excluded. Both catalog aliases
and canonical source paths are leased during provider execution and returned file streaming.
Lease release on failure/EOF/close remains the host's responsibility. Providers without the
new capability keep their existing single-path dispatch and multi-content ZIP behavior.

The descriptors are not application persistence entities, credentials, or an authority to
modify paths. A provider that retains/seeds data after returning must own a separate validated
snapshot and lifecycle; a response-stream lease alone does not protect long-lived seeding.
This first PR includes a fake selection-aware provider and focused compatibility/security
tests. It is not the qBittorrent implementation or proof of feature parity; #112 remains open.

## Next focused implementation and acceptance boundaries

- Add a separately identified, administrator-opt-in plugin with dedicated seeding-client
  origin/credentials, isolated state and read-only snapshot mount. Never share acquisition
  ownership or adopt unrelated pre-existing torrents. Categories/tags alone are not isolation.
- A bounded snapshot builder must copy only authorized selected content into a new owned
  immutable tree, preserving logical grouped names. Original torrent-managed files and paths
  remain unchanged. Do not use writable hardlinks, move originals, remap unrelated torrents,
  or pass a whole library directory to the creator. Verify stable source fingerprints and
  copied hashes, enforce count/byte/storage/time limits and cancellation, and publish snapshots
  atomically. Logical content names are untrusted display text: derive safe relative names,
  reject traversal and collisions, and never interpret a display name as a filesystem path.
  Copying large games costs storage and I/O; quotas, reuse and retention must be
  measured before claiming resource parity. No unsafe copy-free optimization is implied.
- qBittorrent 5.1.4's official source exposes torrent-creator task, status and file methods.
  Its creator takes a single source path, so it cannot represent arbitrary authorized sibling
  selections without the isolated exact-selection tree. Probe/pin supported API capabilities;
  do not assume all 5.x clients implement creation. Explicitly disable auto-seeding at creation.
  See the [official 5.1.4 creator controller](https://github.com/qbittorrent/qBittorrent/blob/release-5.1.4/src/webui/api/torrentcreatorcontroller.cpp).
- Validate bounded torrent metadata, file names/counts/sizes/piece hashes and selected bytes
  before starting an owned torrent. Persist intent and operation identity before external
  calls. Verify stopped-add/category/tag/path/hash ownership before starting; use idempotent
  reconcile after ambiguous timeouts/restarts rather than duplicate creation or broad cleanup.
  Stop/retention must affect only exact owned hashes; never request source-file deletion.
- Keep the private authenticated qBittorrent API distinct from configurable peer TCP/UDP/NAT
  exposure. qBittorrent does not become an authenticated tracker merely by having a protected
  Web UI. Use an explicitly configured supported tracker policy; private mode alone is not
  user authorization. Do not scrape or silently add public trackers/DHT/PEX fallbacks.
- Authenticated `.torrent` delivery does not wrap peer transfers in browser authentication.
  A recipient can retain/share metadata or completed bytes; stopping a seed or revoking a
  tracker credential cannot recall already delivered files. Document supported tracker
  admission/revocation limitations and authorized-content-only use before enabling the plugin.
- Demonstrate creation, metadata validation, exact grouped/optional download, actual seeding
  to an isolated second client, restart/failure recovery, scoped stop and cleanup, unchanged
  original hashes/paths, and bounded resource use with a pinned actual qBittorrent fixture in
  CI or an authorized disposable server test. No local Docker or production torrent paths.

Only after parity is evidenced should a separate reviewed cutover consider disabling or
removing the original plugin. This work does not authorize that cutover or promote `latest`.

## Snapshot and actual creator foundation

`SelectionSnapshotBuilder` is deliberately not wired to a production provider. A caller
must supply a private, application-owned cache, byte/entry/time budgets, and its own
capacity reservation and retention lifecycle. Each operation makes new copies, traverses
source directories through no-follow directory descriptors, verifies source file identities
and copied SHA-256 bytes, and atomically publishes only the new owned leaf. Platforms
without `SecureDirectoryStream` fail closed. Filesystem snapshots are not assumed immutable
against an administrator: the future dedicated qB client must receive this cache read-only,
and Gameyfin must retain ownership until all owned seeds are stopped and reconciled.

Run `python3 tools/qbittorrent-creator-fixture.py --staging-ack` only on a disposable CI
runner or an explicitly authorized staging host with the pinned image already loaded.
The fixture accepts no operator endpoint or source path. It creates tiny invented grouped
and optional selections, a private authenticated qB client on an internal Docker network,
and a loopback-only relay; it publishes no Docker ports and mounts only its new fixture
copies read-only. Synthetic HTTP disables secure-cookie transport only inside that isolated
fixture; production API transport must remain HTTPS or an explicitly protected private link.
Credentials are generated per run and deleted with the owned temporary state.

An authorized disposable server rehearsal on 2026-10-08 used qBittorrent v5.2.3,
`ghcr.io/hotio/qbittorrent@sha256:91d59985ed65fe3504b405b51e6d586524c2189a6cffd56324101b70fe094bde`
(image ID `sha256:5abfcc362ba366e18acdf10e97427bea3ad883a4aef0681021cf8693670ccd2a`).
Both required-only and grouped-plus-optional creator tasks passed exact member names,
sizes, private flag and v1 piece-hash checks. Auto-seeding was explicitly disabled; the
client had no added torrents, and original hashes remained unchanged. Owned containers,
network and temporary directories were removed and their absence independently checked.
This proves the pinned creator capability, not Kotlin-to-client integration or peer transfer.
An additional selected hidden-file case exposed a limitation: this pinned creator omitted
the dotfile; this release does not expose an `ignoreDotfiles` creator control, although
newer source code does. Sending that parameter does not prove support. Exact-member validation
rejected the incomplete metadata. The fixture retains that negative case; a future provider
must reject such a selection rather than silently deliver an incomplete torrent. Supporting
all hidden-file selections requires separate verified resolution before claiming parity.

The dedicated CI workflow requires every snapshot test to execute on Linux with no skips
and repeats the real pinned creator rehearsal. Windows tests intentionally skip descriptor
copy cases, so a local Windows pass alone is not acceptance. Still missing for #112: provider
configuration and wiring, persisted idempotent seeding ownership, second-client transfer,
restart/recovery, tracker admission/revocation, resource/retention evidence, and reviewed
opt-in cutover. The original plugin and existing download routes remain unchanged.
