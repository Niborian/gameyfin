package org.gameyfin.app.core.download.torrent

import org.gameyfin.snapshot.SnapshotProtocol
import tools.jackson.databind.JsonNode
import java.util.UUID

/** A proved owned ADD is still checking. This is not permission to add or start again. */
internal class QbDistributionPendingConfirmation : IllegalStateException("Owned add still checking")

/** Host-only lifecycle. No endpoint/provider is registered. A caller owns one lease and
 * serializes operations for it; durable compare-and-transition also refuses duplicate I/O.
 * Every uncertain outcome retains its client-bound lease. Never automatically re-add/start.
 */
internal class QbDistributionLifecycle(private val policy: QbDistributionPolicy,
    private val api: QbDistributionApi, private val journal: QbDistributionJournal,
    private val authorityCheckpoint: () -> Unit = {}) {
    private val validator = TorrentMetadataValidator(TorrentNetworkPolicy(policy.approvedTrackers, true))

    fun reserve(lease: OwnedSnapshotLease, operationId: UUID? = null): QbDistributionRecord {
        policy.requireAdmission(); lease.verifyForClient(policy.cacheIdentity)
        val record = journal.reserve(policy.clientId, lease.snapshotId, lease.leaseId,
            SnapshotProtocol.digest(lease.manifest.bytes()), policy.mappingDigest, operationId)
        lease.retainForClient(policy.clientId, record.id)
        return record
    }

    /** One create attempt. Even a timeout stays CREATING and must be reconciled by token. */
    fun create(id: UUID, lease: OwnedSnapshotLease): QbDistributionRecord {
        val row = checked(id, lease); require(row.state == QbDistributionState.RESERVED)
        api.verifyClient(); lease.verifyForClient(policy.cacheIdentity)
        journal.transition(id, setOf(row.state), QbDistributionState.CREATING)
        val taskId = mutateRemote { api.create(row.snapshotId, row.id) }
        api.ownedTask(taskId, row.snapshotId, row.id)
        return journal.transition(id, setOf(QbDistributionState.CREATING), QbDistributionState.CREATED, taskId = taskId)
    }

    /** Reconcile an ambiguous creator request without issuing a second create. */
    fun reconcileCreation(id: UUID, lease: OwnedSnapshotLease): QbDistributionRecord {
        val row = checked(id, lease); require(row.state == QbDistributionState.CREATING)
        api.verifyClient(); lease.verifyForClient(policy.cacheIdentity)
        val matches = api.tasks().filter { it["comment"]?.asText() == policy.comment(id) }
        require(matches.size == 1) { "Creator ownership remains uncertain" }
        val taskId = requireNotNull(matches.single()["taskID"]?.asText())
        api.ownedTask(taskId, row.snapshotId, id)
        return journal.transition(id, setOf(row.state), QbDistributionState.CREATED, taskId = taskId)
    }

    fun validate(id: UUID, lease: OwnedSnapshotLease): QbDistributionRecord {
        val row = checked(id, lease); require(row.state == QbDistributionState.CREATED)
        api.verifyClient(); lease.verifyForClient(policy.cacheIdentity)
        val task = api.ownedTask(requireNotNull(row.taskId), row.snapshotId, id)
        require(task["status"]?.asText() == "Finished") { "Creator has not completed" }
        val metadata = api.metadata(row.taskId)
        val validated = validateMetadata(metadata, lease)
        require(api.find(validated.infoHash) == null) { "Existing torrent is not adopted" }
        return journal.transition(id, setOf(row.state), QbDistributionState.VALIDATED,
            infoHash = validated.infoHash, metadata = metadata)
    }

    fun addStopped(id: UUID, lease: OwnedSnapshotLease): QbDistributionRecord {
        val row = checked(id, lease); require(row.state == QbDistributionState.VALIDATED)
        api.verifyClient(); lease.verifyForClient(policy.cacheIdentity)
        require(api.find(requireNotNull(row.infoHash)) == null) { "Existing torrent is not adopted" }
        validateMetadata(requireNotNull(row.metadata), lease)
        journal.transition(id, setOf(row.state), QbDistributionState.ADDING)
        mutateRemote { api.addStopped(row.snapshotId, id, row.metadata) }
        // HTTP acknowledgement is not physical stopped-state acknowledgement. Initial
        // qB checking is asynchronous; the caller confirms separately without another add.
        return journal.get(id)
    }

    fun reconcileAdded(id: UUID, lease: OwnedSnapshotLease): QbDistributionRecord {
        val row = checked(id, lease); require(row.state == QbDistributionState.ADDING)
        api.verifyClient()
        val info = verifyOwned(row, lease, requireComplete = false, requireStopped = false)
        when (info["state"]?.asText()) {
            "checkingUP", "checkingDL", "checkingResumeData" -> throw QbDistributionPendingConfirmation()
            "stoppedUP", "stoppedDL" -> Unit
            else -> error("Owned add is not stopped")
        }
        return journal.transition(id, setOf(row.state), QbDistributionState.STOPPED)
    }

    /** Explicit recheck only while stopped. Checking cannot be mistaken for completion. */
    fun recheck(id: UUID, lease: OwnedSnapshotLease): QbDistributionRecord {
        val row = checked(id, lease); require(row.state == QbDistributionState.STOPPED)
        api.verifyClient(); verifyOwned(row, lease, requireComplete = false, requireStopped = true)
        journal.transition(id, setOf(row.state), QbDistributionState.CHECKING)
        mutateRemote { api.recheck(requireNotNull(row.infoHash)) }
        return journal.get(id)
    }

    fun confirmChecked(id: UUID, lease: OwnedSnapshotLease): QbDistributionRecord {
        val row = checked(id, lease); require(row.state == QbDistributionState.CHECKING)
        api.verifyClient(); verifyOwned(row, lease, requireComplete = true, requireStopped = true)
        return journal.transition(id, setOf(row.state), QbDistributionState.STOPPED)
    }

    fun start(id: UUID, lease: OwnedSnapshotLease): QbDistributionRecord {
        val row = checked(id, lease); require(row.state == QbDistributionState.STOPPED)
        api.verifyClient(); verifyOwned(row, lease, requireComplete = true, requireStopped = true)
        lease.verifyForClient(policy.cacheIdentity)
        journal.transition(id, setOf(row.state), QbDistributionState.STARTING)
        mutateRemote { api.start(requireNotNull(row.infoHash)) }
        return confirmSeeding(id, lease)
    }

    fun confirmSeeding(id: UUID, lease: OwnedSnapshotLease): QbDistributionRecord {
        val row = checked(id, lease); require(row.state in setOf(QbDistributionState.STARTING, QbDistributionState.SEEDING))
        api.verifyClient()
        // A failed ownership/snapshot proof cannot authorize even a best-effort stop by
        // infohash: a foreign/reconfigured row may now occupy that hash. Retain the lease
        // and in-flight state for explicit reconciliation, with no remote mutation.
        val info = verifyOwned(row, lease, requireComplete = true, requireStopped = false)
        require(info["state"]?.asText() in setOf("uploading", "stalledUP", "queuedUP", "forcedUP")) { "Client is not upload-only" }
        return if (row.state == QbDistributionState.SEEDING) row else journal.transition(id, setOf(row.state), QbDistributionState.SEEDING)
    }

    /** Two separately acknowledged phases; callers repeat reconcileRemoval, never deleteFiles. */
    fun stopForRemoval(id: UUID, lease: OwnedSnapshotLease): QbDistributionRecord {
        val row = checked(id, lease)
        require(row.state in setOf(QbDistributionState.STOPPED, QbDistributionState.CHECKING, QbDistributionState.STARTING, QbDistributionState.SEEDING))
        api.verifyClient(); verifyOwned(row, lease, requireComplete = false, requireStopped = false)
        journal.transition(id, setOf(row.state), QbDistributionState.STOPPING)
        mutateRemote { api.stop(requireNotNull(row.infoHash)) }
        return journal.get(id)
    }

    fun removeStopped(id: UUID, lease: OwnedSnapshotLease): QbDistributionRecord {
        val row = checked(id, lease); require(row.state == QbDistributionState.STOPPING)
        api.verifyClient(); verifyOwned(row, lease, requireComplete = false, requireStopped = true)
        journal.transition(id, setOf(row.state), QbDistributionState.REMOVING)
        mutateRemote { api.remove(requireNotNull(row.infoHash)) }
        return journal.get(id)
    }

    fun reconcileRemoval(id: UUID, lease: OwnedSnapshotLease): QbDistributionRecord {
        val row = checked(id, lease); require(row.state in setOf(QbDistributionState.REMOVING, QbDistributionState.REMOVED))
        api.verifyClient(); require(api.find(requireNotNull(row.infoHash)) == null) { "Torrent removal not acknowledged" }
        val taskId = requireNotNull(row.taskId)
        val tasks = api.tasks()
        val task = tasks.singleOrNull { it["taskID"]?.asText() == taskId }
        if (task != null) {
            api.ownedTask(taskId, row.snapshotId, id)
            mutateRemote { api.deleteTask(taskId) }
        }
        require(api.tasks().none { it["taskID"]?.asText() == taskId || it["comment"]?.asText() == policy.comment(id) }) { "Creator removal not acknowledged" }
        val removed = if (row.state == QbDistributionState.REMOVED) row else journal.transition(id, setOf(row.state), QbDistributionState.REMOVED)
        lease.releaseAfterClientRemoval(policy.clientId, id)
        return removed
    }

    /** Deliberate operator retry after inspecting an uncertain remove. Revalidate exact
     * ownership and physical stopped state first; never retry against a hash alone. */
    fun retryRemoval(id: UUID, lease: OwnedSnapshotLease): QbDistributionRecord {
        val row = checked(id, lease); require(row.state == QbDistributionState.REMOVING)
        api.verifyClient(); verifyOwned(row, lease, requireComplete = false, requireStopped = true)
        mutateRemote { api.remove(requireNotNull(row.infoHash)) }
        return journal.get(id)
    }

    /** Explicit cancellation only before any add could have been issued. A creator must
     * already be physically finished: deleting a running qB task merely requests async
     * interruption and its absent status is NOT proof that all snapshot reads ended. */
    fun cancelBeforeAdd(id: UUID, lease: OwnedSnapshotLease): QbDistributionRecord {
        val row = checked(id, lease)
        require(row.state in setOf(QbDistributionState.RESERVED, QbDistributionState.CREATED, QbDistributionState.VALIDATED))
        api.verifyClient(); lease.verifyForClient(policy.cacheIdentity)
        proveNoTorrentBinding(row)
        val tasks = boundCreatorTasks(row)
        when (row.state) {
            QbDistributionState.RESERVED -> require(row.taskId == null && tasks.isEmpty()) { "Unexpected creator ownership" }
            QbDistributionState.CREATED -> {
                require(tasks.size == 1) { "Creator exit remains unproven" }
                requireFinishedCreator(row)
            }
            QbDistributionState.VALIDATED -> {
                // VALIDATED persists completion plus full metainfo proof. A later task
                // disappearance (e.g. qB restart) does not invent a new creation token.
                require(row.metadata != null && row.infoHash != null && row.taskId != null)
                if (tasks.isNotEmpty()) requireFinishedCreator(row)
            }
            else -> error("Unreachable cancellation state")
        }
        journal.transition(id, setOf(row.state), QbDistributionState.CANCELLING)
        return confirmCancellation(id, lease)
    }

    /** Re-entry after task-delete or DB finalization uncertainty repeats proofs only.
     * Existing identical hashes at another path/operation are left entirely untouched. */
    fun confirmCancellation(id: UUID, lease: OwnedSnapshotLease): QbDistributionRecord {
        val row = checked(id, lease)
        require(row.state in setOf(QbDistributionState.CANCELLING, QbDistributionState.CANCELLED))
        api.verifyClient(); proveNoTorrentBinding(row)
        val tasks = boundCreatorTasks(row)
        if (tasks.isNotEmpty()) {
            require(row.state == QbDistributionState.CANCELLING) { "Creator reappeared after cancellation" }
            requireFinishedCreator(row)
            mutateRemote { api.deleteTask(requireNotNull(row.taskId)) }
        }
        require(boundCreatorTasks(row).isEmpty()) { "Creator cancellation not acknowledged" }
        val cancelled = if (row.state == QbDistributionState.CANCELLED) row else
            journal.transition(id, setOf(row.state), QbDistributionState.CANCELLED)
        lease.releaseAfterClientRemoval(policy.clientId, id)
        return cancelled
    }

    /** Recovery for reserve committed but retainForClient never committed. A released
     * NEVER-bound original token fences a late bind; do not fabricate a client lease to
     * cancel it. Active/unknown worker ownership and every post-CREATE lineage refuse. */
    fun cancelUnboundReserved(id: UUID, snapshots: OwnedSnapshotRegistry): QbDistributionRecord {
        check(!Thread.currentThread().isInterrupted) { "Distribution cancelled" }
        val row = journal.get(id)
        require(row.clientId == policy.clientId && row.mappingDigest == policy.mappingDigest &&
            row.state in setOf(QbDistributionState.RESERVED, QbDistributionState.CANCELLED) &&
            row.taskId == null && row.infoHash == null && row.metadata == null) { "Not an unbound pre-create intent" }
        require(snapshots.get(row.snapshotId).manifestDigest == row.manifestDigest) { "Snapshot identity differs" }
        require(snapshots.leaseBindingState(row.snapshotId, row.leaseId, row.clientId, row.id) in
            setOf(SnapshotLeaseBindingState.UNBOUND_ACTIVE, SnapshotLeaseBindingState.UNBOUND_RELEASED)) { "Original lease was client-bound" }
        check(snapshots.releaseUnboundLeaseAfterExit(row.snapshotId, row.leaseId, SnapshotProcessProbe.SYSTEM)) { "Original worker exit remains unproven" }
        require(snapshots.leaseBindingState(row.snapshotId, row.leaseId, row.clientId, row.id) == SnapshotLeaseBindingState.UNBOUND_RELEASED)
        api.verifyClient(); proveNoTorrentBinding(row)
        require(boundCreatorTasks(row).isEmpty()) { "Unexpected creator ownership" }
        return if (row.state == QbDistributionState.CANCELLED) row else
            journal.transition(id, setOf(QbDistributionState.RESERVED), QbDistributionState.CANCELLED)
    }

    private fun requireFinishedCreator(row: QbDistributionRecord) {
        val task = api.ownedTask(requireNotNull(row.taskId), row.snapshotId, row.id)
        require(task["status"]?.asText() in setOf("Finished", "Failed")) { "Creator still holds snapshot reads" }
    }

    private fun boundCreatorTasks(row: QbDistributionRecord): List<JsonNode> {
        val tasks = api.tasks().filter { task ->
            require(task["taskID"]?.isString == true && task["sourcePath"]?.isString == true) { "Malformed creator inventory" }
            val source = remotePath(task["sourcePath"])
            task["taskID"].asText() == row.taskId || task["comment"]?.asText() == policy.comment(row.id) ||
                intersects(source, policy.source(row.snapshotId))
        }
        require(tasks.size <= 1 && tasks.all { it["taskID"].asText() == row.taskId &&
            it["comment"]?.asText() == policy.comment(row.id) && it["sourcePath"].asText() == policy.source(row.snapshotId) }) {
            "Creator ownership remains ambiguous"
        }
        return tasks
    }

    private fun proveNoTorrentBinding(row: QbDistributionRecord) {
        for (torrent in api.inventory()) {
            require(listOf("save_path", "content_path", "tags").all { torrent[it]?.isString == true }) { "Malformed torrent binding inventory" }
            val save = remotePath(torrent["save_path"])
            val content = remotePath(torrent["content_path"])
            val tags = torrent["tags"].asText().split(',').map(String::trim)
            require(save != policy.savePath(row.snapshotId) && !intersects(content, policy.source(row.snapshotId)) &&
                policy.tag(row.id) !in tags) { "External snapshot ownership remains" }
        }
    }

    /** POSIX remote paths, not host Path semantics. Reject ambiguous dot/backslash/control
     * components; normalize a trailing separator without treating /cache-a as /cache. */
    private fun remotePath(value: JsonNode): String {
        require(value.isString)
        val raw = value.asText()
        require(raw.startsWith('/') && raw.length <= 8192 && raw.none { it == '\\' || it.isISOControl() }) { "Malformed remote binding path" }
        val components = raw.split('/').drop(1).dropLastWhile { it.isEmpty() }
        require(components.none { it.isEmpty() || it == "." || it == ".." }) { "Ambiguous remote binding path" }
        return "/" + components.joinToString("/")
    }
    private fun intersects(first: String, second: String): Boolean =
        first == "/" || second == "/" || first == second || first.startsWith("$second/") || second.startsWith("$first/")

    private fun <T> mutateRemote(action: () -> T): T {
        check(!Thread.currentThread().isInterrupted) { "Distribution cancelled" }
        authorityCheckpoint()
        check(!Thread.currentThread().isInterrupted) { "Distribution cancelled" }
        return action()
    }

    /** Host restart recovery uses the same persisted lease token, never a replacement. */
    fun resume(id: UUID, snapshots: OwnedSnapshotAcquisitionService): OwnedSnapshotLease {
        val row = journal.get(id)
        require(row.clientId == policy.clientId && row.mappingDigest == policy.mappingDigest)
        return snapshots.resumeClientLease(row.snapshotId, row.leaseId, row.clientId, row.id)
    }

    private fun checked(id: UUID, lease: OwnedSnapshotLease): QbDistributionRecord {
        check(!Thread.currentThread().isInterrupted) { "Distribution cancelled" }
        return journal.get(id).also {
            require(it.clientId == policy.clientId && it.mappingDigest == policy.mappingDigest && it.snapshotId == lease.snapshotId &&
                it.leaseId == lease.leaseId && it.manifestDigest == SnapshotProtocol.digest(lease.manifest.bytes())) { "Distribution lease identity differs" }
            // REMOVED may still need a DB-release retry after its exact worker exited.
            // releaseAfterClientRemoval is itself tuple-bound and idempotent on that lease.
            if (it.state !in setOf(QbDistributionState.REMOVED, QbDistributionState.CANCELLED)) lease.retainForClient(policy.clientId, id)
        }
    }

    private fun validateMetadata(bytes: ByteArray, lease: OwnedSnapshotLease): ValidatedTorrentMetadata {
        lease.verifyForClient(policy.cacheIdentity)
        return validator.validate(bytes.inputStream(), lease.manifest.members.map { SnapshotMember(it.name, it.bytes, it.digest) },
            OwnedSnapshotReader(lease::openMember)).also { require(it.rootName == "data") { "Snapshot root differs" } }
    }

    private fun verifyOwned(row: QbDistributionRecord, lease: OwnedSnapshotLease, requireComplete: Boolean, requireStopped: Boolean): JsonNode {
        lease.verifyForClient(policy.cacheIdentity)
        val hash = requireNotNull(row.infoHash)
        val info = requireNotNull(api.find(hash)) { "Owned torrent absent" }
        require(info["category"]?.asText() == policy.category && info["tags"]?.asText()?.split(',')?.map(String::trim) == listOf(policy.tag(row.id)) &&
            info["save_path"]?.asText()?.trimEnd('/') == policy.savePath(row.snapshotId) && info["auto_tmm"]?.booleanValue() == false) { "Torrent ownership differs" }
        require(info["downloaded"]?.isIntegralNumber == true && info["downloaded"].longValue() == 0L) { "Payload download detected" }
        val properties = api.properties(hash)
        require(properties["comment"]?.asText() == policy.comment(row.id) && properties["is_private"]?.booleanValue() == true) { "Torrent metadata ownership differs" }
        val metadata = api.export(hash)
        val validated = validateMetadata(metadata, lease)
        require(validated.infoHash == hash && QbDistributionPolicy.digest(metadata) == QbDistributionPolicy.digest(requireNotNull(row.metadata))) { "Exported metadata differs" }
        val expected = lease.manifest.members.associate { "data/${it.name}" to it.bytes }
        val files = api.files(hash)
        require(files.size == expected.size && files.map { it["name"]?.asText() }.toSet().size == files.size)
        for (file in files) {
            require(file["size"]?.isIntegralNumber == true && expected[file["name"]?.asText()] == file["size"].longValue() && file["priority"]?.intValue() == 1) { "Torrent member selection differs" }
            if (requireComplete) require(file["progress"]?.doubleValue() == 1.0) { "Snapshot member not complete" }
        }
        require(api.webSeeds(hash).isEmpty()) { "Web seed refused" }
        val actualTrackers = api.trackers(hash).filterNot { it["url"]?.asText() in setOf("** [DHT] **", "** [PeX] **", "** [LSD] **") }
        require(actualTrackers.map { it["url"]?.asText() }.toSet() == policy.approvedTrackers && actualTrackers.size == policy.approvedTrackers.size) { "Tracker policy differs" }
        if (requireStopped) require(info["state"]?.asText() in setOf("stoppedUP", "stoppedDL")) { "Torrent is not stopped" }
        if (requireComplete) require(info["progress"]?.doubleValue() == 1.0 && info["amount_left"]?.isIntegralNumber == true && info["amount_left"].longValue() == 0L) { "Snapshot incomplete; start refused" }
        return info
    }
}
