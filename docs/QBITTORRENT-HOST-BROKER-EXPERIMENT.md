# Host-to-descriptor helper experiment (#112)

Inactive synthetic code only. This is neither a deployable JVM helper nor an application
provider. It uses Linux Python `fork` for an isolated test process; the application image
does not gain Python or a new runtime flag. No app endpoint, plugin wiring, production
mount, source mutation or seeding permission is added.

`HostCatalogBroker` takes trusted configured source/cache roots and an invented fixed
catalog at construction. Constructor configuration is administrator/host authority, not
request data. It binds root device/inode identities before starting the helper. The helper
opens configured roots with `openat2` (no symlinks/magic links), verifies opened identities,
and keeps those descriptors for the entire session. Configured root mount points are
allowed, but selected members cannot cross mounts below a held root. Broad `/` source or
cache configuration is rejected before helper startup.

Each helper session creates random opaque root tokens. Selection callers provide only
variant/content IDs; the fixture host checks membership and automatically includes required
content before sending explicit relative members. They cannot supply roots, fingerprints
or an arbitrary verification object. This fixed catalog is a stand-in for real host
authorization, **not integration with Gameyfin's database, access rules or retirement leases**.
Root-path replacement after session establishment does not redirect subsequent acquisition.
This does not establish trusted/exclusive cache ancestry before construction or an atomic
cross-file filesystem snapshot; both remain separate activation requirements.

IPC is a private inherited socketpair, with no listening port or operator endpoint. Frames
are length-prefixed JSON bounded to 64 KiB. Requests contain unique request tokens, and
responses must bind the same token. Calls are serialized so concurrent requests cannot
consume one another's responses. Member count is bounded to 64, total copied bytes to
4 MiB. The helper captures each selected member's fingerprint through an opened descriptor
below the held root, then invokes the descriptor-copy experiment's copy/recheck guards.
Outputs use generated flat filenames; logical name preservation is not implemented.

The parent channel uses a five-second socket timeout. On lost channel, malformed/stale
response or ambiguous failure, the session is discarded and the owned process receives
termination; no retry, adoption, recursive cleanup or seeding follows. The two-second join
does not prove termination of kernel-uninterruptible I/O. Successful operation directories
remain private synthetic artifacts until test temporary cleanup. A crash after copying can
leave an uncertain artifact; this experiment has no persistent recovery journal.
Any exception once descriptor-copy execution begins closes the helper channel, including
cleanup permission errors and identity uncertainty. Only pre-copy validation refusals keep
the channel reusable; successful copying followed by response failure is also uncertain.

Fifteen integration tests use a real helper process and invented temporary files: required
and optional membership/bytes, unknown IDs, held-root path replacement, wrong root tokens,
symlink escape, root identity substitution, oversized frames, dead-helper refusal, send
budget, serialized concurrent callers/request identity, descriptor/socket cleanup faults,
malformed command refusal without channel poisoning, and host ID count/type refusal.
Three subsequent review-regression tests exercise real-helper operation-name substitution,
copy failure with cleanup `PermissionError`, and broad-root configuration refusal. Fixtures
explicitly close dead process handles before replacement/teardown so descriptor leak checks
cannot be obscured by delayed garbage collection.
Run on Linux with the reviewed
descriptor-copy module available:

```sh
python3 -m unittest discover -s tools -p test_snapshot_host_broker_experiment.py -v
```

On 2026-10-09 the root reviewer executed the exact modules via an in-memory SSH-stdin
harness on authorized Linux staging: 15 tests passed in 0.075 seconds. Tests asserted helper
processes were dead during teardown and used normal temporary-directory cleanup. No
persistent server payload was installed. This is synthetic integration evidence only;
exact-head Ubuntu CI and final independent review are required before merge.
The reviewer reran the updated eighteen-test suite through the same in-memory Linux harness:
18 passed in 0.100 seconds, with helper teardown and exact descriptor-count guards intact.

## Activation blockers

- Replace fixed test catalog with core authenticated selection and retirement-lease handling;
  revalidate catalog root ownership and reject unsupported paths without silently omitting them.
- Provide reviewed native packaging and process supervision suitable for the JVM/container;
  Python `fork` is not an application runtime contract.
- Preserve logical grouped/optional names, support safe directory enumeration and document
  cross-file consistency. Hardlinked source parity remains unsupported by the copy experiment.
- Persist non-overwriting publication/manifests, handle fsync/storage quotas and reconcile
  ambiguous process exit and artifact ownership after restart.
- Complete qBittorrent ownership/add/start/stop, isolated peer transfer, tracker admission,
  resource/retention evidence and independent exact-head review.

This experiment bridges fixture ID-based membership to a held descriptor session; it does
not solve the production Path-only API boundary or complete #112/cutover acceptance.
