# Scan task memory and recovery evidence

Issue #19 remains open until representative scans, recovery, and rollout checks are evidenced.
This change bounds scheduler overhead; it does not establish a production memory budget.

## Fail-closed filesystem reconciliation

Scanning now propagates failures while enumerating configured roots, inspecting child
attributes, and reading candidate directories. A missing root or denied directory
must not be interpreted as an empty successful scan and produce metadata removals.
The ordinary directory-browser endpoint still returns an empty result on read errors.
Missing roots, roots replaced by regular files, and failure/progress/recovery semantics
are covered by isolated JVM tests; this does not prove a production mount outage test.
This protection preserves metadata; scans do not delete original source bytes. Successful
complete enumeration still allows legitimate absent game paths to be reconciled.
This cannot detect a disconnected mount that leaves a readable empty mountpoint:
mount identity/availability needs a separate operator policy, and an empty readable
directory is still considered a successful enumeration.

On 2026-10-08, targeted `FilesystemServiceTest` and `LibraryScanServiceTest`
completed on JDK 25: 48 tests, zero failures/errors/skips. The failure test uses
a deterministic mocked access denial to verify progress and retained records;
it is not an actual-image filesystem permission rehearsal.

The actual-image fixture can opt into `--missing-root-fault`: after normal scans,
temporarily park only its generated synthetic `lib1` directory outside the readonly
source mount. It requires exactly one failed scan, no completed scan, and unchanged
full game DTOs before restoring the directory in `finally` and scanning all libraries
again. Source hashes and exact download assertions still run afterwards. One exact
expected missing-root error is counted separately; unexpected errors are not masked.
This extension needs a passing exact-head actual-image CI run before execution proof
is claimed. It does not test production mount availability or actual access denial.

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

Processor interruption is a scan failure, never an unmatched/null result. Interrupted
workers and coordinators preserve their interrupt flag; library reconciliation and
success accounting are skipped when interrupted processing propagates.
These assertions concern host reconciliation/accounting only. A transactional processor
may have committed changes before returning with its interrupt flag set; this guard does
not undo those changes or earlier completed per-game transactions.

`invokeBounded` requests `Future.cancel(true)` but cancelled Future state alone does not
prove body exit. The integrated worker-lifetime gate retains `scansInProgress` ownership
until the per-scan executor actually terminates; see `SCAN-WORKER-LIFETIME.md`.
This does not provide atomic rollback of earlier individual game transactions or make
an indefinitely uncooperative provider automatically recoverable. Representative recovery
acceptance remains necessary before closing #19.

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
