package org.gameyfin.app.core.download.torrent

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

internal enum class OwnedSnapshotState { RESERVED, IN_FLIGHT, READY, UNCERTAIN, RETIRING, RETIRED }

/** Only the locked, pre-write admission predicate creates this marker. It is NOT
 * interchangeable with a generic validation/database error or unknown COMMIT.
 */
internal class SnapshotAdmissionRefused(message: String) : IllegalStateException(message)
internal enum class SnapshotLeaseBindingState { UNBOUND_ACTIVE, UNBOUND_RELEASED, BOUND_ACTIVE, BOUND_RELEASED }

/** One persistent admission lock serializes all host instances sharing this database.
 * Unknown PREPARE outcomes remain charged until exact retirement, never age-expire.
 */
@Entity
internal class OwnedSnapshotAdmissionRecord(@Id val id: Int = 1)
internal interface OwnedSnapshotAdmissionRepository : JpaRepository<OwnedSnapshotAdmissionRecord, Int> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from OwnedSnapshotAdmissionRecord a where a.id = 1")
    fun locked(): OwnedSnapshotAdmissionRecord?
}

/** Source names live only in this private host record, never browser/plugin DTOs or logs. */
@Entity
internal class OwnedSnapshotRecord(
    @Id val id: UUID,
    @Column(nullable = false, unique = true) val operation: UUID,
    @Column(nullable = false) val gameId: Long,
    @Column(nullable = false) val variantId: Long,
    @Lob @Column(nullable = false) val request: ByteArray,
    @Column(nullable = false, length = 64) val requestDigest: String,
    @Column(nullable = false) val reservedBytes: Long,
    @Column(length = 64, updatable = false) val hostRequestDigest: String? = null,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
) {
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false)
    var state: OwnedSnapshotState = OwnedSnapshotState.RESERVED
    @Lob var manifest: ByteArray? = null
    @Column(length = 64) var manifestDigest: String? = null
    @Column(nullable = false) var updatedAt: Instant = createdAt
    @Version var version: Long = 0
    var creatorScope: String? = null
    var creatorPid: Long? = null
    var creatorStartedMillis: Long? = null
    var workerScope: String? = null
    var workerPid: Long? = null
    var workerStartedMillis: Long? = null
    var workerExitProvedAt: Instant? = null
    @Lob var preparation: ByteArray? = null
}

/** A lease survives host death. It is never cleared merely because a JVM restarted.
 * Eventual seeding leases must also retain exact external-client ownership before release.
 */
@Entity
internal class OwnedSnapshotLeaseRecord(
    @Id val id: UUID,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "snapshot_id", nullable = false)
    val snapshot: OwnedSnapshotRecord,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
) {
    var releasedAt: Instant? = null
    var clientId: UUID? = null
    var clientOwnershipId: UUID? = null
    var workerScope: String? = null
    var workerPid: Long? = null
    var workerStartedMillis: Long? = null
    var creatorScope: String? = null
    var creatorPid: Long? = null
    var creatorStartedMillis: Long? = null
}

/** Each descriptor-owning consumer has its own durable claim. Resuming one external
 * lease twice cannot make either worker invisible to retention or client release.
 */
@Entity
internal class OwnedSnapshotConsumerRecord(
    @Id val id: UUID,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "lease_id", nullable = false)
    val lease: OwnedSnapshotLeaseRecord,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
) {
    var releasedAt: Instant? = null
    var workerScope: String? = null
    var workerPid: Long? = null
    var workerStartedMillis: Long? = null
    var creatorScope: String? = null
    var creatorPid: Long? = null
    var creatorStartedMillis: Long? = null
}

internal interface OwnedSnapshotRepository : JpaRepository<OwnedSnapshotRecord, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from OwnedSnapshotRecord s where s.id = :id")
    fun locked(@Param("id") id: UUID): OwnedSnapshotRecord?
    @Query("select count(s) from OwnedSnapshotRecord s where s.state <> org.gameyfin.app.core.download.torrent.OwnedSnapshotState.RETIRED")
    fun unretiredCount(): Long
    @Query("select coalesce(sum(s.reservedBytes), 0) from OwnedSnapshotRecord s where s.state <> org.gameyfin.app.core.download.torrent.OwnedSnapshotState.RETIRED")
    fun unretiredBytes(): Long
}
internal interface OwnedSnapshotLeaseRepository : JpaRepository<OwnedSnapshotLeaseRecord, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from OwnedSnapshotLeaseRecord l where l.id = :id")
    fun locked(@Param("id") id: UUID): OwnedSnapshotLeaseRecord?
    fun countBySnapshotIdAndReleasedAtIsNull(snapshotId: UUID): Long
    fun findBySnapshotIdAndReleasedAtIsNullAndClientIdIsNull(snapshotId: UUID): List<OwnedSnapshotLeaseRecord>
}
internal interface OwnedSnapshotConsumerRepository : JpaRepository<OwnedSnapshotConsumerRecord, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from OwnedSnapshotConsumerRecord c where c.id = :id")
    fun locked(@Param("id") id: UUID): OwnedSnapshotConsumerRecord?
    fun countByLeaseIdAndReleasedAtIsNull(leaseId: UUID): Long
    fun findByLeaseIdAndReleasedAtIsNull(leaseId: UUID): List<OwnedSnapshotConsumerRecord>
}

internal data class SnapshotRecordView(val id: UUID, val operation: UUID, val gameId: Long, val variantId: Long,
    val request: ByteArray, val requestDigest: String, val state: OwnedSnapshotState, val manifest: ByteArray?, val manifestDigest: String?,
    val creator: SnapshotProcessIdentity? = null, val worker: SnapshotProcessIdentity? = null, val workerExitProvedAt: Instant? = null,
    val preparation: ByteArray? = null, val hostRequestDigest: String? = null)

@Service
internal class OwnedSnapshotRegistry(private val snapshots: OwnedSnapshotRepository,
    private val leases: OwnedSnapshotLeaseRepository, manager: PlatformTransactionManager,
    private val consumers: OwnedSnapshotConsumerRepository, private val admission: OwnedSnapshotAdmissionRepository) {
    private val transaction = TransactionTemplate(manager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
    fun reserve(id: UUID, operation: UUID, gameId: Long, variantId: Long, request: ByteArray,
        creator: SnapshotProcessIdentity? = null, worker: SnapshotProcessIdentity? = null,
        maxUnretiredSnapshots: Int = 256, maxUnretiredBytes: Long = 1024L * 1024 * 1024 * 1024,
        hostRequestDigest: String? = null): SnapshotRecordView = commit {
        require(request.size in 1..org.gameyfin.snapshot.SnapshotProtocol.MAX_FRAME)
        require(hostRequestDigest == null || hostRequestDigest.matches(Regex("[0-9a-f]{64}")))
        require(maxUnretiredSnapshots in 1..256 && maxUnretiredBytes in 1..4L * 1024 * 1024 * 1024 * 1024)
        val parsed = org.gameyfin.snapshot.SnapshotProtocol.decode(request) {
            require(readInt() == org.gameyfin.snapshot.SnapshotProtocol.VERSION && readInt() == org.gameyfin.snapshot.SnapshotProtocol.CAPTURE)
            org.gameyfin.snapshot.SnapshotCapture.read(this)
        }
        require(parsed.snapshotId == id && parsed.operation == operation)
        requireNotNull(admission.locked()) { "Snapshot admission record unavailable" }
        require(!snapshots.existsById(id)) { "Snapshot identity already exists" }
        if (snapshots.unretiredCount() >= maxUnretiredSnapshots)
            throw SnapshotAdmissionRefused("Unretired snapshot admission limit reached; reconcile owned operations")
        val used = snapshots.unretiredBytes()
        check(used >= 0) { "Invalid reserved byte accounting" }
        if (parsed.byteLimit > maxUnretiredBytes - used) throw SnapshotAdmissionRefused("Aggregate snapshot copy budget exhausted")
        view(snapshots.saveAndFlush(OwnedSnapshotRecord(id, operation, gameId, variantId, request.copyOf(), org.gameyfin.snapshot.SnapshotProtocol.digest(request), parsed.byteLimit, hostRequestDigest).apply {
            creator?.let { creatorScope = it.scope; creatorPid = it.pid; creatorStartedMillis = it.startedMillis }
            worker?.let { workerScope = it.scope; workerPid = it.pid; workerStartedMillis = it.startedMillis }
        }))
    }
    fun get(id: UUID): SnapshotRecordView = commit { view(requireNotNull(snapshots.locked(id))) }
    /** null means a successful authoritative lookup found no exact operation.
     * Database/lock failures propagate and must never be interpreted as absence.
     */
    fun find(id: UUID): SnapshotRecordView? = transaction.execute { snapshots.locked(id)?.let(::view) }
    fun begin(id: UUID) = update(id) { require(it.state == OwnedSnapshotState.RESERVED); it.state = OwnedSnapshotState.IN_FLIGHT }
    fun uncertain(id: UUID) = update(id) {
        require(it.state != OwnedSnapshotState.RETIRED) { "Retired snapshot cannot be reactivated" }
        it.state = OwnedSnapshotState.UNCERTAIN
    }
    fun recordPreparation(id: UUID, encoded: ByteArray) = update(id) {
        require(it.state == OwnedSnapshotState.IN_FLIGHT)
        require(encoded.size in 1..4096)
        val prepared = org.gameyfin.snapshot.SnapshotPreparation.read(encoded)
        require(prepared.snapshotId == it.id && prepared.operation == it.operation && prepared.requestDigest == it.requestDigest)
        require(it.preparation == null || it.preparation!!.contentEquals(encoded)) { "Prepared directory identity rebound" }
        it.preparation = encoded.copyOf()
    }
    fun reconcileAcquisitionExit(id: UUID, probe: SnapshotProcessProbe): Boolean = commit {
        val row = requireNotNull(snapshots.locked(id))
        val worker = identity(row.workerScope, row.workerPid, row.workerStartedMillis)
        val owner = worker ?: identity(row.creatorScope, row.creatorPid, row.creatorStartedMillis)
        val exited = owner != null && probe.exitProved(owner)
        if (exited) { row.workerExitProvedAt = Instant.now(); snapshots.saveAndFlush(row) }
        exited
    }
    fun ready(id: UUID, manifest: ByteArray) = update(id) {
        applyReady(it, manifest)
    }
    private fun applyReady(it: OwnedSnapshotRecord, manifest: ByteArray) {
        require(it.state == OwnedSnapshotState.IN_FLIGHT || it.state == OwnedSnapshotState.UNCERTAIN || it.state == OwnedSnapshotState.READY)
        require(manifest.size in 1 until org.gameyfin.snapshot.SnapshotProtocol.MAX_FRAME)
        val parsed = org.gameyfin.snapshot.SnapshotManifest.read(manifest)
        require(parsed.snapshotId == it.id && parsed.operation == it.operation && parsed.requestDigest == it.requestDigest)
        it.preparation?.let { encoded ->
            val prepared = org.gameyfin.snapshot.SnapshotPreparation.read(encoded)
            require(parsed.cacheIdentity == prepared.cacheIdentity && parsed.directoryIdentity == prepared.directoryIdentity) { "Published directory differs from committed preparation" }
        }
        val digest = org.gameyfin.snapshot.SnapshotProtocol.digest(manifest)
        require(it.manifestDigest == null || it.manifestDigest == digest) { "Snapshot identity rebound" }
        it.manifest = manifest.copyOf(); it.manifestDigest = digest; it.state = OwnedSnapshotState.READY
    }
    /** READY publication and its first consuming worker lease are one committed unit. */
    fun readyAndLease(id: UUID, manifest: ByteArray, creator: SnapshotProcessIdentity, worker: SnapshotProcessIdentity): UUID = commit {
        val snapshot = requireNotNull(snapshots.locked(id)); applyReady(snapshot, manifest); snapshots.saveAndFlush(snapshot)
        val token = UUID.randomUUID()
        leases.saveAndFlush(OwnedSnapshotLeaseRecord(token, snapshot).apply {
            creatorScope = creator.scope; creatorPid = creator.pid; creatorStartedMillis = creator.startedMillis
            workerScope = worker.scope; workerPid = worker.pid; workerStartedMillis = worker.startedMillis
        }); token
    }
    /** A protective read/reconciliation lease commits BEFORE opening an existing tree. */
    fun reserveReopenLease(id: UUID, creator: SnapshotProcessIdentity): UUID = commit {
        val snapshot = requireNotNull(snapshots.locked(id)); require(snapshot.state in setOf(OwnedSnapshotState.READY, OwnedSnapshotState.UNCERTAIN, OwnedSnapshotState.IN_FLIGHT))
        require(leases.countBySnapshotIdAndReleasedAtIsNull(id) < 64) { "Unresolved snapshot lease limit reached" }
        val token = UUID.randomUUID()
        leases.saveAndFlush(OwnedSnapshotLeaseRecord(token, snapshot).apply {
            creatorScope = creator.scope; creatorPid = creator.pid; creatorStartedMillis = creator.startedMillis
        }); token
    }
    fun lease(id: UUID, creator: SnapshotProcessIdentity? = null): UUID = commit {
        val snapshot = requireNotNull(snapshots.locked(id)); require(snapshot.state == OwnedSnapshotState.READY)
        val token = UUID.randomUUID()
        leases.saveAndFlush(OwnedSnapshotLeaseRecord(token, snapshot).apply { creator?.let { creatorScope = it.scope; creatorPid = it.pid; creatorStartedMillis = it.startedMillis } })
        token
    }
    fun beginRetirement(id: UUID, creator: SnapshotProcessIdentity, worker: SnapshotProcessIdentity, probe: SnapshotProcessProbe): SnapshotRecordView = commit {
        val row = requireNotNull(snapshots.locked(id))
        require(row.state in setOf(OwnedSnapshotState.READY, OwnedSnapshotState.UNCERTAIN, OwnedSnapshotState.RETIRING))
        require(row.preparation != null && leases.countBySnapshotIdAndReleasedAtIsNull(id) == 0L) { "Snapshot identity or leases remain unresolved" }
        val previous = identity(row.workerScope, row.workerPid, row.workerStartedMillis)
        require(row.workerExitProvedAt != null || (previous != null && probe.exitProved(previous))) { "Prior worker exit unproven" }
        row.state = OwnedSnapshotState.RETIRING
        row.creatorScope = creator.scope; row.creatorPid = creator.pid; row.creatorStartedMillis = creator.startedMillis
        row.workerScope = worker.scope; row.workerPid = worker.pid; row.workerStartedMillis = worker.startedMillis
        row.workerExitProvedAt = null; row.updatedAt = Instant.now()
        view(snapshots.saveAndFlush(row))
    }
    fun finishRetirement(id: UUID, worker: SnapshotProcessIdentity): SnapshotRecordView = update(id) {
        require(it.state == OwnedSnapshotState.RETIRING && identity(it.workerScope, it.workerPid, it.workerStartedMillis) == worker)
        require(leases.countBySnapshotIdAndReleasedAtIsNull(id) == 0L)
        it.workerExitProvedAt = Instant.now(); it.state = OwnedSnapshotState.RETIRED
    }
    fun bindLeaseWorker(id: UUID, token: UUID, worker: SnapshotProcessIdentity) = commit {
        requireNotNull(snapshots.locked(id)); val lease = requireNotNull(leases.locked(token))
        require(lease.snapshot.id == id && lease.releasedAt == null && lease.clientId == null)
        require(lease.workerPid == null || identity(lease.workerScope, lease.workerPid, lease.workerStartedMillis) == worker)
        lease.workerScope = worker.scope; lease.workerPid = worker.pid; lease.workerStartedMillis = worker.startedMillis
        leases.saveAndFlush(lease); Unit
    }
    /** Only the supervisor that proved its owned worker exited may release this token. */
    fun bindClient(id: UUID, token: UUID, clientId: UUID, ownershipId: UUID): UUID = commit {
        val snapshot = requireNotNull(snapshots.locked(id)); require(snapshot.state == OwnedSnapshotState.READY)
        val lease = requireNotNull(leases.locked(token)); require(lease.snapshot.id == id && lease.releasedAt == null)
        require(lease.clientId == null || (lease.clientId == clientId && lease.clientOwnershipId == ownershipId))
        lease.clientId = clientId; lease.clientOwnershipId = ownershipId; leases.saveAndFlush(lease)
        val claim = UUID.randomUUID()
        consumers.saveAndFlush(OwnedSnapshotConsumerRecord(claim, lease).apply {
            workerScope = lease.workerScope; workerPid = lease.workerPid; workerStartedMillis = lease.workerStartedMillis
            creatorScope = lease.creatorScope; creatorPid = lease.creatorPid; creatorStartedMillis = lease.creatorStartedMillis
        }); claim
    }
    fun requireClientLease(id: UUID, token: UUID, clientId: UUID, ownershipId: UUID) = commit {
        requireNotNull(snapshots.locked(id))
        val lease = requireNotNull(leases.locked(token))
        require(lease.snapshot.id == id && lease.releasedAt == null && lease.clientId == clientId && lease.clientOwnershipId == ownershipId)
        Unit
    }
    /** Read-only finalization proof for ONE persisted ownership tuple. Absence, a
     * foreign tuple or an active lease is never inferred to mean released.
     */
    fun clientLeaseReleased(id: UUID, token: UUID, clientId: UUID, ownershipId: UUID): Boolean = commit {
        requireNotNull(snapshots.locked(id))
        val lease = requireNotNull(leases.locked(token))
        require(lease.snapshot.id == id && lease.clientId == clientId && lease.clientOwnershipId == ownershipId)
        lease.releasedAt != null
    }
    /** A journal RESERVED commit can precede client binding. Inspect its ORIGINAL
     * token; never invent another lease or reinterpret a foreign client as unbound.
     */
    fun leaseBindingState(id: UUID, token: UUID, clientId: UUID, ownershipId: UUID): SnapshotLeaseBindingState = commit {
        requireNotNull(snapshots.locked(id))
        val lease = requireNotNull(leases.locked(token)); require(lease.snapshot.id == id)
        if (lease.clientId == null && lease.clientOwnershipId == null) {
            require(consumers.countByLeaseIdAndReleasedAtIsNull(token) == 0L)
            if (lease.releasedAt == null) SnapshotLeaseBindingState.UNBOUND_ACTIVE else SnapshotLeaseBindingState.UNBOUND_RELEASED
        } else {
            require(lease.clientId == clientId && lease.clientOwnershipId == ownershipId) { "Different external client owns lease" }
            if (lease.releasedAt == null) SnapshotLeaseBindingState.BOUND_ACTIVE else SnapshotLeaseBindingState.BOUND_RELEASED
        }
    }
    /** Used ONLY by the lifecycle's RESERVED/no-CREATE cancellation branch. This
     * exact unbound token may finish after its old descriptor/creator proved exited.
     * No caller assertion, foreign PID absence, or new lease substitutes for proof.
     */
    fun releaseUnboundLeaseAfterExit(id: UUID, token: UUID, probe: SnapshotProcessProbe): Boolean = commit {
        requireNotNull(snapshots.locked(id))
        val lease = requireNotNull(leases.locked(token))
        require(lease.snapshot.id == id && lease.clientId == null && lease.clientOwnershipId == null)
        require(consumers.countByLeaseIdAndReleasedAtIsNull(token) == 0L)
        if (lease.releasedAt != null) return@commit true
        val worker = identity(lease.workerScope, lease.workerPid, lease.workerStartedMillis)
        val owner = worker ?: identity(lease.creatorScope, lease.creatorPid, lease.creatorStartedMillis)
        if (owner == null || !probe.exitProved(owner)) return@commit false
        lease.releasedAt = Instant.now(); leases.saveAndFlush(lease); true
    }
    fun claimClientLease(id: UUID, token: UUID, clientId: UUID, ownershipId: UUID, creator: SnapshotProcessIdentity? = null): UUID = commit {
        val snapshot = requireNotNull(snapshots.locked(id)); require(snapshot.state == OwnedSnapshotState.READY || snapshot.state == OwnedSnapshotState.UNCERTAIN)
        val lease = requireNotNull(leases.locked(token))
        require(lease.snapshot.id == id && lease.releasedAt == null && lease.clientId == clientId && lease.clientOwnershipId == ownershipId)
        require(consumers.countByLeaseIdAndReleasedAtIsNull(token) < 64) { "Unresolved consumer claim limit reached" }
        val claim = UUID.randomUUID()
        consumers.saveAndFlush(OwnedSnapshotConsumerRecord(claim, lease).apply { creator?.let { creatorScope = it.scope; creatorPid = it.pid; creatorStartedMillis = it.startedMillis } })
        claim
    }
    fun bindConsumerWorker(id: UUID, token: UUID, claimId: UUID, worker: SnapshotProcessIdentity) = commit {
        requireNotNull(snapshots.locked(id)); val lease = requireNotNull(leases.locked(token)); require(lease.snapshot.id == id && lease.releasedAt == null)
        val consumer = requireNotNull(consumers.locked(claimId)); require(consumer.lease.id == token && consumer.releasedAt == null)
        require(consumer.workerPid == null || identity(consumer.workerScope, consumer.workerPid, consumer.workerStartedMillis) == worker)
        consumer.workerScope = worker.scope; consumer.workerPid = worker.pid; consumer.workerStartedMillis = worker.startedMillis
        consumers.saveAndFlush(consumer); Unit
    }
    /** Pure local process proof is permitted ONLY in the same trusted host/boot/PID scope.
     * An unbound claim cannot have received OPEN: bind must commit before worker commands.
     * Its creator's proven exit is therefore sufficient for that pre-consumption phase.
     */
    fun reconcileExitedConsumers(id: UUID, token: UUID, clientId: UUID, ownershipId: UUID, probe: SnapshotProcessProbe): Int = commit {
        requireNotNull(snapshots.locked(id)); val lease = requireNotNull(leases.locked(token))
        require(lease.snapshot.id == id && lease.releasedAt == null && lease.clientId == clientId && lease.clientOwnershipId == ownershipId)
        val active = consumers.findByLeaseIdAndReleasedAtIsNull(token); require(active.size <= 64)
        var released = 0
        for (consumer in active) {
            val worker = identity(consumer.workerScope, consumer.workerPid, consumer.workerStartedMillis)
            val owner = worker ?: identity(consumer.creatorScope, consumer.creatorPid, consumer.creatorStartedMillis)
            if (owner != null && probe.exitProved(owner)) { consumer.releasedAt = Instant.now(); consumers.saveAndFlush(consumer); released++ }
        }
        released
    }
    fun reconcileExitedSessionLeases(id: UUID, probe: SnapshotProcessProbe): Int = commit {
        requireNotNull(snapshots.locked(id))
        val active = leases.findBySnapshotIdAndReleasedAtIsNullAndClientIdIsNull(id); require(active.size <= 64)
        var released = 0
        for (lease in active) {
            val worker = identity(lease.workerScope, lease.workerPid, lease.workerStartedMillis)
            val owner = worker ?: identity(lease.creatorScope, lease.creatorPid, lease.creatorStartedMillis)
            if (owner != null && probe.exitProved(owner)) { lease.releasedAt = Instant.now(); leases.saveAndFlush(lease); released++ }
        }
        released
    }
    fun releaseConsumer(id: UUID, token: UUID, claimId: UUID) = commit {
        requireNotNull(snapshots.locked(id)); val lease = requireNotNull(leases.locked(token)); require(lease.snapshot.id == id)
        val consumer = requireNotNull(consumers.locked(claimId)); require(consumer.lease.id == token)
        if (consumer.releasedAt == null) consumer.releasedAt = Instant.now()
        consumers.saveAndFlush(consumer); Unit
    }
    fun release(id: UUID, token: UUID) = release(id, token, null, null)
    /** Called only after the owning client lifecycle has proved stopped/removal/task absence. */
    fun releaseClient(id: UUID, token: UUID, clientId: UUID, ownershipId: UUID) = release(id, token, clientId, ownershipId)
    private fun release(id: UUID, token: UUID, clientId: UUID?, ownershipId: UUID?) = commit {
        requireNotNull(snapshots.locked(id))
        val lease = requireNotNull(leases.locked(token)); require(lease.snapshot.id == id)
        require(lease.clientId == clientId && lease.clientOwnershipId == ownershipId) { "External client ownership still retains snapshot" }
        require(consumers.countByLeaseIdAndReleasedAtIsNull(token) == 0L) { "Descriptor consumers still retain snapshot" }
        if (lease.releasedAt == null) lease.releasedAt = Instant.now()
        leases.saveAndFlush(lease); Unit
    }
    fun activeLeases(id: UUID): Long = commit { leases.countBySnapshotIdAndReleasedAtIsNull(id) }
    private fun update(id: UUID, mutation: (OwnedSnapshotRecord) -> Unit): SnapshotRecordView = commit {
        val snapshot = requireNotNull(snapshots.locked(id)); mutation(snapshot); snapshot.updatedAt = Instant.now()
        view(snapshots.saveAndFlush(snapshot))
    }
    private fun view(row: OwnedSnapshotRecord) = SnapshotRecordView(row.id, row.operation, row.gameId, row.variantId,
        row.request.copyOf(), row.requestDigest, row.state, row.manifest?.copyOf(), row.manifestDigest,
        identity(row.creatorScope, row.creatorPid, row.creatorStartedMillis), identity(row.workerScope, row.workerPid, row.workerStartedMillis), row.workerExitProvedAt,
        row.preparation?.copyOf(), row.hostRequestDigest)
    private fun <T : Any> commit(action: () -> T): T = requireNotNull(transaction.execute { action() })
    private fun identity(scope: String?, pid: Long?, started: Long?): SnapshotProcessIdentity? =
        if (scope != null && pid != null && started != null) SnapshotProcessIdentity(scope, pid, started) else null
}
