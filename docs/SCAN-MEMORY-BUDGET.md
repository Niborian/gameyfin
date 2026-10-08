# Synthetic scan resource evidence

Issue #27 remains open pending a controlled real-library scan and verification of the
previously interrupted production records. These measurements run outside TrueNAS.

## Post-task natural idle observations

The reusable image runner accepts `--idle-seconds 300` or `900` for five/fifteen-minute
natural settling after scans, downloads and any external probe. CI requests five minutes;
the default runner remains disabled unless explicitly requested. Samples every fifteen
seconds retain JVM heap **used**, JVM process RSS, and cgroup-v2 container memory charge
as distinct byte values. Container charge includes anonymous memory, file cache and other
processes; it is not JVM RSS. Anonymous/file-cache charges are also recorded separately.
Unsupported/unavailable telemetry fails rather than fabricating zeroes. Samples are not
continuous peak measurements; observation itself has telemetry/process overhead.

The user's approximate goals are 500 MiB idle process RSS and 1 GiB for typical tasks,
with exceptional task peaks permitted. They are comparison targets, not hard acceptance
limits or a newly applied heap/container cap. Existing scan-phase metrics show sampled
task resource use separately from these post-task observations. No forced GC, artificial
cache purge, heap reduction or production setting change occurs. A synthetic idle sample
does not establish representative production idle behavior; repeated paired workload and
real-library measurements remain necessary before #27 or cutover acceptance.

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

## Repeatable actual-image H2 fixture contract

`scripts/rehearsal/seed-scan-fixture.py` generates these deterministic source files,
offline SQL and a SHA-256/download-selection manifest into a new output directory.
Its container fixture root is configurable; it does not open a database or start an
image. CI retains the SQL/manifest as synthetic inputs, not execution evidence.
Import `seed.sql` with the selected image's compatible H2 JAR only after its fresh
schema has migrated and the fixture application has stopped. Keep the original
database pristine for separate baseline/candidate restores. Source generation has
been locally exercised. The opt-in test imports the generated SQL into the actual
Flyway-migrated H2 schema and verifies four libraries, 132 games, 132 variants, 264
contents and 924 source files. Both this test and the 1,000-game mocked scan/recovery
passed on JDK 25 on 2026-10-07 (two tests, no skips or failures). Actual-image scan
execution remains separate; generated inputs plus schema validation are not an
end-to-end image runner. The earlier one-time fixture remains separate evidence.

The next CI comparison must consume immutable baseline/candidate image references and
their source revisions as inputs, rather than a mutable tag or any server-specific path.
Run only on an isolated GitHub-hosted runner or explicitly authorized disposable staging
host. Do not mount production databases, libraries, plugins or torrent paths. Give each
run unique resource names, fresh private application keys and credentials, no published
ports, bounded CPUs/memory and no external network; remove only its recorded resources.

Use the same synthetic 104/27/1/0 library distribution for both images, pinned defaults,
two discoverable versions, grouped required base files and an unchecked optional patch.
Capture a pristine stopped-service H2/data snapshot after fixture initialization, then
restore that snapshot separately for each run with verified runtime ownership. Do not
reuse an already scanned database or seed while an application owns the database.

Record cold and repeated full scans, peak sampled heap and process/container RSS,
completion duration, throughput, failed/completed library counts, restarts and health.
Assert exactly one pinned default per game, selected archive entries and SHA-256 bytes,
and unchanged source path/hash inventories. Test scan failure and recovery only through
a fixture-specific controlled fault (for example inaccessible synthetic scan input),
restore the input, rerun and compare all affected records; never corrupt or manipulate
a live H2 database to manufacture failure. A fixture that does not observe the intended
failure must fail its assertion, not label a successful scan as recovery.

Upload sanitized metrics, assertions, source revisions and image identities. Keep keys,
cookies, database rows and application-data backups private. The current opt-in JVM
fixture remains valuable but mocks persistence; it is not this actual-image workflow.
Passing this contract would establish reproducible synthetic image behavior, not the
controlled real-library evidence or interrupted-production-record checks needed to
close #27.

## Reusable actual-image runner and PR gate

The runner samples only the required scan/heap meters once per sample, rather than
scraping every unrelated binder repeatedly. Each request remains bounded to ten
seconds. A phase may retry at most two timed-out scrapes within its unchanged
180-second completion deadline; timeout counts and longest scrape duration are
recorded, and no heap value is fabricated for a gap. RSS is sampled independently
before each scrape. Reported memory maxima are sampled observations, not continuous
peak guarantees. A telemetry gap is not proof of responsive production monitoring:
the real-library resource and monitoring acceptance remains outstanding.

`scripts/rehearsal/run-image-scan.py` now creates an isolated synthetic H2 application,
imports the validated seed while stopped, runs cold/repeated full scans, and optionally
interrupts only its own container after observing an active scan. It restarts the same
image and verifies all 132 game records, exact selected archive entries/bytes/estimates,
unchanged source hashes, heap/RSS samples and health. A reviewed candidate must retain
exactly one default per game; the baseline's unknown source provenance is recorded
explicitly, not replaced by a fictitious revision. No production database is an input.

The runner accepts image identity, source revision, matching in-image H2 JAR and plugin
paths. No images are pulled or published. Runtime user/group and privileged Docker
prefix are configurable. An optional reviewed external probe receives only synthetic
fixture context on stdin and temporary account credentials through its child environment.
Containers/networks are uniquely named and removal is verified; private synthetic leaves
are printed for bounded inspected cleanup. Never use broad Docker pruning.

The PR image workflow now runs this candidate-only gate after loading its own checked
archive. Its two plugin JARs are built from exact source trees matching official upstream
commit `005a1611ce4495e9080e143ec4bc2f1f0b91e633`:
Steam `52104d217dcfa24fb6bfd54ee7f6aed6c239b557` and direct download
`5ac40f7f9b23c7cc67f6b7dee61a211dd395041a`. Plugin artifact SHA-256 values are recorded
per run. Normal administrator plugin enablement is used; signature verification is not
disabled. CI uses no production plugin files, credentials or private baseline image.
The new CI gate still needs a passing exact-head run before it is considered validated.

A server-only paired run on 2026-10-07 exercised the reviewed candidate source
`36896a1d048e3c3a26cd599656eeb8d408768e0e`, image
`sha256:483c612e5d6d975096a1fa480a479d856906934c605ab4edc8e5e30eaeba2f32`.
The CI archive SHA-256 was
`03e7d4f1ab2a219308db15ff34c9347137feea17723b9336893bbdf8be6d0a7c`.
The baseline retained its already recorded local image ID and unknown source revision;
its image label is `26.04`, despite the running UI reporting `2.4.0`.
Both used the same generated fixture and constraints, but independently initialized
schemas, not a copied production database or a production upgrade rehearsal.

| Image / phase | Wall seconds | Sampled heap bytes | Sampled JVM RSS bytes | Default variants |
| --- | ---: | ---: | ---: | ---: |
| Baseline / cold | 8.911 | 159,375,400 | 540,971,008 | 264 |
| Candidate / cold | 8.777 | 161,432,664 | 540,594,176 | 132 |
| Baseline / repeat | 3.744 | 164,129,088 | 547,610,624 | 264 |
| Candidate / repeat | 3.536 | 150,261,608 | 546,844,672 | 132 |
| Baseline / forced-interruption recovery | 8.867 | 151,716,272 | 537,178,112 | 264 |
| Candidate / forced-interruption recovery | 8.843 | 160,888,000 | 547,774,464 | 132 |

Every full scan completed four libraries with zero failed-library increments. The
interruption test observed one active library in the baseline and three in the candidate
before killing only the disposable process. Both recovered to 132 games, health `UP`,
no recorded container OOM kill and no automatic restart. Both exact grouped downloads
contained `Grouped base/base-a.bin` and `Grouped base/base-b.bin`; selecting the optional
patch added only `Optional patch.bin`. Estimates were 32,768 and 49,152 bytes and member
SHA-256 hashes matched the source manifest on both images. An earlier optional-selection
failure was a harness naming mistake (`patch.bin` versus the content-derived archive
name), not an application defect. All 924 source path/hash pairs remained unchanged.
The candidate additionally passed the shared isolated proxy's two selected downloads,
authenticated identity and logout-to-anonymous checks under issue #29.

This one paired run shows comparable resource use, not a proven total memory saving or
production regression guarantee. The meaningful verified improvement is retaining one
pinned default per game instead of two. The bounded outstanding-task proof remains
separate. These synthetic results cannot close the real-library and interrupted-production
record criteria under #19/#27 or replace the consistent production backup under #26.
