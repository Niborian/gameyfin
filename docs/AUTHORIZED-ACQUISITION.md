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
