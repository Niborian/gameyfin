# Isolated authorized acquisition provider

Only obtain user-owned, open-source, public-domain or otherwise authorized content. No release-site-specific behavior or licensing/access-control bypass is implemented. Request/Steam metadata is not permission to download.

## Opt-in deployment boundary

`ACQUISITION_ENABLED` and `ACQUISITION_DEDICATED_CLIENT_ACKNOWLEDGED` default to false. Existing provider origins are never discovered or reused. Never point this adapter at a client containing unrelated torrents or mounting production source paths.

qBittorrent credentials are client-wide, not category-scoped. Category/tags are **not** credential permissions. Least privilege requires a separate isolated qBittorrent instance and secret without access to production torrents/mounts, plus a dedicated restricted Prowlarr API instance and network policy restricting both origins. Configuration acknowledgment does not establish actual isolation; verify it in staging.

Use deployment environment/secrets, not command-line arguments, repository files, ConfigEntry records or logs:

| Variable | Meaning |
| --- | --- |
| `ACQUISITION_ENABLED` | Deliberate enable after reviewed staging evidence |
| `ACQUISITION_DEDICATED_CLIENT_ACKNOWLEDGED` | Explicit confirmation of isolated client boundary |
| `ACQUISITION_PROWLARR_URL`, `ACQUISITION_PROWLARR_API_KEY` | Dedicated origin and API secret |
| `ACQUISITION_QBITTORRENT_URL` | Isolated qBittorrent 5.x origin |
| `ACQUISITION_QBITTORRENT_USERNAME`, `ACQUISITION_QBITTORRENT_PASSWORD` | Isolated client credentials |
| `ACQUISITION_CATEGORY` | Pre-created dedicated category; default `gameyfin-acquisition` |
| `ACQUISITION_MANAGED_TAG` | Dedicated tag prefix; default `gameyfin-managed` |
| `ACQUISITION_SAVE_PATH` | Required explicit absolute isolated acquisition root on the dedicated client; no default |

No user-specific URL, port, path or secret is built in. Remote credentials require HTTPS; HTTP is only allowed on loopback for fixtures. Origins cannot embed credentials/path/query/fragment. Scope labels are bounded simple names. The adapter never changes client-wide settings or creates category/download paths. A category alone is not sufficient with manual torrent management: explicitly configure `ACQUISITION_SAVE_PATH`, pre-create the category with that same path, and isolate the server mount. Add supplies that exact save path with automatic management and separate incomplete download path disabled; subsequent client-reported paths must still match. Blank/relative/traversal/filesystem-root paths are refused. Path validation is remote syntax/identity validation, not proof of actual mount isolation; staging must prove that boundary.

## Review and deliberate add

The UI displays approved IDs separately from verified enabled approved IDs. Empty scope authorizes none. An administrator prepares a request for review and searches one active approved indexer. Only that indexer's results with one exact 40-hex v1 identity become persisted candidates. Prowlarr rewrites search magnet URLs into proxy URLs: the adapter never follows them, instead constructing a tracker-free magnet from the result's exact `infoHash`. Literal magnets are also accepted, but conflicting identity metadata is rejected. Arbitrary download URLs, invalid/v2-only/base32 identities and alternate literal identity parameters are unsupported. Prowlarr grab is never called. Tracker-required private content is not supported by this tracker-free acquisition boundary.

Approval/selection queues a review record only. Add is a separate explicit action, requiring a selected approved candidate, queued request, current active approved indexer and required authorization reason. The UI requires an authorization attestation for add/resume; server endpoints independently require administrator authorization and approval/selection. No scheduler performs adds.

The dedicated category must already exist before submission. The adapter adds initially stopped, verifies the exact hash/category/tags and stopped state, then starts only confirmed owned transfers. H2 enforces unique hash ownership; duplicate search hashes create one review candidate. Existing torrents are never adopted. HTTP redirects are refused, responses/time are bounded, and separate clients prevent qB cookies leaking to Prowlarr or Prowlarr API headers leaking to qB. Stop/start acknowledgment alone is insufficient: bounded re-reads must confirm the expected stopped/running client state or leave an uncertain outcome.

qB5.2 changed successful login/add acknowledgments to empty response bodies and uses port-qualified `QBT_SID_<port>` session cookies instead of legacy `SID`. Canonical empty or legacy `Ok.` acknowledgments are accepted only with a recognized session cookie and authenticated version verification for login, and exact owned stopped-state verification before starting an add. Empty login without a session cookie, failure text, ownership/path mismatches and ambiguous outcomes still fail closed. Matching-origin Referer headers retain qB CSRF protection.

## Audit, stop, resume and uncertainty

An immutable actor/time/reason audit and `IN_FLIGHT` intent commit before HTTP. Concurrent adds are denied; operation tokens refuse stale completion of a superseded intent. A provider timeout or ambiguous outcome becomes `UNCERTAIN`, never a blind automatic retry. A crashed in-flight operation can be explicitly reconciled after 600 seconds, beyond the bounded sequential verification requests: verify exact persisted hash and ownership labels, then stop and confirm stopped state. An absent or mismatched identity stays uncertain for manual isolated-client inspection; no replacement is added.

A typed preflight refusal before the add request (for example missing category/root or rejected credentials) records `SUBMIT_REFUSED` and returns to review, allowing a fresh deliberate add after setup correction. Once add has been attempted, failures remain uncertain: no blanket exception handler resets potentially submitted work to review.

Cancel means **stop**, never delete. Resume means start the same persisted hash, never re-add. Both verify client category and all ownership tags before mutation. No delete API, deletion flag, source mount, rename, move, retirement or import action exists. Review-record cancellation/retry/status edits refuse provider-backed requests. Foreign keys preserve acquisition audit instead of silently deleting it with a request.

Changing scope configuration while transfers exist fails closed. Preserve original scope until owned transfers are stopped and reviewed; no automatic relabeling occurs. Protect H2/backup access: stored magnet metadata can contain tracker information even though provider credentials are absent from records.

## Evidence and remaining gate

Ephemeral-loopback HTTP fixtures and synthetic credentials exercise scoped search, actual login/add/stop/start requests, cookie/header isolation, exact hash ownership, refusal of existing/unrelated torrents, redirects, disabled behavior and HTTPS rules. Lifecycle tests cover approval, committed intents, duplicate refusal, uncertainty and reconciliation. Actual Flyway migration tests verify unique ownership and audit-preserving foreign keys.

Mock proof does not establish that a real deployment is isolated. Before enabling, rehearse with an actual isolated qBittorrent 5.x/Prowlarr pair and lawful fixture: verify API compatibility, category setup, secret/network/mount isolation, add/stop/resume, errors/restart/reconciliation, audit and unchanged unrelated/source files. No production provider/credential/source was accessed. Keep #33 and this PR draft until real isolated-client acceptance evidence is reviewed.

The `Isolated authorized acquisition acceptance` Actions workflow runs the real provider pair, pinned by immutable image digest, on a disposable runner only. `tools/acquisition-live-fixture.py` refuses local execution. Its Docker network has external egress disabled; its Torznab fixture returns an invented no-tracker hash with no seed or payload. Only fresh temporary configuration/download directories and masked synthetic secrets are used. The application adapter is exercised against the real pair for approved-indexer search, add, ownership/category/path verification, stop, resume and refusal of an already-existing hash. A client restart must preserve the stopped owned identity. The workflow removes only its exact containers/network/temp leaf and exports the JUnit result. A passing run complements the database lifecycle/audit/failure tests; it does not authorize production enablement or prove an operator's mount/network isolation.
