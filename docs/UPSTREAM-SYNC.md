# Upstream synchronization

This fork follows upstream deliberately. An upstream change is never merged directly into `main`, never published as a
candidate image before review, and never deployed to TrueNAS by this procedure.

## Before creating the merge branch

1. Create or update the milestone-backed issue for the synchronization. Record the fork base currently in `main` and
   the exact upstream commit, tag, or release being evaluated. Link the upstream release notes or comparison.
2. Review the upstream diff for model, repository, serialization, authentication, library-scanning, and plugin changes.
   Call out any paths that touch `GameVariant`, `VariantContent`, hardlink mirrors, or download selection.
3. Review database implications before merging. List every schema or migration change, whether it is forward-only, and
   the tested rollback/restore plan. If the result is unclear, stop and resolve it before a candidate image is built.

## Merge and validation branch

Create a dedicated branch from the current fork `main`; use a name such as
`sync/upstream-<upstream-version-or-short-sha>`. Add the upstream remote only when it does not already exist, fetch the
specific revision, and merge it in that branch. Do not use a force push and do not merge upstream directly to `main`.

Resolve conflicts in favor of the fork's published guarantees unless the linked issue explicitly changes one:

- scans, grouping, and cleanup never move, rename, delete, or rewrite torrent-managed source paths;
- mirror hardlinks retain their same-filesystem safety behavior;
- one game can retain variants, their selected default, and their optional/shared content;
- exact downloads include only the selected version and content.

The pull request must identify the upstream source revision, the prior fork base, every conflict resolution with a
behavioral effect, and the migration assessment. It must close the synchronization issue.

## Required validation

Run the full application test suite after resolving conflicts:

```powershell
.\gradlew.bat test
```

Run the fork regression suites explicitly as well. This makes the critical guarantees visible in the pull request even
when the full suite is green:

```powershell
.\gradlew.bat :app:test --tests "org.gameyfin.app.games.variants.*" --tests "org.gameyfin.app.libraries.LibraryScanServiceTest" --tests "org.gameyfin.app.core.download.files.DownloadServiceTest"
```

The reviewer must confirm that the variant fixture leaves its temporary source files unchanged, repeated scans retain
the same game/variant/content relationships and pinned default, hardlink behavior remains safe, and the download
tests cover grouped and shared optional content. Record test commands and results in the PR.

If the upstream change includes a database migration, restore a representative backup into an isolated environment and
exercise startup plus the variant fixture before release consideration. This is required evidence for a candidate image;
it is not authorization to change the running server.

## Release decision

After review and merge, update `RELEASE-NOTES.md` with a separate **Upstream changes** section and a **Fork behavior
preserved** section. State the upstream revision, notable user-visible changes, migration compatibility, and the
evidence that variants, exact downloads, hardlinks, and source paths remained safe.

Merging an upstream synchronization does not publish a package tag or move `latest`. Follow the normal candidate and
promotion gates described in the README. A candidate remains unsuitable for production until the cutover evidence in
issue #61 is complete.
