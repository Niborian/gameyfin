# Request-scoped game browse batching (#150, #27)

The actual-H2 investigation on main `792a8aaa` used 132 rich games over four
libraries, 264 active/archived variants, 528 required/optional content records,
cover/screenshot DTOs, plugin metadata provenance and 924 synthetic files.
The original `GameService.getAll` used 3,435 SELECTs; request-session batching
used 125 at batch32 or 73 at batch64, with identical production Jackson JSON.
Every arm hydrated 1,455 entities and 3,432 collections. This is not a claim
that retained catalogue heap or idle application memory disappears.

The fix only scopes fetch batch64 to `GameService.getAll` inside a read-only
transaction. Its contextual EntityManager unwraps the current Hibernate
session. A `finally` restores the previous policy after DTO conversion,
empty results and failures. No entity fetch mappings, global batch option,
subselect option, other game reads, metadata updates or scan paths change.

`GameBrowseBatchTest` exercises the normal Spring-injected, transaction-proxied
service with actual H2. It compares full USER, ADMIN and SUPERADMIN serialized
DTOs to the original mapper, checks 73 SELECTs, previous batch0/17 restoration,
unchanged subselect policy, archived visibility, required/optional content,
cover/screenshots and plugin provenance. A separate case covers USER-field
username provenance. An unwrapped request verifies a fresh read-only service
transaction and batch64 while the SELECT executes.

Flush/clear/reload snapshots retain variant/default/retirement IDs and values,
content IDs/selection flags/grouped path rows, game paths/download counters,
and every synthetic file path and byte. Unit tests cover restoration when the
repository, DTO mapper or temporary setter throws, as well as empty reads.

The test-only subselect alternative was rejected: a sampled test thread spent
209 seconds CPU over 223 seconds elapsed in H2 nested `IN` evaluation through
`CollectionLoaderSubSelectFetch`. The exact owned local worker was stopped.
This policy was not tested on the server or introduced into production.

Local CPU/allocation/timing figures are observational and affected by fixed
order, JIT and desktop execution. Paired bounded actual-app server CPU/RAM,
response latency and post-task recovery remain acceptance work under #27.
There is no production change, cutover or idle-memory savings claim here.

Local validation: 77 tests passed with zero failures across GameBrowseBatchTest,
GameServiceTest, Positive*Test and GameSeedLoadInvestigationTest using the
existing JDK25, offline Gradle, two workers and a 768MiB build heap. The frontend
build tasks were excluded; this change does not touch frontend assets.
