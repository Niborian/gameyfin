# Optional qBittorrent distribution

Status: implementation draft for #112. Not enabled, not deployment instructions,
and not replacement-parity evidence. Keep the original Torrent Download plugin.
This serves selected library content; it does not acquire games from indexers.

## Intended boundary

An administrator must opt in to a dedicated seeding client and verify its private
API, read-only snapshot mapping, storage limits, trackers and peer-network policy.
Do not reuse an unrelated application's qBittorrent client. No personal hostnames,
addresses or credentials belong in source code. Remote API transport requires
HTTPS; loopback HTTP is only appropriate within an explicitly isolated link.

The host resolves the same authorized variant/content membership as exact downloads,
including required base files and selected grouped/optional content. A separate
Kotlin/JDK25 Linux AMD64/glibc worker copies stable source descriptors into owned
snapshots. It never changes original paths, modes, bytes or hardlink relationships.
Copies consume additional disk space and hashing/creation work; admission and
retention must be bounded. Unsupported capabilities fail closed; ZIP/direct
downloads must remain available.

The draft has two independent admission controls: saved administrator opt-in and
the server's default-false `gameyfin.owned-torrent.core-parity-enabled` interlock.
Neither a checked confirmation nor a saved configuration proves a safe deployment.
Credentials are write-only through dedicated encrypted settings, not generic
configuration responses. Prepared metadata is restricted to the requesting
authenticated browser session; that restriction does not extend to torrent peers.

The current adapter targets qBittorrent5.2.3 / WebAPI2.15.1 and v1 metainfo.
This is a protocol target, not proof that every image/build has passed runtime
acceptance. Creation uses `torrentcreator/addTask` with `startSeeding=false`.
Before starting, validate exact members, byte lengths and piece hashes against
the held snapshot, add stopped without skipping checks, and verify owned state.
Only host-derived snapshot paths may reach this API; never caller-supplied paths.

## Authentication is not torrent revocation

Gameyfin/OIDC controls obtaining metadata through the web application. A downloaded
`.torrent` contains file names, lengths, piece hashes and tracker information;
it can be copied outside the browser. Peer connections do not carry a Gameyfin
browser session. Logging out, expiring a session or changing a web role cannot
recall metadata or bytes already distributed.

Private metainfo restricts discovery for compliant clients; it is not encryption
or a substitute for network enforcement. A known peer address can still be a
target for connections. Verify dedicated-client peer ingress/egress rules and
tracker admission separately; disabling DHT/PeX/LSD alone is insufficient.
Use only content the operator is authorized to distribute.

Stopping and removing the exact owned torrent can stop this application's seed
after acknowledgement. It cannot erase recipients' files, recall shared metadata
or stop independent peers from redistributing content. Do not advertise per-user
instant revocation or OIDC-protected peer traffic.

## Ownership, restart and cleanup

Persist operation, client/mapping, snapshot/manifest and lease identities before
external mutations. A category, tag or matching info-hash alone does not grant
ownership. Unknown outcomes retain ownership for explicit reconciliation, not
blind recreation or adoption of an existing torrent. Failed ownership proof must
not authorize even a best-effort stop of a possibly foreign torrent.

Keep durable snapshot protection through creation and seeding. Removal must prove
the exact owned torrent is stopped, use `deleteFiles=false`, observe its absence
and the creator task's absence, then release the exact lease. Descriptor consumers
must also have exited before owned-cache retirement can proceed. Foreign entries,
unknown process scopes and uncertain publication are not disposable by name.

A committed remote terminal state is not proof that its separate local lease
release succeeded. Recovery must finalize the same persisted ownership tuple
before deleting the owned snapshot. Creator-task deletion is asynchronous: task
absence alone must not be used as proof that an interrupted creator stopped
reading its source. Unresolved operations remain protected for explicit recovery.

If preparation finishes but its identity response is lost before persistence, an
empty pending directory may remain. No source copy is authorized before that
identity commits. The application must retain this uncertain entry rather than
invent its ownership from a UUID-shaped name. An operator exit receipt cannot
reconstruct a missing directory identity. Do not promise automatic cleanup for
every crash window or a global storage bound merely from per-operation quotas.

## Release gates still outstanding

Administrator configuration/download wiring, descriptor-checked retention and
restart/operator recovery, exact-head Linux checks, actual pinned seeder plus
second-client transfer, unchanged source fingerprints and exact fixture cleanup
must be evidenced. Mocked HTTP, Windows-skipped native checks or a creator-only
metadata fixture are not feature parity. Production changes remain a separate
reviewed cutover; never remove the original plugin merely because code merged.

Protocol references: [BitTorrent metainfo and peer protocol](https://www.bittorrent.org/beps/bep_0003.html),
[private torrents and their limits](https://www.bittorrent.org/beps/bep_0027.html),
[reviewed creator controller](https://github.com/qbittorrent/qBittorrent/blob/release-5.2.3/src/webui/api/torrentcreatorcontroller.cpp),
[pinned API version declaration](https://github.com/qbittorrent/qBittorrent/blob/release-5.2.3/src/webui/webapplication.h).
