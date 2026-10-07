# Authorized acquisition boundary

Gameyfin's library and request work must only be used for content that the operator is authorized to obtain: their own material, open-source software, public-domain works, or other content for which they hold the required rights.

This fork has an opt-in acquisition adapter, disabled by default. Synthetic HTTP regressions test refusal and lifecycle boundaries; a separate disposable GitHub Actions fixture exercises a real isolated qBittorrent/Prowlarr pair using an invented no-payload identity. Neither proves an operator's production isolation or authorizes enabling it. No production provider or credential is configured or discovered automatically. A game request is a review record, not authority to acquire a release. See [provider configuration, audit and staging gate](AUTHORIZED-ACQUISITION-PROVIDER.md).

## Non-negotiable limits

- Never add a workflow for a particular third-party release site or a way to bypass licensing, DRM, authentication, payment, rate limits, or other access controls.
- Never log, persist in application records, or expose provider credentials.
- Never turn an observed candidate into an automatic download. An administrator must deliberately approve any future provider action after reviewing the candidate and the operator's authority to obtain it.
- Never give a future integration broad control over an existing torrent client or its unrelated torrents.
- Never move, rename, delete, or rewrite torrent-managed source paths.

## Requirements before any provider integration

The Prowlarr/qBittorrent integration must remain disabled by default and requires explicit administrator opt-in, plus verified isolated-client/network/credential boundaries. All of the following must be reviewed before a real client is enabled:

1. A dedicated qBittorrent category and application-owned tags, so Gameyfin can identify only the work it created.
2. Least privilege through a dedicated client and separate credentials, enforced network isolation and filesystem mounts that cannot reach production or unrelated torrent paths. qBittorrent credentials are client-wide; category/tags do not restrict their API permissions. Credentials must be held only in deployment secrets rather than application data or logs.
3. A visible, administrator-approved set of indexers; unapproved indexers must not be searched.
4. An auditable request, candidate, approval, queue, cancel, retry, and import-review trail.
5. Fakes or a sandbox for integration tests; production providers need their own explicit opt-in.

Until those controls are implemented and reviewed, Gameyfin must not initiate provider, torrent-client, filesystem, or cleanup actions.

## Request cancellation and retry

The general status editor cannot queue a request or mark it downloading. Queuing requires a recorded candidate, an administrator approval record, and explicit candidate selection; this alone still performs no provider action. A separately explicit Add action is required when the isolated provider adapter has been deliberately enabled.

Administrator cancellation records a required reason and retains the request history. It refuses an already fulfilled, cancelled, or downloading request: changing a record cannot prove that an external download stopped. Retrying a failed or cancelled request returns it to `AWAITING_APPROVAL`; it does not queue work. Both actions record the actor, previous/new status, time, and reason.

Provider-backed records cannot be cancelled/retried/status-edited through the general editor. Owned transfer cancel means stop without deleting files; retry resumes the same persisted hash. Intent/audit and uncertainty reconciliation are described in the provider guide. Real isolated-client credential and integration proof remains a deployment gate.

## Scope policy and visibility

Administrators may configure positive numeric Prowlarr indexer IDs in Game Requests settings. An empty list approves none; wildcards, zero, nonnumeric, and overflowing IDs fail closed. Requests display approved IDs separately from verified active approved IDs. The active set remains empty while the provider is disabled.

Category and managed tag prefix are deployment-configurable, with generic safe defaults. The adapter applies the managed prefix plus request/candidate identity tags. Labels alone are not sufficient authority: the adapter verifies the exact persisted transfer hash and current client category/tags before any stop/resume/reconcile action, and refuses to adopt existing torrents.

The pure scope policy stores no credentials. Provider settings use external deployment secrets, never ConfigEntry/audit/UI. qB credentials are client-wide: use a dedicated isolated client rather than pretending category tags restrict credentials. Mock integration proves adapter boundaries; real isolated-client evidence remains required before #33 can close.
