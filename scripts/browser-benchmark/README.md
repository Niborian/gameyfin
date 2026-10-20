# Original-UI scan benchmark (#27, partial #61 evidence)

Diagnostic continuation after a successful normal login POST uses one unmeasured
identity-verified normal profile UI navigation to verify the exact fixture identity
in the original ProfileMenu (not protection of the profile route itself).
This same unmeasured preparation applies to both arms and is not a pure metadata
cold-cache claim. Automatic redirect observation and fixture-profile proof are retained separately
for every browser. Optional automatic-landing checks start only within the first
three seconds; each query and the following diagnostic profile probe share the
original 15-second post-submit deadline (not a separate three-second CDP timeout).
Absence during that observation interval is unaccepted evidence,
not proof of a full 15-second standalone login failure. It remains a blocking UX finding:
profiling may continue, but `cleanAcceptance` cannot pass unless every before/during
and post-300-second browser observed the automatic redirect. This does not bypass
authentication or repair the original UI. The same post-submit 15-second and total
90-second deadlines remain in force; no cover is preloaded by the profile check.
Profile-proof failures retain only a fixed substage and boolean DOM/base/identity
conditions plus a none/one/multiple trigger-count category. No profile field values,
page text, URLs, base URL values, cookies or credentials are recorded. These checks
reuse existing polls and do not relax identity verification or extend deadlines.
The already-observed exact user-info response may additionally be classified as
not-observed/null/fixture-user/other/invalid/failed after accepted normal login.
Its bounded private body is never retained; only the fixed category is recorded.
This is not a substitute for the original UI identity gate and adds no HTTP request.
Cookie transport diagnostics observe only the existing CDP extra-info events and
retain eight nullable booleans. Missing, malformed or overflowed observations remain
unknown, not proof of absent cookies. Cookie/header names and values are never
retained, and no cookie API, new HTTP request or authentication bypass is added.
The pending-RPC snapshot and later session-cookie observation are hypotheses-only
timing diagnostics: later means a strictly greater CDP response timestamp, not
event-arrival order. They do not add a wait, retry or change application authentication.
Profile-menu interaction targets exactly one visible original navbar trigger wrapper
containing exactly one small avatar button, rather than assuming an ARIA attribute
is placed on that wrapper. Missing or ambiguous wrappers still fail closed; the
fixture identity, administrator/menu and automatic-redirect acceptance gates remain.

This is an opt-in scientific fixture, not deployment or release promotion. It
never publishes an image, moves `latest`, connects to TrueNAS, reads production
H2, or mounts game/torrent source paths. GitHub-hosted Linux is the only runtime.
Local unit tests do not activate Docker. The workflow requires explicit parent
review followed by the `bench-ok-<full exact PR head SHA>` label on a same-repository
PR or the manual reviewed input with `reviewed_head` equal to that workflow SHA.
The event label must match the current full head, so retained labels and unrelated
label events cannot authorize newer unreviewed code. Adding the label is an
execution gate, not a test result.

## Paired source inputs

Control: `5a6f51442157fd4bcd1041997fb5560f665f7278`.
Candidate: `81f85eb4c5c9cfe5e0c4b4cb0b9420ea9e04f798`.

These are the historical control (`792a8aa`) and candidate (`f0c2870`)
with the identical reviewed #154 authentication-startup lifecycle patch and
single-flight initialization correction applied to each. The control includes
the exact candidate correction cherry-picked onto its separate backend source.
Both therefore use the same corrected original Gameyfin UI; this is
not a comparison of the auth fix against the old UI. The benchmark still
requires byte-identical UI, configuration, and dependency entries in both
independently built JARs. Source inspection alone does not establish that build
parity or resolve the earlier anonymous-session runtime failure.

Both complete original Gameyfin applications are built separately. The only
scientific build input override is the identical recorded historical candidate
(`f0c2870`) commit instant, `2026-10-11T18:51:55Z`, supplied to Spring Boot 4.0.3's build-info task
by the CI-only init script. Its SHA is retained. No JAR/source/resource bytes are
rewritten and no resource differences are silently excluded. Every non-code
resource and nested dependency entry is hashed; disagreement fails before any
runtime. ZIP timestamp differences are not resource bytes.
The workflow verifies that historical timestamp separately from both new exact
source commit IDs; it does not claim the auth-fix commits have the old date.

## Isolation and budgets

The whole sequential suite has a 9,000-second outer process limit and a
180-minute hosted-job envelope, including builds and cleanup. The previous
3,300-second limit could not cover the roughly 190-second per-app
startup/bootstrap estimate from run #38563298028 across seven app instances plus
six matched 60-second idle and 300-second recovery windows, scans, and browser
work. Seven existing 1,200-second app watchdogs account for up to 8,400 seconds
before orchestration
overhead. This is an overall execution envelope, not a performance result or
permission to extend an individual operation: all three balanced pairs, the
held case, recovery windows, app/browser watchdogs, and resource limits remain
unchanged. No network-idle or artificial pre-login sleep is added.

One app at a time: 0.5 CPU, 1 GiB memory/swap ceiling, 320 MiB G1 heap, PID256,
private cgroup, nonroot1337, capabilities dropped, internal private network and
no published ports. Fresh synthetic132-game/924-file fixture for every arm;
normal admin setup/login and explicit THIRD_PARTY plugin enable lifecycle.
Sources and original synthetic cover bytes mount read-only. DB/data/plugin
scratch lives only in the fresh marked fixture leaf.

The browser is a **separate** container with 1 CPU and 768 MiB ceiling/PID256;
browser resource counters are reported separately, never attributed to app
memory savings. It uses the digest-pinned official Puppeteer image, UID10042,
read-only root filesystem and ephemeral tmpfs profile. Official sandboxing
requires SYS_ADMIN; Chrome's sandbox chroot also requires SYS_CHROOT, normally
retained by Docker but removed by drop-all. Only this ephemeral hosted browser
drops all capabilities then restores exactly those two. No privileged mode, Docker socket, host PID/network/IPC,
host files, `--no-sandbox`, or server/local-Docker equivalent is permitted.
An actual `chrome://sandbox` adequate-sandbox gate is mandatory, not inferred
from flags. Rendering is fixed1280×800, en-US, UTC.

The browser origin barrier allows only literal loopback forwarding to the
fresh-inspected fixture container's8080 port, including exact-authority
WebSocket CONNECT. External hosts are rejected before DNS; Chrome has no proxy
DIRECT fallback and no nonproxied WebRTC API. Private credentials enter attach
stdin only. The fresh browser profile can temporarily contain its own fixture
cookies; it is never a user's profile and disappears with the owned container.
No HAR, cookies, passwords, APP_KEY, raw application logs or DB are artifacts.

## Measurements and interpretation

Three ordinary order-balanced pairs run sequentially, A/B, B/A, A/B. Each arm
uses successful deterministic metadata, full scans, exact selection ZIP and
cover200/304 checks, original-UI library/game navigation with decoded byte-exact
cover, and300-second natural memory/CPU recovery. No forced GC, cache dropping,
provider delay or scheduler change in this throughput case. Cold browser jobs
have fresh profiles; post-recovery app/server caches are warm. Navigation timing,
cover decode/cache and app resource counters are distinct measurements.

A separate opt-in waiting-provider case proves causal UI navigation during a
bounded known provider wait. It is **not CPU-heavy throughput or resource-win
evidence**. Gauge brackets in ordinary cases do not prove continuous activity;
unknown overlap and timeout remain explicit nonacceptance, not successful fast
requests. Six synthetic cases do not establish production scan/cutover readiness.

The host starts an independent exact-ID browser deadline watchdog before start.
Normal cleanup and workflow-final cleanup are additional fences. No process-name
kill, broad prune, or guessed mount cleanup is used. Failed/uncertain cleanup
must fail the report. Actual Linux sandbox/browser/original-app execution remains
unverified until the reviewed CI run passes; source and guard tests are not a
substitute.

The first reviewed CI run [38182465997](https://github.com/Niborian/gameyfin/actions/runs/38182465997)
passed both original-UI source builds and strict parity (473 resource/dependency
entries), but failed in the first control case before scan measurements. Its
cleanup guard also failed; exact cleanup was not proven. No performance result
is inferred from that run. Docker 28 canonicalizes capability names to `CAP_`
spellings ([Moby change](https://github.com/moby/moby/pull/48551)); guards now
compare exact semantic sets, rejecting extras and duplicate aliases, without
changing any grant or limit. Sanitized pre-guard receipts retain numeric limits,
known capability/security categories and bounded browser failure phases only.
Exact immutable ownership, mount and namespace cleanup authority remains strict;
a numeric performance-contract mismatch does not prevent removing a proven
owned isolated container. Unknown scope is retained and reported as failure.

Run [38185335275](https://github.com/Niborian/gameyfin/actions/runs/38185335275)
completed application fixture initialization, then failed at Chrome launch;
final cleanup succeeded. Its receipt confirms canonical `CAP_SYS_ADMIN` and the
unchanged browser limits. No scans or performance results were obtained. Chrome's
XDG configuration/cache directories now reside in the same private `/tmp`
profile as its user data, following [official read-only-container guidance](https://pptr.dev/next/troubleshooting#running-in-read-only-containers).
HOME and Puppeteer's installed executable/cache resolution remain unchanged.
This addresses a source-backed startup requirement, not a proven unique cause
of that run's generic launch failure; actual sandbox/UI acceptance is pending.

The workflow now runs a separate Chrome-only startup diagnostic before expensive
application builds. It uses the same pinned browser image, permissions, readonly
root/tmpfs and CPU/RAM/PID budget on an isolated internal network with no app,
authentication, host mounts or published ports. A durable creation intent and an
independent 30-second cleanup fence survive controller cancellation. Supported
Puppeteer browser-process launch exposes exit code/signal; only fixed fatal
booleans and numeric cgroup PID/OOM counters are retained. Signals may include
diagnostic shutdown and are not automatically classified as application crashes.
The smoke is not a performance comparison; normal benchmark launch is unchanged.

Only this app-free, authentication-free smoke may retain a redacted fatal excerpt
(at most 16 ASCII lines and 2048 bytes). Chrome receives a constant environment
whitelist; credential-like lines are rejected, and URLs, paths, addresses and
long identifiers are removed. The normal authenticated benchmark still retains
only structured error categories, never error text.

The reviewed smoke [38471659367](https://github.com/Niborian/gameyfin/actions/runs/38471659367)
finally identified `sys_chroot` failure before cleanup (SIGABRT, no deadline or
PID/OOM events). [Pinned Chromium 155.0.8059.39 credentials.cc](https://raw.githubusercontent.com/chromium/chromium/155.0.8059.39/sandbox/linux/services/credentials.cc)
uses this call to enter its safe empty sandbox root; [Docker's documented default capabilities](https://docs.docker.com/engine/containers/run/)
include SYS_CHROOT. Explicit review approved restoring only SYS_CHROOT beside
existing SYS_ADMIN in hosted-CI browser/smoke containers, with exact-set guards.
Nonroot, no-new-privileges, readonly root, private networking, no host mounts,
limits and the actual sandbox gate remain unchanged. This is CI fixture startup
repair, not a production change or proof of successful startup/performance.

The subsequent smoke passed actual startup/sandbox checks but recorded 29 PID
limit denials at PID128. Review raised only the separate browser/smoke cap to
PID256 (CPU1/RAM768 MiB unchanged; application cap unchanged). Normal browser
failures now retain fixed authentication-stage and numeric PID/OOM counters,
never credentials or page/error text, to distinguish authentication from resource
pressure. Neither startup success nor this fixture budget change proves an app
performance improvement.

Partial workload evidence is atomically retained after idle settling, each
completed scan phase and recovery. A later authentication/validation failure
does not erase earlier CPU/RAM or navigation status. `complete: false` marks
unfinished workloads, and all functional, responsiveness, final-health and
exact-cleanup acceptance gates remain required; partial data is not a paired win.

Each successful fresh browser uses the application's normal Profile menu Sign
Out action immediately after its measured sample, then proves return to the
login form before exact container removal. Closing Chrome alone did not retire
server sessions under the application's existing three-session limit. Logout
never uses a direct API/POST or changes authentication policy. Navigation timing
and browser resource endpoints precede logout; during-scan activity gauges bracket
the complete navigation-plus-logout operation, while app scan CPU/sampling
includes logout work. Navigation and verified logout share the same 15-second
operation deadline; the 90-second browser watchdog remains. Logout failure rejects acceptance but
still attempts exact container removal and retains prior partial evidence.

Primary references: [official Puppeteer Docker sandbox](https://pptr.dev/guides/docker),
[Chromium proxy semantics](https://chromium.googlesource.com/chromium/src/+/HEAD/net/docs/proxy.md),
[Spring Boot build-info properties](https://docs.spring.io/spring-boot/gradle-plugin/integrating-with-actuator.html).
