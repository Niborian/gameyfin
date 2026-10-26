package org.gameyfin.app.core.download.torrent

import org.gameyfin.app.core.download.files.DownloadPathLeases
import org.gameyfin.app.core.download.files.DownloadSelectionResolver
import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.games.entities.GameVariant
import org.gameyfin.app.games.entities.VariantContent
import org.gameyfin.app.games.entities.effectivePaths
import org.gameyfin.snapshot.SnapshotCapture
import org.gameyfin.snapshot.SnapshotManifest
import org.gameyfin.snapshot.SnapshotProtocol
import org.gameyfin.snapshot.SnapshotSelection
import org.gameyfin.snapshot.SnapshotSourceProof
import java.io.InputStream
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** Host configuration only. Constructing this service neither registers a provider nor
 * creates an HTTP endpoint. qB activation stays absent until full transfer/retention parity.
 */
internal data class OwnedSnapshotSettings(val cacheRoot: Path, val byteLimit: Long, val fileLimit: Int, val reserveBytes: Long,
    val maxUnretiredSnapshots: Int = 16, val maxUnretiredBytes: Long = byteLimit * 4) {
    init {
        require(cacheRoot.isAbsolute && cacheRoot.normalize() == cacheRoot)
        require(byteLimit in 1..1024L * 1024 * 1024 * 1024 && fileLimit in 1..SnapshotProtocol.MAX_MEMBERS && reserveBytes >= 0)
        require(maxUnretiredSnapshots in 1..256 && maxUnretiredBytes in byteLimit..4L * 1024 * 1024 * 1024 * 1024)
    }
}

internal data class OwnedSnapshotLogicalSelection(val gameId: Long, val variantId: Long, val contentIds: List<Long>,
    val sourceRoot: Path, val sources: List<Path>, val selections: List<SnapshotSelection>)

/** Carries a reserved/intended identity even when a callback or database COMMIT
 * outcome was unknown. It contains no source paths, credentials or permission grant.
 */
internal class OwnedSnapshotAcquisitionFailure(val snapshotId: UUID?, cause: Throwable,
    val reservationRefused: Boolean = false) :
    IllegalStateException("Owned snapshot acquisition did not complete", cause) {
    init { require(!reservationRefused || cause is SnapshotAdmissionRefused) }
    var safeToReject: Boolean = false
        private set
    internal fun confirmNoRetainedState() { check(snapshotId == null || reservationRefused); safeToReject = true }
}

/** A real host boundary: membership + source lease -> committed operation -> descriptor
 * worker -> immutable verified manifest -> durable consumption lease. Internal only;
 * callers still own user authorization. Plugins never receive roots or this service.
 */
internal class OwnedSnapshotAcquisitionService(private val downloads: DownloadSelectionResolver, private val sourceLeases: DownloadPathLeases,
    private val registry: OwnedSnapshotRegistry, private val workers: SnapshotWorkerSupervisor, private val settings: OwnedSnapshotSettings) {
    // One source-copy operation at a time. Consumption uses its separate persistent worker.
    private val acquisition = Semaphore(1)
    private val workerSlots = Semaphore(2)

    /** Torrent-only canonicalization. Legacy ZIP selection/order is unchanged. */
    fun resolveCanonicalSelection(game: Game, variantId: Long?, contentIds: List<Long>?): OwnedSnapshotLogicalSelection {
        val (variant, contents) = downloads.resolveSelection(game, variantId, contentIds)
        return canonicalMapping(game, variant, contents)
    }

    /** Database-only lexical mapping for the locked attach writer. No filesystem
     * validation is performed here; the full descriptor SOURCE proof stays outside.
     */
    fun resolveCanonicalMembership(game: Game, variantId: Long?, contentIds: List<Long>?): OwnedSnapshotLogicalSelection {
        val (variant, contents) = downloads.resolveMembership(game, variantId, contentIds)
        return canonicalMapping(game, variant, contents)
    }

    private fun canonicalMapping(game: Game, variant: GameVariant, contents: List<VariantContent>): OwnedSnapshotLogicalSelection {
        val ordered = contents.sortedBy { requireNotNull(it.id) }
        val ids = ordered.map { requireNotNull(it.id) }; require(ids.distinct().size == ids.size)
        val root = requireNotNull(Path.of(variant.path).toAbsolutePath().normalize().parent)
        val sources = mutableListOf<Path>()
        val selected = ordered.flatMapIndexed { index, content ->
            content.effectivePaths().map { Path.of(it).toAbsolutePath().normalize() }.sortedBy { it.toString() }.map { path ->
                require(path.startsWith(root) && path != root) { "Selection outside host content root" }
                sources.add(path)
                SnapshotSelection(root.relativize(path).toString().replace('\\', '/'), "content-${index + 1}/${path.fileName}")
            }
        }
        return OwnedSnapshotLogicalSelection(requireNotNull(game.id), requireNotNull(variant.id), ids, root, sources.toList(), selected)
    }

    /** Fresh authorization membership and filesystem proofs run outside owner DB locks.
     * A proved source mismatch is not corruption of the old still-referenced cache.
     */
    fun verifyCurrentSources(lease: OwnedSnapshotLease, game: Game, variantId: Long?, contentIds: List<Long>?): SnapshotSourceProof {
        val record = registry.get(lease.snapshotId)
        val current = resolveCanonicalSelection(game, variantId, contentIds)
        require(record.gameId == current.gameId && record.variantId == current.variantId)
        val original = SnapshotProtocol.decode(record.request) {
            require(readInt() == SnapshotProtocol.VERSION && readInt() == SnapshotProtocol.CAPTURE); SnapshotCapture.read(this)
        }
        require(original.cacheRoot == settings.cacheRoot.toString())
        val fresh = original.copy(sourceRoot = current.sourceRoot.toString(), selections = current.selections)
        return sourceLeases.acquire(current.sources).use { lease.verifyCurrentSources(fresh) }
    }

    fun acquire(game: Game, variantId: Long?, contentIds: List<Long>?, onReserved: (UUID) -> Unit = {},
        plannedSnapshotId: UUID? = null, hostRequestDigest: String? = null): OwnedSnapshotLease {
        if (!acquisition.tryAcquire()) throw OwnedSnapshotAcquisitionFailure(null,
            IllegalStateException("Snapshot acquisition already active")).apply { confirmNoRetainedState() }
        var worker: SnapshotWorkerConnection? = null
        var operation: UUID? = null
        var sourceLease: AutoCloseable? = null
        var slotHeld = false
        var handedOff = false
        var primary: Throwable? = null
        var failedAcquisition: OwnedSnapshotAcquisitionFailure? = null
        var workerStartAttempted = false
        var admissionRefused = false
        try {
            val owner = workers.ownerIdentity() // fail before source I/O if host scope is unavailable
            val selection = resolveCanonicalSelection(game, variantId, contentIds)
            val gameId = selection.gameId; val canonicalVariantId = selection.variantId
            sourceLease = sourceLeases.acquire(selection.sources)
            require(workerSlots.tryAcquire()) { "Owned snapshot workers at capacity" }; slotHeld = true
            workerStartAttempted = true; worker = workers.start()
            val cacheIdentity = hello(worker, "")
            // The durable caller may predeclare its exact operation identity before
            // entering acquisition. An existing ID is still refused, NEVER resumed
            // or recopied here; reconciliation uses the separately verified APIs.
            val id = plannedSnapshotId ?: UUID.randomUUID()
            val request = SnapshotCapture(id, UUID.randomUUID(), selection.sourceRoot.toString(), settings.cacheRoot.toString(), cacheIdentity,
                selection.selections, settings.byteLimit, settings.fileLimit, settings.reserveBytes)
            // Commit before COPY/publication; never blind-retry an unknown operation.
            // Assign BEFORE reserve: an ambiguous database COMMIT cannot be treated
            // as proof of absence or lose the intended identity for operator recovery.
            operation = id
            reserveAndBegin(request, gameId, canonicalVariantId, owner, worker.identity(), onReserved, hostRequestDigest) { admissionRefused = true }
            // PREPARE contains no source reads. Persist exact opened directory identities
            // before allowing COPY, so partial copied trees can be safely reconciled.
            val preparation = worker.exchange(request.bytes(SnapshotProtocol.PREPARE))
            registry.recordPreparation(id, preparation)
            val encoded = worker.exchange(request.bytes())
            val manifest = checkedManifest(registry.get(id), encoded)
            val token = registry.readyAndLease(id, encoded, owner, worker.identity())
            sourceLease.close(); sourceLease = null
            return OwnedSnapshotLease(id, token, manifest, worker, registry) { workerSlots.release() }
                .also { worker = null; handedOff = true }
        } catch (failure: Throwable) {
            val retained = OwnedSnapshotAcquisitionFailure(operation, failure, admissionRefused)
            primary = retained; failedAcquisition = retained
            if (!retained.reservationRefused) operation?.let { runCatching { registry.uncertain(it) }.exceptionOrNull()?.let(retained::addSuppressed) }
            throw retained
        } finally {
            val exited = worker?.let { runCatching { it.finish() }.getOrDefault(false) } ?: (!workerStartAttempted || handedOff)
            // If worker exit is unknown, retain source retirement protection as well.
            if (exited) {
                val leaseFailure = runCatching { sourceLease?.close() }.exceptionOrNull()
                if (slotHeld && !handedOff) workerSlots.release()
                acquisition.release()
                if ((operation == null || failedAcquisition?.reservationRefused == true) && leaseFailure == null)
                    failedAcquisition?.confirmNoRetainedState()
                if (leaseFailure != null) {
                    if (primary != null) primary.addSuppressed(leaseFailure) else throw leaseFailure
                }
            }
            // Unknown worker exit deliberately retains both permits and source lease.
        }
    }

    /** Shared with focused host-only ordering checks: request linkage must commit
     * before begin/PREPARE. Callback failure must never let native copying proceed.
     */
    internal fun reserveAndBegin(request: SnapshotCapture, gameId: Long, variantId: Long,
        owner: SnapshotProcessIdentity, worker: SnapshotProcessIdentity, onReserved: (UUID) -> Unit,
        hostRequestDigest: String? = null, onAdmissionRefused: () -> Unit = {}) {
        try {
            registry.reserve(request.snapshotId, request.operation, gameId, variantId, request.bytes(), owner, worker,
                settings.maxUnretiredSnapshots, settings.maxUnretiredBytes, hostRequestDigest)
        } catch (refused: SnapshotAdmissionRefused) { onAdmissionRefused(); throw refused }
        onReserved(request.snapshotId)
        registry.begin(request.snapshotId)
    }

    /** Optional explicit retention after all exact browser/client/descriptor leases ended.
     * No automatic expiry, source deletion or broad cache scan. Unknown ownership is kept.
     */
    fun retire(id: UUID) {
        val record = registry.get(id)
        if (record.state == OwnedSnapshotState.RETIRED) return
        val preparation = requireNotNull(record.preparation) { "Unknown prepare outcome remains quarantined" }
        val prepared = org.gameyfin.snapshot.SnapshotPreparation.read(preparation)
        require(registry.reconcileAcquisitionExit(id, SnapshotProcessProbe.SYSTEM) || record.workerExitProvedAt != null) { "Prior worker exit requires explicit reconciliation" }
        registry.reconcileExitedSessionLeases(id, SnapshotProcessProbe.SYSTEM)
        require(workerSlots.tryAcquire()) { "Owned snapshot workers at capacity" }
        var worker: SnapshotWorkerConnection? = null
        var started = false
        var primary: Throwable? = null
        try {
            worker = workers.start()
            val identity = worker.identity()
            require(hello(worker, prepared.cacheIdentity) == prepared.cacheIdentity)
            registry.beginRetirement(id, workers.ownerIdentity(), identity, SnapshotProcessProbe.SYSTEM)
            started = true
            worker.exchange(SnapshotProtocol.encode {
                writeInt(SnapshotProtocol.VERSION); writeInt(SnapshotProtocol.RETIRE)
                writeInt(preparation.size); write(preparation)
            })
            check(worker.finish()) { "Retirement worker exit remains uncertain" }
            registry.finishRetirement(id, identity)
        } catch (failure: Throwable) {
            primary = failure
            if (started) runCatching { registry.uncertain(id) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            val exited = worker?.let { runCatching { it.finish() }.getOrElse { failure -> primary?.addSuppressed(failure); false } } ?: true
            if (exited) workerSlots.release()
        }
    }

    /** Reconcile only this persisted identity. No directory discovery, adoption, retries,
     * source reads or deletion. Existing unresolved leases are deliberately retained.
     */
    fun reopen(id: UUID): OwnedSnapshotLease = reopenOwned(id, null, null)

    fun resumeClientLease(id: UUID, leaseId: UUID, clientId: UUID, ownershipId: UUID): OwnedSnapshotLease {
        registry.requireClientLease(id, leaseId, clientId, ownershipId)
        return reopenOwned(id, leaseId, SnapshotClientBinding(clientId, ownershipId))
    }

    private fun reopenOwned(id: UUID, retainedLeaseId: UUID?, binding: SnapshotClientBinding?): OwnedSnapshotLease {
        val record = registry.get(id)
        require(record.state != OwnedSnapshotState.RESERVED) { "No acquisition began" }
        val request = SnapshotProtocol.decode(record.request) {
            require(readInt() == SnapshotProtocol.VERSION && readInt() == SnapshotProtocol.CAPTURE); SnapshotCapture.read(this)
        }
        require(request.cacheRoot == settings.cacheRoot.toString()) { "Configured cache root changed" }
        if (retainedLeaseId == null) registry.reconcileExitedSessionLeases(id, SnapshotProcessProbe.SYSTEM)
        require(workerSlots.tryAcquire()) { "Owned snapshot workers at capacity" }
        val protectedLeaseId = try { retainedLeaseId ?: registry.reserveReopenLease(id, workers.ownerIdentity()) }
            catch (failure: Throwable) { workerSlots.release(); throw failure }
        val consumerClaim = try { if (retainedLeaseId != null) {
            val client = requireNotNull(binding)
            registry.reconcileExitedConsumers(id, retainedLeaseId, client.clientId, client.ownershipId, SnapshotProcessProbe.SYSTEM)
            registry.claimClientLease(id, retainedLeaseId, client.clientId, client.ownershipId, workers.ownerIdentity())
        } else null } catch (failure: Throwable) { workerSlots.release(); throw failure }
        val worker = try { workers.start() } catch (failure: Throwable) {
            if (consumerClaim != null) runCatching { registry.releaseConsumer(id, requireNotNull(retainedLeaseId), consumerClaim) }.exceptionOrNull()?.let(failure::addSuppressed)
            else if (retainedLeaseId == null) runCatching { registry.release(id, protectedLeaseId) }.exceptionOrNull()?.let(failure::addSuppressed)
            workerSlots.release(); throw failure
        }
        try {
            // No root or snapshot OPEN command may precede this committed identity.
            if (consumerClaim != null) registry.bindConsumerWorker(id, protectedLeaseId, consumerClaim, worker.identity())
            else registry.bindLeaseWorker(id, protectedLeaseId, worker.identity())
            require(hello(worker, request.expectedCacheIdentity) == request.expectedCacheIdentity)
            val encoded = worker.exchange(SnapshotProtocol.encode {
                writeInt(SnapshotProtocol.VERSION); writeInt(SnapshotProtocol.OPEN)
                SnapshotProtocol.text(this, record.id.toString()); SnapshotProtocol.text(this, record.operation.toString()); SnapshotProtocol.text(this, record.requestDigest)
            })
            val manifest = checkedManifest(record, encoded)
            registry.ready(id, encoded)
            return OwnedSnapshotLease(id, protectedLeaseId, manifest, worker, registry, binding, consumerClaim) { workerSlots.release() }
        } catch (failure: Throwable) {
            runCatching { registry.uncertain(id) }.exceptionOrNull()?.let(failure::addSuppressed)
            // Persistence failure cannot bypass termination of the exact owned child.
            val exited = runCatching { worker.finish() }.getOrElse { failure.addSuppressed(it); false }
            if (exited) {
                workerSlots.release()
                if (consumerClaim != null) runCatching { registry.releaseConsumer(id, requireNotNull(retainedLeaseId), consumerClaim) }.exceptionOrNull()?.let(failure::addSuppressed)
                else if (retainedLeaseId == null) runCatching { registry.release(id, protectedLeaseId) }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
    }
    private fun hello(worker: SnapshotWorkerConnection, expected: String): String = SnapshotProtocol.decode(worker.exchange(SnapshotProtocol.encode {
        writeInt(SnapshotProtocol.VERSION); writeInt(SnapshotProtocol.HELLO)
        SnapshotProtocol.text(this, settings.cacheRoot.toString()); SnapshotProtocol.text(this, expected)
    })) { SnapshotProtocol.text(this) }
    private fun checkedManifest(record: SnapshotRecordView, encoded: ByteArray): SnapshotManifest {
        val manifest = SnapshotManifest.read(encoded)
        require(manifest.snapshotId == record.id && manifest.operation == record.operation && manifest.requestDigest == record.requestDigest)
        require(record.manifestDigest == null || SnapshotProtocol.digest(encoded) == record.manifestDigest)
        return manifest
    }
}

/** Persistent worker retains the validated directory descriptor for every read. Closing a
 * response stream does not release the snapshot lease; the owning host lifecycle must close
 * this whole lease after all consumers (and future seed ownership) have stopped.
 */
internal data class SnapshotClientBinding(val clientId: UUID, val ownershipId: UUID)

internal class OwnedSnapshotLease(val snapshotId: UUID, val leaseId: UUID, val manifest: SnapshotManifest,
    private val worker: SnapshotWorkerConnection, private val registry: OwnedSnapshotRegistry,
    private var clientBinding: SnapshotClientBinding? = null, private var consumerClaim: UUID? = null,
    private val releaseWorkerSlot: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()
    private var workerExited = false
    private var databaseReleased = false
    @Synchronized fun retainForClient(clientId: UUID, ownershipId: UUID) {
        check(!closed.get())
        val binding = SnapshotClientBinding(clientId, ownershipId)
        require(clientBinding == null || clientBinding == binding)
        if (clientBinding == binding && consumerClaim != null) return
        consumerClaim = registry.bindClient(snapshotId, leaseId, clientId, ownershipId)
        clientBinding = binding
    }
    @Synchronized fun verifyForClient(expectedCacheIdentity: String) {
        check(!closed.get())
        require(manifest.cacheIdentity == expectedCacheIdentity)
        val encoded = worker.exchange(SnapshotProtocol.encode { writeInt(SnapshotProtocol.VERSION); writeInt(SnapshotProtocol.VERIFY) })
        require(SnapshotProtocol.digest(encoded) == SnapshotProtocol.digest(manifest.bytes())) { "Held snapshot identity changed" }
    }
    @Synchronized internal fun verifyCurrentSources(request: SnapshotCapture): SnapshotSourceProof {
        check(!closed.get())
        require(request.snapshotId == snapshotId && request.operation == manifest.operation)
        val proof = SnapshotSourceProof.read(worker.exchange(request.bytes(SnapshotProtocol.VERIFY_SOURCE)))
        require(proof.manifestDigest == SnapshotProtocol.digest(manifest.bytes())) { "Source proof acknowledged a different cache generation" }
        return proof
    }
    /** Only the owning lifecycle calls this after exact stopped torrent removal with
     * deleteFiles=false AND creator-task absence have both been verified. No source/cache
     * deletion occurs here. A database outage permits retry of this same ownership tuple.
     */
    @Synchronized fun releaseAfterClientRemoval(clientId: UUID, ownershipId: UUID) {
        require(clientBinding == SnapshotClientBinding(clientId, ownershipId))
        close()
        check(workerExited) { "Worker exit remains uncertain" }
        if (!databaseReleased) {
            registry.releaseClient(snapshotId, leaseId, clientId, ownershipId)
            databaseReleased = true
        }
    }
    fun openMember(name: String): InputStream {
        check(!closed.get())
        val member = manifest.members.firstOrNull { it.name == name } ?: error("Not an owned snapshot member")
        return object : InputStream() {
            private var offset = 0L
            private var streamClosed = false
            override fun read(): Int { val bytes = ByteArray(1); return if (read(bytes) == -1) -1 else bytes[0].toInt() and 255 }
            override fun read(bytes: ByteArray, start: Int, length: Int): Int {
                java.util.Objects.checkFromIndexSize(start, length, bytes.size)
                check(!streamClosed && !closed.get())
                if (length == 0) return 0
                if (offset == member.bytes) return -1
                val result = worker.exchange(SnapshotProtocol.encode {
                    writeInt(SnapshotProtocol.VERSION); writeInt(SnapshotProtocol.READ); SnapshotProtocol.text(this, name)
                    writeLong(offset); writeInt(minOf(length, SnapshotProtocol.BLOCK))
                })
                require(result.isNotEmpty() && result.size <= minOf(length.toLong(), member.bytes - offset))
                result.copyInto(bytes, start); offset += result.size
                return result.size
            }
            override fun close() { streamClosed = true }
        }
    }
    /** Host readiness/shutdown must use this when absence of the descriptor worker
     * is an acceptance condition. Plain close conservatively retains unknown exits.
     * External qB ownership is deliberately NOT released by this method.
     */
    @Synchronized fun closeAndRequireExit() {
        close()
        check(workerExited) { "Owned descriptor worker exit remains unproven" }
    }
    @Synchronized override fun close() {
        closed.set(true)
        if (!workerExited) {
            workerExited = worker.finish()
            if (workerExited) releaseWorkerSlot()
        }
        if (workerExited && consumerClaim != null) {
            registry.releaseConsumer(snapshotId, leaseId, requireNotNull(consumerClaim))
            consumerClaim = null
        }
        if (workerExited && !databaseReleased && clientBinding == null) {
            // If H2 is unavailable, retain the durable lease; another close can retry
            // this exact token without restarting or touching a filesystem object.
            registry.release(snapshotId, leaseId)
            databaseReleased = true
        } else if (!workerExited) {
            registry.uncertain(snapshotId)
        }
    }
}
