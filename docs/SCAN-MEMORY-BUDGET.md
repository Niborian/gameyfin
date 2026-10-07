# Synthetic scan resource evidence

Issue #27 remains open pending a controlled real-library scan and verification of the
previously interrupted production records. These measurements run outside TrueNAS.

## Reproduction

Use JDK 25 with a fresh test JVM:

```powershell
./gradlew.bat :app:test --tests org.gameyfin.app.libraries.ScanMemoryBenchmarkTest -PscanBenchmark=true -PscanBenchmarkHeap=512m -PscanBenchmarkGames=1000 -x :app:vaadinBuildFrontend --no-daemon
```

Change `scanBenchmarkGames` to `5000` for the scale fixture. The test is disabled in
ordinary test runs. Its JVM has `-Xmx512m` and `-XX:MaxMetaspaceSize=256m`; the Gradle
daemon is separate from measured test-JVM memory. Linux CI publishes the JSON and JUnit
reports as `synthetic-scan-memory-evidence`.

The fixture uses the real filesystem scanner and full `LibraryScanService` orchestration.
Each game has two variants and six 32-byte content files. It injects one SQL exception,
waits for scan failure, reruns the full scan, and verifies game/variant/content counts,
updated-game metrics, and unchanged source paths and SHA-256 hashes. Metadata providers,
grouping, and persistence are mocked. It does not exercise H2, image processing, real
metadata payloads, HTTP traffic, downloads, or multiple simultaneous libraries.

## Recorded Windows measurements

Measured on 2026-10-07 with Temurin 25.0.4.1, scan concurrency four, scanner baseline
`5ae6c1b`. These are fixture observations rather than limits for production.

| Games / variants / contents | Sampled peak heap | Process lifetime peak RSS | Recovery scan wall time | Throughput | Recovery failures |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1,000 / 2,000 / 6,000 | 87.98 MiB | 379.48 MiB | 0.805 s | 1,241.77 games/s | 0 |
| 5,000 / 10,000 / 30,000 | 131.25 MiB | 448.76 MiB | 5.796 s | 862.74 games/s | 0 |

Each run first recorded one deliberate database failure and then completed the recovery
scan. The scale run initially checked source paths and sizes; the final fixture additionally
checks SHA-256 bytes. Heap is sampled every two milliseconds during recovery and may miss
very brief peaks. Windows `PeakWorkingSet64` and Linux `VmHWM` describe the entire test-JVM
lifetime, including fixture setup and mocking/instrumentation overhead. Wall time includes
completion polling and a 50-millisecond scan-guard settling interval. Reports retain raw
bytes and seconds so rounding does not replace the evidence.

The same final SHA-256 fixture was also run against baseline `5ae6c1b` and the bounded
scan scheduler from PR #104 (`07226a3`). Baseline peak sampled heap/RSS were
148,979,512 / 481,157,120 bytes with 5.357 seconds wall time. Bounded scheduler values
were 149,698,136 / 477,073,408 bytes with 5.413 seconds wall time. That is similar
overall resource use and runtime for this mocked workload: RSS was 0.85% lower,
sampled heap 0.48% higher, and wall time 1.05% higher. A single pair cannot establish
a meaningful heap/RSS improvement or a throughput guarantee. The deterministic
pending-task reduction is separate evidence. See the raw comparison JSON; repeat
representative workloads before making a release-readiness claim.

## Isolated real-image and H2 comparison

A one-time isolated Linux staging run on 2026-10-07 used the running image clone
`sha256:b031128b79ce56a7a7ea59498d1894ae5a28b0309952bd917ee7c3f9936d3729`
and reviewed candidate source `0f152220a5c73b938312f43e464291fd1dfb9511`, image ID
`sha256:e0508fd21796cac8b1b9d34d750dd9ec1fdd1f126d19d69460185a78d02e1258`.
The candidate was exported from CI, not promoted to a release tag. It predates the
bounded scheduler, so these measurements are not evidence of PR #104's memory savings.

Both images started from the same offline synthetic H2 database and fixture: four
libraries containing 104/27/1/0 games, 132 pinned variants, 264 grouped-base/optional-patch
content rows, two discoverable versions per game, and 924 source files. External metadata
IDs were empty; no external provider traffic or torrent plugin was available. This exercises
actual H2 persistence and application scan endpoints, not a production library replica.

| Image / full scan | Completion-poll wall time | Sampled JVM RSS bytes | Sampled heap bytes | Completed / failed libraries |
| --- | ---: | ---: | ---: | --- |
| Running-image clone / first | 7.944 s | 544,067,584 | 163,064,416 | 4 / 0 |
| Candidate / first | 7.968 s | 557,756,416 | 163,836,688 | 4 / 0 |
| Running-image clone / repeat | 3.273 s | 547,115,008 | 165,802,248 | 4 / 0 |
| Candidate / repeat | 3.369 s | 562,941,952 | 168,390,120 | 4 / 0 |

All source paths and SHA-256 hashes stayed unchanged. The clone produced two default
variants per game after a full scan; the candidate retained exactly one pinned default.
Exact grouped downloads returned the same selected files and hashes on both images.
The candidate's repeated full-scan RSS was approximately 2.9% higher, not lower, and its
wall time approximately 2.9% higher. One paired sequence is insufficient to establish a
production regression threshold or guarantee. RSS/heap sampling can miss brief peaks;
wall time includes completion polling and reading the final game list.

## Staging budget proposal

For the next isolated staging comparison, start with heap `512m`, metaspace cap `256m`,
and container limit `1536m`. This reserves 1024 MiB outside maximum heap for metaspace,
thread stacks, direct buffers, code cache, libraries, and margin. The scale fixture observed
approximately 449 MiB RSS; that observation does not size a full server. The proposed
container limit was also tested with the real images and synthetic H2 workload above,
using two CPUs, no published ports, and no external network. Both completed without an
OOM or automatic restart, with sampled JVM RSS below 563 MB. This does not prove the
budget is sufficient for production metadata, large images, downloads, or torrent traffic.
Do not apply this proposal to production until those runs establish enough native-memory
headroom, acceptable throughput, stable restarts, and no OOM/H2 closed-database errors.

Capture the candidate digest and full JVM arguments, sample both heap and container RSS,
record duration/throughput and scan errors, rehearse backup/restore under #26, and rerun
the previously interrupted scan with explicit affected-record counts. Keep real-library
results in a separate evidence record; synthetic figures cannot complete those criteria.
