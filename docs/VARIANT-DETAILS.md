# Variant details and local version detection

The variant editor separates **Store**, **product identifier**, **Edition**, and
**Library version/build** from **Included/selectable extras**. These labels describe
one variant; they do not change which paths are downloaded or imply store ownership.

## Administrator workflow

1. Open the variant manager and select the target variant.
2. Enter the store/product, edition and known library version/build. Manual entry
   works even when no compatible manifest exists. Saving updates catalogue metadata
   only, not source files or the existing download selection.
3. Use local detection when wanted. It is never a background poll and contacts no
   store or third-party API. Review each suggestion's relative evidence and caveats.
4. Apply a suggestion explicitly. Detection does not silently replace manual values;
   stale previews/saves must be refreshed rather than overwriting newer edits.

Required base files, optional DLC/patches/mods/extras/server files, grouped archives,
shared content and pinned defaults retain their existing controls and meaning.
No DLC is automatically attached, acquired, enabled or removed by detection.

## Evidence and limits

- Steam: a local app manifest for the exact configured app ID. A build ID is not a
  human release-version number. Ancestor manifests must also fit the selected copy's
  installation path and remain inside its configured library boundary.
- GOG: compatible product metadata or installer filenames. Filename suggestions are
  unverified clues, not proof of the contents, entitlement or newest release.
- Epic: compatible local JSON metadata for the configured catalog item. Binary
  launcher manifests are not supported by this reader.
- Unknown, ambiguous, unreadable and truncated results are not “up to date”. Choose
  manually when evidence cannot distinguish the copy.

Traversal has entry/depth/file/byte budgets, refuses symlinks, and requires secure
directory-descriptor access. Unsupported filesystems fail closed; manual editing
remains available. The time budget is cooperative, not a guarantee that the OS can
interrupt blocked filesystem I/O. Do not broaden mounts just to make detection work.

Detection never executes installers, edits launcher manifests or metadata.txt,
starts scans, or writes source data. Applying a result writes separate variant-detail
database records only; existing exact-download metadata and source paths are not
rewritten. This document is not a claim of live deployment or completed runtime
acceptance.
