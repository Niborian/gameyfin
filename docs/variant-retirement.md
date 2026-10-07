# Variant retirement and recovery

Administrators can open **Manage variants → Version retirement** to configure a library's keep-all, latest-N, or grace-period policy. These policies advise review; they never schedule cleanup. Latest-N is evaluated separately for each variant name. Grace begins at the first recorded observation of an active newer sibling and is retained if the replacement is missing or archived. Recording a replacement cannot reset the first observation date.

Archiving hides a superseded version from users and download selection. It preserves the variant's content, paths, required/default-selected bundle flags, and all source files. Default, pinned, and latest versions are protected. A real active newer sibling is required even when cached latest flags are stale. Administrators can reverse visibility through the audited state history.

## Recoverable mirror quarantine

Quarantine requires the administrator to type `QUARANTINE <variant ID>` and provide a reason. The version must already be archived with a verified active newer sibling. Only a `HARDLINKED` variant in a hardlink-mirror library under the application's `library-hardlinks/library-<ID>/<game>/<variant>` root is eligible. Direct torrent sources are never moved.

The command rejects symlinks, redirected paths, content outside the variant root, current defaults, pinned/latest versions, active downloads, mirror creation, and catalog references to the same path or physical file. Inventory is paged and bounded: 10,000 candidate entries, 100,000 catalog entries (including directories), and 1,000,000 identity comparisons. Missing/unreadable paths, unsupported atomic renames, or exceeded budgets stop the action. Windows uses `Files.isSameFile` when file identity keys are unavailable.

The mirror is renamed atomically on the same filesystem to `variant-quarantine/<UUID>/payload`. Original catalog paths remain intact. A database audit records the administrator, reason, original/quarantine paths, time, minimum recovery date, and restoration actor/time. A forced `recovery.properties` journal is written before the rename so a process interruption or database loss leaves independently inspectable recovery evidence.

Restore requires `RESTORE <variant ID>` and refuses to overwrite anything at the original path. It retains archived visibility until a separate audited activation. Database-save failure or transaction rollback compensates the rename. Scans preserve quarantined records and do not recreate their mirrors. All synthetic recovery tests also verify that the original torrent-source fixture and its inode alias remain intact.

There is no permanent deletion or automatic expiration. The recovery date is a minimum retention commitment; restoration remains available afterward. Retaining the payload and recovery journal deliberately leaves recovery possible. An interrupted operation whose database transaction never committed can leave a journal/payload without a database record; inspect the journal and reconcile it in isolated staging before any separately authorized manual production recovery. Do not delete an orphan journal or payload just to clear its directory.

Filesystem inspection and path leases coordinate operations inside one Gameyfin process. One service process must own the configured application storage root, and that directory must remain private to it: external processes must not replace paths during an action. Catalog dependency checks cannot enumerate unknown external aliases, but moving a mirror link does not remove or move those aliases or original source paths. The preview byte count is logical content size; retaining torrent-source hardlinks means quarantine does not promise physical disk savings.
