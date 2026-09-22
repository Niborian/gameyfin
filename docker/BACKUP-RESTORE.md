# H2 backup and restore rehearsal

This is a checklist for [production-operations issue #26](https://github.com/Niborian/gameyfin/issues/26), **not evidence that a backup has been tested**. Do not change the live image or run a full-library scan until an operator has recorded a successful isolated restore.

## Before the maintenance window

1. Record the running image ID and digest, application version, container configuration, database path, and mount destinations. Keep environment values and credentials out of tickets and logs.
2. Locate the persistent `db`, `data`, and `plugindata` volumes. The example Compose file maps these to `/opt/gameyfin/db`, `/opt/gameyfin/data`, and `/opt/gameyfin/plugindata`; verify the actual deployment instead of assuming those paths.
3. Choose a backup destination outside the live volumes with enough free space, access controls, and a retention policy. Keep the encryption key and application secrets available through the existing secret-management process.
4. Record the current library, game, variant, content, and ignored-path counts. Check recent scan failures and restarts so the restore can be compared against a known baseline.

## Capture a consistent backup

1. Arrange a maintenance window and stop writes to Gameyfin. A plain copy of a **running** H2 file database is not a consistent backup.
2. Stop the Gameyfin container gracefully, confirm its process has exited, then copy the complete database directory and the matching `data` and `plugindata` directories into one dated, access-restricted backup set. Include the deployment configuration and the image digest, but store secrets separately. Do not move, rename, delete, or rewrite any library or torrent-managed source path.
3. Hash the backup files and record the capture time and size. Keep the original backup immutable during the rehearsal.
4. Restart the unchanged production container only after the cold copy is complete; verify its expected authentication and proxy path and check for new database errors.

## Rehearse away from production

1. Restore a **copy** of the backup into a separate directory and start an isolated Gameyfin instance using the *same image digest* as the backup. Use a separate container name, private network, and no published ports. Do not mount the production database or writable library paths. If a library mount is required for validation, use a read-only fixture or read-only bind mount.
2. Check database migration and startup logs. Query the restored H2 database independently of login for row counts in `LIBRARY`, `GAME`, `GAME_VARIANT`, `VARIANT_CONTENT`, and `LIBRARY_IGNORED_PATHS`; compare them with the recorded production counts. Also check that a pinned default still refers to the intended variant.
3. Confirm the restored application can start and serve the expected authenticated path. Run quick/full scans only against an isolated fixture, then compare game/variant/content counts and fixture hashes. Never point the rehearsal at writable torrent-managed files.
4. Record the restore start/end time, duration, image digest, row-count comparison, log findings, fixture results, and the operator. Treat any mismatch or H2 error as a failed rehearsal; preserve the backup and investigate before a live upgrade.

## Rollback gate

Before a runtime change, retain both the verified pre-change backup and the exact prior image digest. If the new image fails, stop it before restoring the **verified** database/data/plugin-data set and prior image in a controlled maintenance window. Do not run the older image against a database already migrated by the newer image. Recheck counts, login/proxy behavior, scans, and source-file hashes before reopening access.

Issue #26 remains open until an actual dated capture and isolated restore meet its acceptance criteria. This document alone does not authorize or perform a server change.
