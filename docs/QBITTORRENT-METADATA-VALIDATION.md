# Inactive host-owned metadata validation (#112)

`TorrentMetadataValidator` is an internal, pure validation slice. It has no endpoint,
provider registration, filesystem path, network client, source-copy operation or seeding
permission. Existing downloads and production configuration remain unchanged.

The future host must authorize selection, obtain a verified immutable snapshot lease,
and construct its manifest and `OwnedSnapshotReader` capability. The reader takes only
safe generated manifest member names. It must never resolve a caller-provided path or
read mutable torrent-managed originals. This interface is not itself proof of safe
acquisition or storage ownership.

Validation consumes and closes its metadata stream and every opened member stream,
including failures. It checks canonical bounded bencoding, a private v1 multifile torrent,
safe generated ASCII names, exact membership and lengths, and no duplicate/case-colliding
members. Hidden names, Unicode names, v2/hybrid metadata and unsupported info extensions
are deliberately refused; a future helper may generate safe names only after preserving
and documenting exact logical membership. Refusal must never silently omit a selected file.

Limits: metadata 1 MiB, parsing depth 16 and 32,768 nodes, manifest 4,096 files, paths
32 components, and 64 GiB source bytes by default (configurable to at most 1 TiB).
Piece size must be a power of two between 16 KiB and 16 MiB; piece count is bounded by
metadata and total-byte limits. Source bytes are streamed with a 64 KiB buffer, verifying
the expected SHA-256 per member and BEP3 SHA-1 pieces across member boundaries. SHA-1 here
is mandated torrent interoperability, not the trusted content-integrity digest.

The result records metadata SHA-256, info-hash, validated root name, byte count and member
count. Any later add must bind the exact recorded metadata digest and root name to its
owned snapshot; it must independently validate tracker admission and configured peer
policy. This validator does not authorize any metadata-driven network destination: the
future add policy must reject or explicitly whitelist every network-bearing field,
including `announce`, `announce-list`, `url-list`, `httpseeds` and unknown extensions.
Checking only the primary tracker URL is insufficient; webseeds and secondary trackers
must not bypass peer/API exposure restrictions. No active client may consume metadata
until that separate host policy is implemented and reviewed. Stream interruption
is cooperative; the future process/lifecycle boundary must manage elapsed-time limits
and uncertain blocked I/O. No timeout or physical recovery guarantee is claimed here.

Tests cover grouped/optional and required-only pieces, empty members, corrupt digests,
truncation/growth, membership/path/type refusal, canonical malformed encoding, quotas,
stream closure and interruption. These are synthetic unit tests, not actual-client
transfer evidence and not completion of #112.
