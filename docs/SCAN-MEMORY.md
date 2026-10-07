# Scan task memory and recovery evidence

Issue #19 remains open until representative scans, recovery, and rollout checks are evidenced.
This change bounds scheduler overhead; it does not establish a production memory budget.

## Reproduce the synthetic fixture

Use JDK 25 and run:

```powershell
./gradlew.bat :app:test --tests org.gameyfin.app.libraries.scan.BoundedScanTasksTest --tests org.gameyfin.app.libraries.LibraryScanServiceTest -x :app:vaadinBuildFrontend --no-daemon
```

The 10,000-item fixture verifies that at concurrency four, no more than four constructed
tasks remain unfinished. Previously the scanner constructed all tasks and submitted one
virtual thread per game before waiting; the semaphore limited processing but did not
bound waiting tasks. The new completion queue replenishes a slot as soon as any task
finishes and retains the original result order. A latch-based fixture proves that a slow
first task does not prevent a later task starting. A failed task propagates the failure,
cancels the remaining submitted window, and prevents further work being submitted.

On 2026-10-07 the three bounded-task tests and 17 library-scan tests passed under
Temurin 25.0.4.1. Full main and test Kotlin compilation also passed. The frontend build
was excluded because these tests exercise JVM scan logic.

This is a measurement of outstanding task count, not peak heap or RSS. Library entities,
filesystem snapshots, and final result collections still scale with library size.

## Remaining acceptance evidence

Use an isolated staging copy or synthetic library outside TrueNAS. Record fixture and
representative workload results separately, including image digest, JVM flags, container
limit, configured concurrency, duration, throughput, peak heap, peak RSS, restarts, and
error count. Issue #27 owns the tested heap/container budget and native-memory headroom.
Rehearse H2 backup and restore under #26 before changing the live image; never attach to
the running production H2 database to collect this evidence.

Existing scan metrics expose started/completed/failed counts, active scans, duration,
game counts, and database-failure categories. In staging, capture the actuator health
and readiness responses and verify a failed scan raises the failed count and completes
its progress record with `FAILED`, then rerun and verify affected records. Monitor an
increase in `gameyfin_scans_failed_total`; health alone does not prove scan success.
Review management-port and direct-port reachability under #29 before rollout.

No controlled real-library scan, peak RSS/heap measurement, backup/restore rehearsal,
or production exposure verification is claimed by this task fixture.
