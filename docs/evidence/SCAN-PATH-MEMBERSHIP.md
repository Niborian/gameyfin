# Scan-local path membership comparison

Refs #19 and #27. On 2026-10-09, the synthetic recovery benchmark was run before
and after replacing three repeated linear membership searches with two scan-local
HashSet indexes. These indexes are discarded after each scan; this does not improve
idle memory through a retained cache. They trade additional O(n) transient index
allocation for expected O(n) membership work instead of quadratic searches.

Both measurements used a fresh JDK 25.0.4.1 test JVM on the same Windows host,
the same 5,000-game / 10,000-variant / 30,000-content fixture, concurrency four,
and a 512 MiB maximum heap. The command was identical:

```powershell
./gradlew.bat :app:test --tests 'org.gameyfin.app.libraries.ScanMemoryBenchmarkTest.measure synthetic full scan and recovery' -PscanBenchmark=true -PscanBenchmarkHeap=512m -PscanBenchmarkGames=5000 -x :app:vaadinBuildFrontend --no-daemon --console=plain
```

| Observation | Baseline | Indexed membership |
| --- | ---: | ---: |
| Recovery duration | 6.404 s | 1.727 s |
| Games per second | 780.8 | 2,894.9 |
| Sampled peak heap | 149,728,760 B | 149,612,384 B |
| Process-lifetime peak RSS | 495,259,648 B | 479,301,632 B |

Duration was 73.0% shorter in this single pair; sampled heap was effectively flat.
The RSS difference is an observation, not proof of lasting memory savings. Heap is
sampled every two milliseconds during recovery and can miss short peaks; RSS is
the Windows process-lifetime peak working set, including fixture setup and mock
initialization, not recovery-only or idle RSS. No forced collection was used.
Duration includes completion polling and a final 50 ms guard-settle interval.

Each run injected one persistence failure, recovered with zero further failures,
and verified unchanged source-file hashes. Persistence, metadata processing and
grouping are mocked: this is CPU-path evidence, not real-image or production
acceptance. A separate behavior suite passed 31 filesystem tests, 18 scan-service
tests and this benchmark (50 tests, zero failures/errors/skips). Regression coverage
preserves duplicate removal ordering, ignored-path entity identity, scan failure
retention and recovery. The benchmark-only measurements exclude that combined
suite's lifetime RSS to avoid mixing preceding test allocations into the comparison.

Raw observations: [baseline](scan-path-membership-baseline-2026-10-09.json) and
[optimized](scan-path-membership-optimized-2026-10-09.json). Paired repeated real-image
workloads remain necessary before claiming resource regression or production readiness.
