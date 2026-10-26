# Optional qBittorrent distribution

Status: optional implementation for #112, with isolated source/CI acceptance
evidence described below. Default off; not deployment instructions or production
activation approval. Keep the original Torrent Download plugin.
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

### Shared selections, not duplicate copies

The shared implementation gives identical authorized selections one owned immutable copy
and one torrent/swarm. Each browser request keeps its own durable reference and
session-restricted metadata URL; users do not receive another user's request URL.
Canonical resolved variant/content membership and immutable client configuration
identify candidates, but a candidate key or matching info-hash is never enough.

Before attaching another request, recheck current authorization/membership, the
complete current source tree through read-only descriptors and full byte hashes,
the held cache, and the exact owned seeding client's metadata/member/piece tuple.
Stable identical payload from replaced source files can reuse that proven owned
copy; changed payload needs a separate bounded generation. Unknown I/O, unstable
files or failed ownership proof refuse reuse and do not permit a speculative copy.
Source verification still costs reads/hashing, but does not copy the files again.

Releasing one reference leaves the seed intact while other references remain.
The last release fences new attachments before owned stop/removal/cache retirement.
It does not revoke metadata or data already obtained by peers. These sharing
semantics have isolated synthetic CI evidence, not production-network acceptance.

The feature has two independent admission controls: saved administrator opt-in and
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

## Acceptance evidence and remaining boundaries

The shared implementation merged in PR167 (`3b671478`, tested implementation
head `44830626`). Exact-head CI exercised six worker protocol cases and fifteen
native Linux cases without skips, plus the actual pinned qBittorrent pair fixture.
The pair uses real source descriptors, migrated H2 ownership/reference rows,
the seeding client and a second transfer client. It proves two independent request
references with one copy/create/add, identical metadata, continued transfer after
the first release, and exactly one non-destructive removal after the last release.
It also covers changed selections/source generations, restart with persisted
ownership, revoked-attach rollback, unchanged source fingerprints/hardlinks and
exact owned cleanup. Focused settings, facade, registry and lifecycle tests cover
admission, captured-authority checkpoints and retained-operation recovery.

PR169 head `629b430a`, CI run
[40255048843](https://github.com/Niborian/gameyfin/actions/runs/40255048843),
passed all four `OwnedTorrentHttpAcceptanceTest` cases and the real pair case.
The opt-in HTTP fixture starts the actual embedded Vaadin servlet lifecycle on
isolated loopback ports, then uses MockMvc with the real security filter chain,
Hilla dispatcher, native login, session registry, account policy, facade and H2.
It verifies owning-session READY status and exact no-store metadata retrieval,
unrelated/anonymous/expired/logged-out denial, administrator-only settings access,
and CSRF plus invalid-selection refusal without queue side effects. Its prepared
READY rows are synthetic read fixtures; they do not claim worker or qB execution.
The authenticated `/owned-torrent/**` route retains the facade's exact requesting-
session and current-membership checks; it is not a public-mode route.

This is compositional evidence: real native/qB transfer and sharing are tested
separately from the actual web/session boundary. A single positive HTTP
`create -> queue -> worker -> READY` journey, a real browser torrent-button click,
and deployment-specific OIDC/tracker/peer-network behavior have not been exercised
by these fixtures. MockMvc is not a socket-client test, and Windows-skipped native
checks are not Linux evidence. No claim of exhaustive crash recovery is made.

Neither source completion nor these isolated fixtures enables the feature on a
running instance. Production configuration, peer-network validation, original-
plugin removal and any `latest` promotion require separate reviewed authority and
cutover evidence. Retain the original plugin until that cutover is approved.

Protocol references: [BitTorrent metainfo and peer protocol](https://www.bittorrent.org/beps/bep_0003.html),
[private torrents and their limits](https://www.bittorrent.org/beps/bep_0027.html),
[reviewed creator controller](https://github.com/qbittorrent/qBittorrent/blob/release-5.2.3/src/webui/api/torrentcreatorcontroller.cpp),
[pinned API version declaration](https://github.com/qbittorrent/qBittorrent/blob/release-5.2.3/src/webui/webapplication.h).
