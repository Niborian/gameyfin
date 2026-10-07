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

## Staging budget proposal

For the next isolated staging comparison, start with heap `512m`, metaspace cap `256m`,
and container limit `1536m`. This reserves 1024 MiB outside maximum heap for metaspace,
thread stacks, direct buffers, code cache, libraries, and margin. The scale fixture observed
approximately 449 MiB RSS; that observation does not size a full server. The proposed
container limit has not yet been tested with the real application image or library.
Do not apply this proposal to production until those runs establish enough native-memory
headroom, acceptable throughput, stable restarts, and no OOM/H2 closed-database errors.

Capture the candidate digest and full JVM arguments, sample both heap and container RSS,
record duration/throughput and scan errors, rehearse backup/restore under #26, and rerun
the previously interrupted scan with explicit affected-record counts. Keep real-library
results in a separate evidence record; synthetic figures cannot complete those criteria.
