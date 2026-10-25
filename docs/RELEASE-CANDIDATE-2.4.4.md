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
- The earlier extension-only 99.3% inventory was incomplete and is superseded by
  the exact Git-blob inventory below. No language masking or filler was added.
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

### Transport compatibility gate

Installed Hilla/Atmosphere review subsequently identified another blocker: the
WebSocket frame dispatch restores authentication but does not bind Spring servlet
request attributes. The new privileged-session guard must therefore capture the
actual trusted transport request/session without weakening its checks; otherwise
legitimate administrator subscriptions are rejected. The focused fix decorates
only the existing Hilla handler through Atmosphere's public registration API,
retaining its mapping, broadcaster and interceptors. A non-inheritable request
binding exists only around the handler invocation and is cleared in `finally`.
The transport principal must be the captured authentication by reference; fresh
account, persisted session and registry checks are unchanged. Independent source
and installed-API review found no blocking issue in this fix.

Five focused regressions exercise installed Hilla message parsing and dispatch
through the modified Atmosphere registration, absent persisted sessions, mismatched
principals, exception cleanup and registration preservation. Endpoint reflection
dispatch and account lookup are controlled collaborators. All five passed locally
with the twelve existing configuration-boundary cases (17/17, zero skips). The
initial fixture used an interface mock where Atmosphere requires its concrete
resource type; the corrected fixture passed without changing production guards.
These cases do not claim a real socket handshake, browser or OIDC provider test.
Exact-head verification is required before merge and publication.

## Tooling composition audit

The corrected inventory includes the extensionless Gradle wrapper, Windows wrapper,
Dockerfile, shell/PowerShell and CSS/HTML as well as Kotlin/TypeScript. Before the
last cleanup this was 3,352,643 / 3,388,702 bytes (98.9359%). Two redundant image
builder wrappers accounted for 4,031 Git-blob bytes; removing them leaves
3,352,643 / 3,384,671 bytes, **99.0537% Kotlin/TypeScript**. This counts tracked
blob sizes, not working-copy line endings or an assertion about GitHub's exact
Linguist result. No `.gitattributes` overrides were introduced.

SQL migrations and XML/MJML templates remain untouched. They are data languages
in [GitHub's language definitions](https://github.com/github-linguist/linguist/blob/main/lib/linguist/languages.yml),
not executable-language bytes in this inventory; assets, configuration and prose
are likewise not programming-source bytes. Existing runtime shell scripts and
both Gradle wrappers remain counted. This does not claim 99% of every repository
file or total asset/data size.

Reference review found only two documentation consumers of the deleted builders;
both now use the normal Gradle build and existing reviewed image workflow. CI,
Dockerfile, runtime entrypoint, signing and recovery behavior do not invoke them.

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
