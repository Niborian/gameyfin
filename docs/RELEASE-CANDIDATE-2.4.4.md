# 2.4.4 candidate — 2026-10-25

Status: preparation only, not deployment approval. Keep the existing
`ix-gameyfin / gameyfin` service and its authentication, mounts and source paths.

## Included changes

- Kotlin/TypeScript tooling cleanup (#158), clearer per-variant editor and bounded
  read-only local detection (#159), isolated plugin configuration security fix (#157).
- Build, UI and lockfile version `2.4.4`; do not overwrite the published `2.4.3`.
- Signing passwords supplied through jarsigner's environment option, not process arguments.

## Verification record

- Normal production build with Java 25 passed before the version increment, including
  Kotlin compilation, Vite bundling and signing all five existing plugins.
- Existing TypeScript frontend/auth/report checks: 80 passed, no skips.
- Focused Kotlin selection: 160 cases; initially 149 passed, 8 Windows filesystem
  skips and 3 failures caused by missing test-only encryption setup. The corrected
  account-policy class was rerun: all 3 passed. No production key was used in tests.
- The four actual H2/Flyway variant-details tests passed, including stale saves,
  manual protection, preview with no writes and apply preserving existing source
  and download-related fields. All 39 existing download-service checks passed.
- Windows skipped seven secure-directory detector cases and one symlink case.
  Linux execution is still required; skips are not evidence of those behaviors.
- Recognized tracked source-byte inventory was approximately 99.3% Kotlin/TypeScript,
  including CSS/HTML and shell wrappers in the denominator; this is not GitHub Linguist.
- No local Docker, server workload, live scan or production configuration change.

The normal `2.4.4` production build and TypeScript type check passed; all five
plugin JAR signatures verified. That local build predates the subsequent review
fixes and is not the shipping artifact. Release preparation must use the reviewed
CI image and its immutable registry digest, not an old local JAR with the same version.

Linux verification on `d24cbf84` passed: 160/160 focused Kotlin tests, zero skips,
80 frontend/auth/report checks, plus the hardlink, scan and local detector evidence
gate with configuration caching. Run:
https://github.com/Niborian/gameyfin/actions/runs/40142010399
The initial gate's configuration-cache capture bug was fixed without weakening its
checks. Subsequent commit `9aef2687` changes only the existing CodeQL workflow to
allow manual production-class analysis without full tests/packaging. Candidate
CodeQL passed on `9aef2687`, and the branch open-alert query returned none:
https://github.com/Niborian/gameyfin/actions/runs/40142151194

Generated bundle outputs remain local build products, not additional server versions.
No image has been published, no latest tag changed and no production smoke check run.
The remaining release steps are source review/integration, separately approved image
publication with provenance/SBOM, and approved maintenance/smoke checks below. Green
builds and static analysis alone are not full browser/OIDC or production acceptance.

## PR review follow-up

PR #160's real provider fixtures exposed a Kotlin port mismatch: both qBittorrent
images returned login HTTP 204 while the helper required 200. The helper now accepts
the successful response while requiring an unexpired session cookie and authenticated
version HTTP 200. Five small loopback protocol cases passed; both pinned real-provider
fixtures subsequently passed on `ea3f33a6`.

GitHub review also identified two valid blockers: a default/list refresh could unmount
a dirty variant editor, and the offline backup task captured Gradle script state.
The editor now preserves a valid selected variant and existing drafts across those
refreshes; focused checks exercise the actual transpiled modal. The offline task
constructs its classpath during configuration and captures only a file property in
its execution guard. Cache store/reuse is checked by intentionally invalid arguments,
refused by the CLI before any environment, path, lock or database access. This is
task-dispatch/cache evidence, not a new production backup rehearsal.

The normal image check no longer requests a five-minute post-task idle sample.
Correctness, interruption recovery, exact selections and source-integrity checks
remain enabled. The user approved version-only `2.4.4` publication after review and
CI pass; this does not approve latest promotion or deployment.

## Cutover checklist — requires separate approval

1. Review the exact source head and successful focused Linux checks; check candidate
   security analysis, image provenance/SBOM and UI/image version parity. Build and
   publish only the explicitly approved immutable candidate, not an automatic latest move.
2. Confirm the existing protected rollback image/config/key set and the October 9
   backup/restore record under #26. Obtain a fresh consistent cold backup at approved
   maintenance time; never copy/open the running live H2 file.
3. Update the same `gameyfin` service to the approved digest without changing its
   mounts, authentication, hostname, ports or other applications. No extra tester names.
4. Perform a brief approved smoke check: health/startup and migration logs, OIDC login,
   browse/images, administrator variant preview/save/apply, exact optional/grouped
   download, ordinary-user plugin metadata without privileged configuration.
5. On failed health/migration/auth/download checks, stop the candidate and restore the
   matched old image plus pre-upgrade database/config/key. Never run the old image
   against a migrated database. Preserve source paths/bytes and record the outcome.

No new benchmark campaign or resource stress run is a prerequisite of this plan.
Real OIDC/browser acceptance and a production cutover have not been performed here.
