# Host-owned torrent creation journal (#112)

This is a deliberately inactive architecture slice. It exposes no browser endpoint, enables
no provider, makes no client call and adds no torrent. It does not close #112.

The host, not a PF4J plugin, owns authorization, bounded snapshot construction, persisted
operation identity, metadata validation and eventual retention. `TorrentMetadataBackend`
is a narrow plugin-api extension: it receives an owned snapshot UUID/digest and operation
UUID, not a library or torrent-managed source path. An eventual adapter must resolve that
UUID only below its dedicated read-only snapshot mount and explicitly disable auto-seeding.
It returns a bounded-readable metadata stream through a task identity. The interface offers
no torrent add/start/delete/adoption operation and needs no app dependency in the plugin.

`TorrentSeedIntentJournal` records a separate client identity and snapshot identity, with a
unique client/snapshot pair. Repeated reservation returns the same row; a different manifest
cannot reuse that identity. Reservation is not authorization or proof that a snapshot exists:
the future host orchestrator must validate its owned snapshot registry and rights first.

Every journal mutation uses an independent committed transaction. Beginning creation locks
the row, records a generated operation token, and moves `DRAFT` to `CREATION_IN_FLIGHT`
before a future adapter call. A stale token or repeat begin is refused. Ambiguous completion
becomes `CREATION_UNCERTAIN`; neither retry nor late success is accepted automatically.
`METADATA_PENDING_VALIDATION` records only the task ID, SHA-256 and bounded size, not
successful content validation or permission to seed. `REFUSED` is only available before
creation and cannot erase an external operation. The additive migration also constrains
state/token/metadata consistency. No endpoint, password, tracker credential or source path
is stored in this journal.

Focused H2 tests exercise committed rows across service recreation, outer-transaction
rollback, serialized concurrent creation, manifest rebinding, foreign identities, stale
completion and ambiguous failure. This is database journal evidence, not a physical server
restart or actual client recovery/transfer rehearsal.

Before active wiring, the next reviewed work must provide:

- Safe snapshot acquisition with opened-file identity and enforced cache ownership;
  the reviewed creator fixture alone does not provide a production-safe copy builder.
- Exact bounded metadata/path/piece validation against the host-owned immutable snapshot,
  including fail-closed refusal of unsupported selected hidden files.
- Verified task-to-operation binding and recovery after response loss; never discover or
  adopt unrelated client torrents by category/tag alone.
- A deliberate validated stopped-add/start flow, persisted exact info-hash ownership,
  dedicated client/configuration identity, and an isolated second-client transfer test.
- Restart/ambiguous-response recovery, non-destructive stop and reference-counted snapshot
  retention with measured capacity/I/O limits, tracker authorization/revocation and peer
  exposure policy. No deletion call may target torrent-managed originals.

Only then may a separate administrator-opt-in provider be enabled. Existing torrent and ZIP
providers stay available; production configuration, installed plugins and `latest` remain
unchanged. See also [the download architecture](QBITTORRENT-TORRENT-DOWNLOADS.md).
