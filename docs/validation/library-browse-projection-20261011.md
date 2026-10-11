# Library browse DTO projection (#147, #27)

The local actual-H2 investigation on main `792a8aaa` used four libraries
containing 104, 27, 1 and 0 games. The original `LibraryService.getAll` loaded
2,657 SELECT statements per read. A scalar-only prototype needed two user
queries or five administrator queries for grouped-only ignored paths.

The production implementation is limited to `LibraryService.getAll`:
two user queries; seven administrator queries, including separate scalar
USER/PLUGIN source details. It does not change entity mappings, scan loading,
GameService.getAll, downloads, image handling, authentication or security roles.
No multi-bag join fetch or global lazy/eager policy is introduced.

`LibraryBrowseProjectionTest` compares complete production Jackson JSON with
the original entity-to-DTO mapper, including array order, mixed ignored source
types, reverse-lexical plugin IDs, an empty plugin list, platform order,
nullable external directories, empty libraries and download sums. USER,
ADMIN, SUPERADMIN and unauthenticated service reads retain the existing role
selection. Persisted creation timestamps retain the existing non-null check.
The existing unordered JPA bags do not define an arbitrary insertion-order
contract; ID ordering for games/directories/ignored paths matches the legacy
H2 fixture order, while platforms/plugin lists are not newly sorted.

The 132-game regression retains 132 variant IDs/default locks, 264 selected
content records, 264 grouped path rows, all game download counters, and all
924 synthetic file paths and bytes across reads and a flush/clear/reload.
The empty catalogue uses one SELECT and loads no collections.
Hibernate statistics additionally assert zero entity hydration for both
non-empty browse roles. Ordinary-user reads run none of the administrator
source queries.

Local focused command (JDK 25, frontend tasks omitted):

```shell
./gradlew :app:test --tests 'org.gameyfin.app.libraries.*' \
  --tests '*Positive*Test' --tests '*GameSeedLoadInvestigationTest' \
  --tests '*GameServiceTest' -x :app:vaadinPrepareFrontend -x :app:vaadinBuildFrontend
```

Result: 221 tests in 21 suites passed locally. The 132-game browse read used
exactly two USER / seven ADMIN SELECTs and hydrated zero entities; the legacy
mapper comparison used 2,657 SELECTs for either role.

Local timings are only investigative: fixed arm order, JIT and the desktop
environment prevent a server CPU, RAM or latency claim. Bounded paired
actual-app server measurements remain acceptance work under #27. This is not
cutover evidence and does not authorize production changes or issue closure.
