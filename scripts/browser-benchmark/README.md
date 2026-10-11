# Original-UI scan benchmark (#27, partial #61 evidence)

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

Control: `792a8aaacbc410e6f716ff23c1e38d77f14ba17e`.
Candidate: `f0c2870ef17d02b4d71bc9fe0170bb89a098a25f`.

Both complete original Gameyfin applications are built separately. The only
scientific build input override is the identical recorded candidate commit
instant, `2026-10-11T18:51:55Z`, supplied to Spring Boot 4.0.3's build-info task
by the CI-only init script. Its SHA is retained. No JAR/source/resource bytes are
rewritten and no resource differences are silently excluded. Every non-code
resource and nested dependency entry is hashed; disagreement fails before any
runtime. ZIP timestamp differences are not resource bytes.

## Isolation and budgets

One app at a time: 0.5 CPU, 1 GiB memory/swap ceiling, 320 MiB G1 heap, PID256,
private cgroup, nonroot1337, capabilities dropped, internal private network and
no published ports. Fresh synthetic132-game/924-file fixture for every arm;
normal admin setup/login and explicit THIRD_PARTY plugin enable lifecycle.
Sources and original synthetic cover bytes mount read-only. DB/data/plugin
scratch lives only in the fresh marked fixture leaf.

The browser is a **separate** container with 1 CPU and 768 MiB ceiling/PID128;
browser resource counters are reported separately, never attributed to app
memory savings. It uses the digest-pinned official Puppeteer image, UID10042,
read-only root filesystem and ephemeral tmpfs profile. Official sandboxing
requires SYS_ADMIN: only this ephemeral hosted browser drops all capabilities
then adds that one. No privileged mode, Docker socket, host PID/network/IPC,
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

Primary references: [official Puppeteer Docker sandbox](https://pptr.dev/guides/docker),
[Chromium proxy semantics](https://chromium.googlesource.com/chromium/src/+/HEAD/net/docs/proxy.md),
[Spring Boot build-info properties](https://docs.spring.io/spring-boot/gradle-plugin/integrating-with-actuator.html).
