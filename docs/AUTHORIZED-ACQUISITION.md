# Authorized acquisition boundary

Gameyfin's library and request work must only be used for content that the operator is authorized to obtain: their own material, open-source software, public-domain works, or other content for which they hold the required rights.

This fork does not implement an external acquisition provider today. In particular, it does not connect to Prowlarr or qBittorrent, search indexers, submit torrents, or store provider credentials. A game request is a review record, not authority to acquire a release.

## Non-negotiable limits

- Never add a workflow for a particular third-party release site or a way to bypass licensing, DRM, authentication, payment, rate limits, or other access controls.
- Never log, persist in application records, or expose provider credentials.
- Never turn an observed candidate into an automatic download. An administrator must deliberately approve any future provider action after reviewing the candidate and the operator's authority to obtain it.
- Never give a future integration broad control over an existing torrent client or its unrelated torrents.
- Never move, rename, delete, or rewrite torrent-managed source paths.

## Requirements before any provider integration

Any future Prowlarr or qBittorrent integration must be implemented in a separately reviewed, milestone-backed change. It must remain disabled by default and require an explicit administrator opt-in. That change must provide all of the following before a real client call is enabled:

1. A dedicated qBittorrent category and application-owned tags, so Gameyfin can identify only the work it created.
2. Least-privilege credentials restricted to that dedicated scope, with credentials held only in deployment secrets rather than application data or logs.
3. A visible, administrator-approved set of indexers; unapproved indexers must not be searched.
4. An auditable request, candidate, approval, queue, cancel, retry, and import-review trail.
5. Fakes or a sandbox for integration tests; production providers need their own explicit opt-in.

Until those controls are implemented and reviewed, Gameyfin must not initiate provider, torrent-client, filesystem, or cleanup actions.

## Request cancellation and retry

The general status editor cannot queue a request or mark it downloading. Queuing requires a recorded candidate, an administrator approval record, and explicit candidate selection. No provider is enabled by this change.

Administrator cancellation records a required reason and retains the request history. It refuses an already fulfilled, cancelled, or downloading request: changing a record cannot prove that an external download stopped. Retrying a failed or cancelled request returns it to `AWAITING_APPROVAL`; it does not queue work. Both actions record the actor, previous/new status, time, and reason.

Dedicated category/tags, administrator-approved indexers, restricted provider credentials, and provider-side cancellation/retry remain prerequisites before external acquisition can be enabled.

## Scope policy and visibility

Administrators may configure positive numeric Prowlarr indexer IDs in Game Requests settings. An empty list approves none; wildcards, zero, nonnumeric, and overflowing IDs fail closed. Requests display the configured approved set separately from the active set. The active set remains empty and the provider disabled because no acquisition adapter is installed.

The reserved category is `gameyfin-acquisition`. A future adapter must apply `gameyfin-managed`, `gameyfin-request-<id>`, and `gameyfin-candidate-<id>` tags. The tested scope policy rejects an unapproved indexer or a torrent without the exact category and request/candidate tags. These checks are necessary, but labels alone are not sufficient authority to modify a torrent: a reviewed adapter must also verify the torrent hash against the persisted submission audit before any provider-side cancellation or retry.

The policy stores no credentials and cannot contact a client. A future deployment must use secrets and a dedicated isolated client/credential boundary that cannot access unrelated torrents. This configuration does not demonstrate restricted credentials or provider-side behavior; sandbox integration evidence is still required before #33 can close.
