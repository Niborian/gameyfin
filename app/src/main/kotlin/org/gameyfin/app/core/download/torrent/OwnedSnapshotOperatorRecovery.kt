package org.gameyfin.app.core.download.torrent

import jakarta.persistence.*
import org.gameyfin.app.core.security.getCurrentAuth
import org.gameyfin.app.core.security.isAdmin
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

internal enum class SnapshotRecoveryTarget { ACQUISITION, SESSION, CONSUMER }

/** Deliberate operator attestation, NOT an automatic local-PID absence inference.
 * The receipt contains the SHA-256 of independently retained container-exit evidence,
 * never credentials or an arbitrary filesystem path. It cannot release a qB lease.
 */
internal data class SnapshotExitAttestation(val receiptId: UUID, val snapshotId: UUID,
    val operation: UUID, val target: SnapshotRecoveryTarget, val leaseId: UUID?, val consumerId: UUID?,
    val expected: SnapshotProcessIdentity, val evidenceDigest: String,
    val confirmsOriginalContainerAndAllItsWorkersExited: Boolean)

@Entity
internal class OwnedSnapshotRecoveryReceipt(
    @Id val id: UUID,
    @Column(nullable = false) val snapshotId: UUID,
    @Column(nullable = false) val operation: UUID,
    @Column(nullable = false, length = 16) val target: String,
    val leaseId: UUID?, val consumerId: UUID?,
    @Column(nullable = false, length = 64) val expectedScope: String,
    @Column(nullable = false) val expectedPid: Long,
    @Column(nullable = false) val expectedStartedMillis: Long,
    @Column(nullable = false, length = 64) val evidenceDigest: String,
    @Column(nullable = false, length = 255) val administrator: String,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
)

/** Internal admin-only recovery boundary; callers must present the exact old tuple.
 * No sweep, arbitrary path, foreign torrent removal, or release-all operation exists.
 * A separate authenticated UI/endpoint may expose this only with the explicit warning.
 */
@Service
internal class OwnedSnapshotOperatorRecovery(private val snapshots: OwnedSnapshotRepository,
    private val leases: OwnedSnapshotLeaseRepository, private val consumers: OwnedSnapshotConsumerRepository,
    private val entityManager: EntityManager) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun attestExited(attestation: SnapshotExitAttestation) {
        val auth = getCurrentAuth()
        if (auth == null || !auth.isAuthenticated || !auth.isAdmin()) throw AccessDeniedException("Administrator required")
        require(auth.name.isNotBlank() && auth.name.length <= 255)
        require(attestation.confirmsOriginalContainerAndAllItsWorkersExited) { "Explicit original-container exit proof required" }
        require(attestation.evidenceDigest.matches(Regex("[0-9a-f]{64}")))
        val currentScope = SnapshotProcessProbe.SYSTEM.scope()
        if (currentScope == attestation.expected.scope) {
            require(SnapshotProcessProbe.SYSTEM.exitProved(attestation.expected)) { "Local process exit remains unproven" }
        }
        require(entityManager.find(OwnedSnapshotRecoveryReceipt::class.java, attestation.receiptId) == null) { "Receipt already consumed" }
        val snapshot = requireNotNull(snapshots.locked(attestation.snapshotId))
        require(snapshot.operation == attestation.operation && snapshot.state != OwnedSnapshotState.RETIRED)
        val now = Instant.now()
        when (attestation.target) {
            SnapshotRecoveryTarget.ACQUISITION -> {
                require(attestation.leaseId == null && attestation.consumerId == null)
                require((identity(snapshot.workerScope, snapshot.workerPid, snapshot.workerStartedMillis)
                    ?: identity(snapshot.creatorScope, snapshot.creatorPid, snapshot.creatorStartedMillis)) == attestation.expected)
                snapshot.workerExitProvedAt = now
            }
            SnapshotRecoveryTarget.SESSION -> {
                require(attestation.consumerId == null)
                val lease = requireNotNull(leases.locked(requireNotNull(attestation.leaseId)))
                require(lease.snapshot.id == snapshot.id && lease.releasedAt == null && lease.clientId == null)
                require(consumers.countByLeaseIdAndReleasedAtIsNull(lease.id) == 0L)
                require((identity(lease.workerScope, lease.workerPid, lease.workerStartedMillis)
                    ?: identity(lease.creatorScope, lease.creatorPid, lease.creatorStartedMillis)) == attestation.expected)
                lease.releasedAt = now
            }
            SnapshotRecoveryTarget.CONSUMER -> {
                val lease = requireNotNull(leases.locked(requireNotNull(attestation.leaseId)))
                require(lease.snapshot.id == snapshot.id && lease.releasedAt == null && lease.clientId != null)
                val consumer = requireNotNull(consumers.locked(requireNotNull(attestation.consumerId)))
                require(consumer.lease.id == lease.id && consumer.releasedAt == null)
                require((identity(consumer.workerScope, consumer.workerPid, consumer.workerStartedMillis)
                    ?: identity(consumer.creatorScope, consumer.creatorPid, consumer.creatorStartedMillis)) == attestation.expected)
                consumer.releasedAt = now
                // The external client lease remains until stopped/removal/task absence.
            }
        }
        entityManager.persist(OwnedSnapshotRecoveryReceipt(attestation.receiptId, snapshot.id, snapshot.operation,
            attestation.target.name, attestation.leaseId, attestation.consumerId, attestation.expected.scope,
            attestation.expected.pid, attestation.expected.startedMillis, attestation.evidenceDigest, auth.name))
        entityManager.flush()
    }
    private fun identity(scope: String?, pid: Long?, started: Long?): SnapshotProcessIdentity? =
        if (scope != null && pid != null && started != null) SnapshotProcessIdentity(scope, pid, started) else null
}
