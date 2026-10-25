package org.gameyfin.app.core.download.torrent

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

internal enum class QbDistributionState { RESERVED, CREATING, CREATED, VALIDATED, ADDING, STOPPED, CHECKING, STARTING, SEEDING, STOPPING, REMOVING, REMOVED, CANCELLING, CANCELLED, UNCERTAIN }

@Entity
@Table(name = "QB_DISTRIBUTION_INTENT", uniqueConstraints = [UniqueConstraint(columnNames = ["CLIENT_ID", "SNAPSHOT_ID"])])
internal class QbDistributionIntent(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(nullable = false) var clientId: UUID = UUID.randomUUID(),
    @Column(nullable = false) var snapshotId: UUID = UUID.randomUUID(),
    @Column(nullable = false) var leaseId: UUID = UUID.randomUUID(),
    @Column(nullable = false, length = 64) var manifestDigest: String = "",
    @Column(nullable = false, length = 64) var mappingDigest: String = "",
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 24) var state: QbDistributionState = QbDistributionState.RESERVED,
    @Column(length = 128) var taskId: String? = null,
    @Column(length = 40) var infoHash: String? = null,
    @Lob var metadata: ByteArray? = null,
    @Column(nullable = false) var updatedAt: Instant = Instant.now(),
    @Version var revision: Long = 0,
)

internal interface QbDistributionIntentRepository : JpaRepository<QbDistributionIntent, UUID> {
    fun findByClientIdAndSnapshotId(clientId: UUID, snapshotId: UUID): QbDistributionIntent?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findLockedById(id: UUID): QbDistributionIntent?
}

internal data class QbDistributionRecord(val id: UUID, val clientId: UUID, val snapshotId: UUID, val leaseId: UUID,
    val manifestDigest: String, val mappingDigest: String, val state: QbDistributionState, val taskId: String?,
    val infoHash: String?, val metadata: ByteArray?)

/** Each return commits before a caller may perform remote I/O. No remote calls in a transaction. */
@Service
internal class QbDistributionJournal(private val repository: QbDistributionIntentRepository, manager: PlatformTransactionManager) {
    private val tx = TransactionTemplate(manager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
    fun reserve(clientId: UUID, snapshotId: UUID, leaseId: UUID, manifestDigest: String, mappingDigest: String,
                operationId: UUID? = null): QbDistributionRecord = requireNotNull(tx.execute {
        require(manifestDigest.matches(Regex("[a-f0-9]{64}")) && mappingDigest.matches(Regex("[a-f0-9]{64}")))
        val row = repository.findByClientIdAndSnapshotId(clientId, snapshotId)
            ?: repository.saveAndFlush(QbDistributionIntent(id = operationId ?: UUID.randomUUID(), clientId = clientId, snapshotId = snapshotId,
                leaseId = leaseId, manifestDigest = manifestDigest, mappingDigest = mappingDigest))
        require((operationId == null || row.id == operationId) && row.leaseId == leaseId && row.manifestDigest == manifestDigest &&
            row.mappingDigest == mappingDigest) { "Distribution ownership cannot be rebound" }
        view(row)
    })
    fun get(id: UUID): QbDistributionRecord = requireNotNull(tx.execute { view(repository.findById(id).orElseThrow()) })
    /** Absence only. Persistence failures must never look like permission to create again. */
    fun find(id: UUID): QbDistributionRecord? = tx.execute { repository.findById(id).orElse(null)?.let(::view) }
    fun transition(id: UUID, from: Set<QbDistributionState>, to: QbDistributionState, taskId: String? = null,
                   infoHash: String? = null, metadata: ByteArray? = null): QbDistributionRecord = requireNotNull(tx.execute {
        val row = requireNotNull(repository.findLockedById(id))
        require(row.state in from) { "Distribution transition requires reconciliation" }
        taskId?.let { require(it.matches(Regex("[A-Za-z0-9-]{1,128}"))); require(row.taskId == null || row.taskId == it); row.taskId = it }
        infoHash?.let { require(it.matches(Regex("[a-f0-9]{40}"))); require(row.infoHash == null || row.infoHash == it); row.infoHash = it }
        metadata?.let { require(it.size in 1..1024 * 1024); require(row.metadata == null || row.metadata!!.contentEquals(it)) { "Validated metadata cannot be rebound" }; row.metadata = it.copyOf() }
        row.state = to; row.updatedAt = Instant.now(); view(repository.saveAndFlush(row))
    })
    private fun view(row: QbDistributionIntent) = QbDistributionRecord(row.id, row.clientId, row.snapshotId, row.leaseId,
        row.manifestDigest, row.mappingDigest, row.state, row.taskId, row.infoHash, row.metadata?.copyOf())
}
