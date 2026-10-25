# Variant details and local version detection

The variant editor separates **Store**, **product identifier**, **Edition**, and
**Library version/build** from **Included/selectable extras**. These labels describe
one variant; they do not change which paths are downloaded or imply store ownership.

## Administrator workflow

1. On the game page, choose **Versions & content** beside the version selector
   (administrators only), then select the target variant. The library-management
   entry point remains available too. Successful content saves refresh the open
   editor immediately; closing and reopening is not required.
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

## Split a broad directory into base and optional content

On a BASE content card, choose **Split into base / extras**. This reads that
directory's direct children and prefills their paths. Review the filename-based
type hints: game archives are suggested as BASE, patch/multiplayer names as PATCH,
and unrecognized items as EXTRA. These hints are not content or ownership proof.
Existing separately represented extras start unselected so they can be retained
without overlapping download selections. Required BASE content stays included;
patches are optional and initially selected, and all suggestions can be changed.

Choose **Replace base selection** and confirm. A single transaction replaces only
the original broad catalogue item with the reviewed selections. It retains the
game/variant source root and existing extras; it never moves or deletes files.
Replacement requires an existing BASE directory, at least one new BASE selection,
and non-overlapping paths contained inside that directory. Missing/stale originals,
outside paths and symlink escapes are refused. This is not a source snapshot or a
guarantee against files being changed concurrently by external programs.
For a different source, **Use current path** and **Add path** remain available.

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
