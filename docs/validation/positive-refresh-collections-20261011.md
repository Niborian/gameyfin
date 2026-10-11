# Positive refresh collection merge safety (#145)

The isolated positive-provider run reported update failures with a null exception
message; it did not prove successful enrichment or a performance improvement.
A separate local H2 reproduction using the actual filesystem size service,
variant parser/discovery and DIRECT variant synchronization failed with
`UnsupportedOperationException` in `AbstractList.clear`, Hibernate collection
replacement and `GameVariantService.syncVariants` repository save.

The refresh setters copied immutable singleton provider lists into persistent
game collections. The focused fix supplies a fresh mutable list for genres,
themes, keywords, features, perspectives and video URLs. It does not change
provider ordering, field ownership, equality or the existing policy that empty
provider values are ignored. Company canonicalization remains separate (#143).

The real-service regression exercises singleton, multiple and empty provider
values, repeated refresh, pinned default retention and unchanged synthetic file
bytes. A separate unit regression preserves user-owned collection identities.
Media and hardlink services are strict mocks; DIRECT source files are local
JUnit temporary fixtures. No production paths, network providers or server jobs
are involved.

Remaining acceptance: capture the actual staging exception stack, validate
successful exact positive DTO enrichment, then run the guarded paired actual-app
benchmark. Local correctness is not full-app resource or release acceptance.
