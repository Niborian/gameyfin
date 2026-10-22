# NEVER MERGE — public synthetic case1 diagnostic manifest

Base: `678b14f3afc799dde28cf9e4ddd24b1190f7194e`.
Branch: `codex/public-auth-case1-20261023`.

Only the registered temporary `.github/workflows/browser-scan-benchmark.yml` is
changed outside this directory. It dispatches only on this diagnostic branch after
an explicit reviewed boolean and exact full-head match. Actions are pinned to the
same checkout/upload commits as the base; permissions remain contents:read/actions:read.

Public synthetic allowlist copied from the reviewed draft:

- `browser_entry.draft.mjs`, `case1_public_auth.mjs`, `case1_protocol.mjs`;
- `hosted_browser_case1.py`, `controller_public_case1.py`, `owned_public_policy.py`;
- `Dockerfile.browser.draft`, `Dockerfile.browser.draft.dockerignore`;
- `test_case1_pipeline.mjs`, `test_case1_controller.py`;
- `README.md`, `browser_entry.exact.diff`, `hosted_browser.exact.diff`.

The two exact diffs compare the copied real transport files against the unchanged
base harness. This manifest is the only additional document. No application,
plugin, plugin-api, normal browser-harness source, private integration implementation,
environment file, actual receipt, credential, image archive or compiled resource is copied.
The directory name is historical; every admitted file here is public synthetic harness
code/documentation. The browser-only context denies everything except public browser
modules and the three specifically named case1 browser modules.

Narrow observation: pending catalog HTTP requests and observed WebSockets must settle
at the first enabled-login-form checkpoint. This is not all work, same-document React
cancellation, WebSocket admin-subscription absence, future request absence or full154
acceptance. Receipt validators explicitly reject those wider true claims.

Local limited checks:9 Node and7 Python tests pass; no browser/image/server/build is run.
Pattern audit found no GitHub token literals, private-key blocks, AWS access-key literals
or production-server address. Manual allowlist inspection confirms credentials are only
generated/transient fixture inputs or existing readonly GitHub workflow token references,
never fixed secret values. No runtime receipts or environment files exist in this change.

No push, dispatch, PR or merge is authorized by this manifest. Parent review must identify
the final exact diagnostic commit/tree before the next step. This workflow must NEVER MERGE.
