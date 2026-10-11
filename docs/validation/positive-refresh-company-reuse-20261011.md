# Positive metadata refresh company reuse (#143)

The isolated positive-provider experiment failed its enrichment gate. Its retained
aggregate diagnostics do not prove the exact server exception. A separate local
real-H2 reproduction exercised `LibraryGameProcessor.processExistingGame` and
`GameService.updateMetadata` with successful metadata including shared developer
and publisher names, title, summary, platforms, genres and keywords. The first
game refreshed; the second failed flush with SQL23505 on `UQ_COMPANY_NAME_TYPE`.

The refresh path assigned transient provider-created companies directly. Normal
create/update paths already resolve companies through `CompanyService.createOrGet`.
The focused change applies that same policy only when publisher/developer fields
are eligible to change. Existing administrator-owned field protection and
unchanged-value detection remain in `updateField`. Name/type identity and existing
independent-insert/concurrent-reuse policy are not changed.

Regression tests use real H2 persistence and real company services, actual
GameService and existing-game processor, explicit transaction flushes, repeated
successful refreshes, same-name developer/publisher distinction, and bounded
concurrent updates. Only image/filesystem/discovery/variant synchronization seams
are strict test substitutes; no network, external images or production paths.

Local targeted Gradle run passed `PositiveProviderProcessorReproductionTest`,
`CompanyServiceConcurrencyTest` and `GameServiceTest` with JDK25, cached/offline
dependencies, two build workers and frontend generation excluded.

This is not proof that the earlier server failure had only this cause, nor a
memory/CPU improvement or production/cutover acceptance claim. Keep #143 open
until isolated actual-app positive-provider validation succeeds with unchanged
source hashes, expected metadata/selection identities, no blocking failures and
exact cleanup. No GC defaults, deployment or production configuration changes.
