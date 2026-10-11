# Library proxy query amplification investigation

Issue: #140. This is local diagnosis, not production acceptance or cutover evidence.

On 2026-10-11, the experimental server candidate missed three consecutive ten-second
metrics requests during its first synthetic full scan at 0.5 CPU, 1 GiB container
memory and a 320 MiB heap. No OOM was observed. Thread samples showed H2 execution
and query planning in `GameService.getById`, reached through existing-game metadata
refresh. Those samples alone did not establish a cause.

A local H2/Flyway fixture reproduced the seed cardinalities: 132 games distributed
over four libraries (104/27/1/0), one pinned variant per game, two content entries,
three content paths, and no metadata fields or provider original IDs. Four bounded
workers performed 500 game reads in separate transactions per measurement. A
Hibernate StatementInspector counted each SELECT once; SQL was retained only as
bounded unique shapes. No production database or source files were used.

`Library` was final in compiled bytecode. Consequently Hibernate could not proxy
the supposedly lazy `Game.library` association. SQL showed a game read loading its
library, then every game in that library through the unchanged eager `Library.games`
association. Making only Library and its persistent properties open removed that
cascade. No fetch annotation, image cache, authentication or source-path behavior
was changed. The general all-open compiler configuration remains outside this fix.

| 500 reads, four workers | Final Library | Proxyable Library |
| --- | ---: | ---: |
| Identifier SELECT count | 910,580 | 9,500 |
| Warm identifier wall time | 13,024 ms | 337 ms |
| Warm identifier process CPU | 53,172 ms | 2,234 ms |
| JPQL SELECT count | 911,580 | 10,500 |
| Warm JPQL wall time | 11,643 ms | 273 ms |
| Warm JPQL process CPU | 54,828 ms | 1,891 ms |

Measurements used identifier/JPQL/identifier/JPQL order, before and after the narrow
change. CPU is whole-process CPU, includes background/GC work and may exceed wall
time on multiple cores. These local unconstrained-JVM figures are diagnostic, not
server resource budgets; order, warmup and machine noise preclude treating precise
ratios as a production speedup prediction. JPQL was investigated but not adopted.

Regression coverage requires detached game admin/user DTO conversion without
initializing the library proxy, detached explicitly loaded library DTOs retaining
all game IDs, pinned variant/content/path integrity, managed proxy identity and
updates, scoped game orphan removal with variant/content children and a retained
neighbor, and a bounded SELECT count for the 132-game fixture. Timing is not asserted.
The committed concurrency fixture dirties its test context after the class so it
cannot pollute a cached H2 context used by later tests. Lifecycle callbacks execute
during persistence/update/removal, but event delivery is not asserted by this slice.

The exact rebuilt artifact still needs the same bounded sequential server scan,
metrics responsiveness, CPU/memory/recovery and correctness comparison. Shared
server CPU pressure remains a caveat. Issues #19, #27 and #61 are not satisfied by
this local experiment, and no release/readiness claim follows from it.

Successful provider refresh remains outside the measured improvement: after a
valid `fetchById` result, `GameService.matchManually` reads `library.platforms`,
initializing the library proxy and its unchanged eager game graph. The offline
synthetic scan returns before that access. A real-H2, in-process positive-provider
fixture must quantify this remaining path before claiming representative metadata
refresh scalability. Individual game-read improvements do not establish that claim.

## Positive-provider follow-up (#27)

An isolated local follow-up used the real GameService and H2 repository with a
persisted provider original ID and a fixed in-process provider. Unrelated dependencies
were strict mocks, preventing accidental network, file or image operations. A null
result used 19 SELECTs and left the library uninitialized. A valid title result used
2,083 SELECTs and initialized its graph. A direct read followed by `library.platforms`
independently reproduced the same 19-to-2,083 amplification.

The narrow follow-up projects platform enum values only for a persisted uninitialized
library proxy. Initialized entities continue using their current in-memory platforms,
including unflushed edits; transient libraries retain existing behavior. Empty platform
lists still filter to an empty result, exactly as before. An existence check on an
empty projection preserves failure for missing library references. No association or
fetch annotation changes. Positive refresh then used 21 SELECTs without initializing
the library; null refresh remained 19. Counts are single-refresh local diagnostics,
not a claim about concurrent successful provider workloads or server budgets.

Tests cover empty/single/multiple platform filters, current managed platform edits,
transient and missing-library cases, actual updated title/platform fields, preserved
provider original IDs and variants, and detached admin/user DTO conversion. The exact
artifact still requires bounded server validation before production acceptance.
