# Proposed host-owned snapshot boundary (#112)

Status: design for review, not an implemented provider or cutover evidence. The existing
creator fixture does not establish safe acquisition from a mutable library. The original
Torrent Download plugin remains available.

## Decision

Do not rebuild the removed copier using Java path attributes plus repeated path hashes.
`SecureDirectoryStream` anchors directory operations, but its public channel interface
does not expose opened-file identity. A source can be A during a path stat, B when the
channel opens, and A again during the final path stat. Hashing B twice is not proof that
authorized A was copied.

Use a host-owned, Linux-only descriptor acquisition process, with a fail-closed capability
probe. It must not be an arbitrary-path facility exposed to a download plugin. Other
platforms retain ZIP/direct downloads until an equivalent reviewed acquisition backend
exists. A separate process allows cancellation without keeping a blocked copier thread in
the application; process termination is not a hard guarantee against uninterruptible
kernel I/O.

## Trust and authorization

- Root, the Gameyfin service identity, and administrators controlling the helper executable
  and its configuration are trusted. A malicious process sharing the service UID is outside
  this isolation boundary. Do not describe mode bits as protection against that process.
- Untrusted source names, symlinks, concurrent renames, and ordinary source writers are in
  scope. The copier never writes to source descriptors or changes original modes/paths.
- The host validates game/variant/content membership and acquires its existing source lease
  before invoking acquisition. A plugin receives only the resulting snapshot identifier and
  manifest, not a helper invocation capability or a source-path override.
- The current `DownloadSelection` names and canonical paths are insufficient as an identity
  capability. Host acquisition must bind membership to a descriptor-backed source manifest
  inside the same trusted operation. Its source root descriptors and relative selection
  paths must come from host-validated content roots, never plugin or HTTP caller roots.
- Before supporting directory selections, specify whether a selected directory authorizes
  its membership at enumeration time. A mixed-time tree cannot be advertised as an atomic
  filesystem snapshot. Per-file stability checks do not establish global tree consistency.

## Descriptor protocol

1. Open configured source and cache roots once. Validate actual opened directories, owner,
   modes, filesystem capabilities and root identities. Reject symlink roots and untrusted
   writable cache ancestry. Keep those directory descriptors until cleanup is complete.
2. Resolve selected relative members beneath source root descriptors using `openat2` with
   `RESOLVE_BENEATH`, `RESOLVE_NO_SYMLINKS` and `RESOLVE_NO_MAGICLINKS`. Decide explicitly
   whether mount crossings are supported; if not, add `RESOLVE_NO_XDEV`. Reject unsupported
   kernels rather than replacing this with path-prefix comparisons.
3. Record each authorized entry's device, inode, regular-file type, size, nanosecond mtime
   and ctime, then open and compare **fstat on that opened descriptor** with that record.
   An A-stat/B-open/A-restore substitution must fail even when final path attributes match A.
   Retain the opened source descriptor for the entire copy. Reject special files and hardlink
   aliases unless their semantics and selection collisions are explicitly supported.
4. Create an unpredictable operation directory through the retained cache root descriptor
   with exclusive creation and mode 0700. Validate its opened identity; create every child
   through owned directory descriptors, with exclusive/no-follow file creation. Do not resolve
   cache paths again during writes, publication, or cleanup. Never use source hardlinks.
5. Stream into the new destination descriptor while computing the digest and enforcing actual
   cumulative bytes/file counts. Advisory size preflight does not replace these limits. Before
   and after copy, compare source descriptor metadata; re-read the same source descriptor for
   a second digest and compare it with the destination digest and metadata stability checks.
   This detects ordinary concurrent in-place writes; it is not protection against trusted root
   altering kernel-visible timestamps or against an atomic cross-file consistency requirement.
6. Persist a bounded manifest containing safe generated relative names, selected logical
   membership, sizes, identities and digests. Flush files and required directories. Publish
   through descriptor-relative atomic rename into an owned completed namespace with a
   non-overwriting destination rule. Reject ownership/identity changes before publication.
7. Hand only the owned completed tree to the dedicated read-only seeding client. Keep the
   snapshot lease until the journal proves the owned torrent is stopped and removed without
   source deletion. Never infer exclusivity from a qBittorrent category or tag alone.

The helper protocol should be versioned, bounded and length-framed. Inputs must include an
operation token, host-authorized members and numeric quotas; no credentials. Outputs contain
only a terminal result and bounded manifest. An executable path is administrator-controlled,
not caller supplied. Logs must not include source names or credential material by default.

## Failure, restart and resource contract

The parent enforces elapsed time and requests cancellation, then terminates the owned helper
process if needed. It must not reuse the operation while process exit is uncertain. An I/O
blocked process or unknown publication result leaves an uncertain journal entry for explicit
reconciliation, not permission to seed or recursively delete a guessed path.

Cleanup traverses only retained owned directory descriptors and checked operation identities.
After restart, reconciliation first reopens the configured cache root and verifies its identity
and ownership, then checks manifest/journal operation tokens. Foreign entries are neither
adopted nor deleted. Interrupted operation directories cannot be assumed safe merely because
their names resemble a token. Disk exhaustion, fsync failure and publication collision fail
closed. Count, bytes, storage reserve and concurrency limits are configurable and measured.

## Required adversarial acceptance tests

- Deterministic A-stat/B-open/A-restore hook: B must never reach a completed snapshot.
- Rename/symlink replacement of each source ancestor during traversal: reject or remain
  anchored to the authorized root; never read an outside sentinel.
- Concurrent truncate, same-size overwrite and append during copy/re-hash: reject unstable
  sources and publish nothing. State explicitly what tree-wide consistency is not promised.
- Cache root/ancestor rename and operation-name replacement during writes/publication/cleanup:
  never write to or delete a foreign sentinel. Test real post-first-byte interruption, not
  only a pre-interrupted thread.
- Real quota exhaustion, ENOSPC, fsync failure, publication collision, cancellation, helper
  kill and restart reconciliation: no completed invalid snapshot and no broad cleanup.
- Logical traversal names, normalization/case collisions, duplicate aliases, hidden files,
  non-regular files and unsupported `openat2`: explicit refusal, not silent omissions.
- Required-only and grouped-plus-optional manifests match exact torrent members and hashes;
  actual isolated second-client transfer matches them and unchanged original fingerprints.

Run descriptor tests on Linux in GitHub Actions and actual-client tests only in isolated CI
or authorized disposable server staging. No local Docker, production mounts or live H2.
Do not claim #112 acceptance from this design or from a metadata-only creator test.

## Primitive-only evidence

On 2026-10-08 the exact synthetic test file was executed through SSH stdin on the
authorized Linux staging server: three tests passed in 0.001 seconds. Inputs were invented
inside `TemporaryDirectory`; normal cleanup completed. This proves only the three tested
descriptor primitives, not source authorization, `openat2` traversal, full copy stability,
publication/recovery or an actual provider. Local Windows execution skipped all three Linux
tests and is not acceptance evidence. The dedicated Ubuntu workflow repeats these tests
without Docker; its exact-head result must be checked before merge.
