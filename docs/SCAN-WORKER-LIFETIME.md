# Scan worker lifetime and recovery (#19)

Every accepted library scan owns its worker executor, passed explicitly through both
existing-game updates and new-game processing. The shared executor dispatches coordinators
only. A cancelled Future is not evidence that its body has stopped: a provider may ignore
interruption and later return to transactional writes.

On coordinator exit, worker admission closes and unfinished work receives interruption.
The coordinator waits up to five seconds for actual executor termination, preserving its
interrupt flag even when interrupted repeatedly during draining. Queued Future tasks are
cancelled. If workers remain alive, library ownership stays retained and a daemon monitor
checks actual termination once per second. A duplicate scan cannot overlap those bodies.
Only proved termination releases ownership. No thread is forcibly killed.

`gameyfin_scans_active` describes coordinator scan progress, not worker quiescence.
`gameyfin_scans_draining` is a label-free bounded-cardinality gauge for retained worker
scopes, including the initial drain and subsequent quarantine. Idle evidence must require
both gauges to be zero; a reported failed scan alone is not proof that writes have ended.
The quarantine log also identifies the affected library for operator diagnosis.

A permanently stuck provider remains quarantined until process restart or genuine body
exit. This change neither makes hung providers automatically recoverable nor establishes
real-library memory budgets, backup/restore proof, safe rollout or cutover acceptance.

Regression coverage includes a real service scan with a SQL-failing sibling and a mocked
processor body standing in for an interruption-ignoring provider. It verifies duplicate rejection after the bounded drain,
draining/active metric distinction, then permits a replacement scan only after body exit.
Worker-scope tests cover cancelled Future versus live body, queued cancellation, repeated
drain interruption and interrupt preservation. These synthetic tests do not replace
representative image/workload measurements.

Local JDK 25 validation on 2026-10-09 passed 23 tests: 19 LibraryScanServiceTest,
2 ScanWorkerLifetimeTest and 2 ScanMetricsTest, with zero failures/errors/skips. The run
excluded Vaadin production frontend rebuilding and did not exercise an application image,
production database or real plugin. Exact-head CI/image validation remains required.
