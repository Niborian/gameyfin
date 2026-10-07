package org.gameyfin.app.requests

import jakarta.persistence.*
import org.gameyfin.app.requests.entities.GameRequestCandidate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import java.time.Instant

@Entity
class AcquisitionTransfer(
    @Id @GeneratedValue(strategy = GenerationType.AUTO) var id: Long? = null,
    @OneToOne(optional = false) val candidate: GameRequestCandidate,
    @Column(nullable = false) val indexerId: String,
    @Column(nullable = false, unique = true) val torrentHash: String,
    @Column(nullable = false, length = 8192) val magnet: String,
    @Column(nullable = false) var state: String = "REVIEW",
    @Column(nullable = false) var updatedAt: Instant = Instant.now(),
    @Column(length = 36) var operationToken: String? = null,
    @Version var version: Long = 0,
)

@Entity
class AcquisitionTransferAudit(
    @Id @GeneratedValue(strategy = GenerationType.AUTO) var id: Long? = null,
    @ManyToOne(optional = false) val transfer: AcquisitionTransfer,
    @Column(nullable = false) val operation: String,
    @Column(nullable = false) val actor: String,
    @Column(nullable = false, length = 4096) val reason: String,
    @Column(nullable = false) val recordedAt: Instant = Instant.now(),
)

interface AcquisitionTransferRepository : JpaRepository<AcquisitionTransfer, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByCandidateId(candidateId: Long): AcquisitionTransfer?
    fun existsByTorrentHash(torrentHash: String): Boolean
    fun existsByCandidateGameRequestIdAndStateNot(requestId: Long, state: String): Boolean
    fun findAllByCandidateGameRequestId(requestId: Long): List<AcquisitionTransfer>
}
interface AcquisitionTransferAuditRepository : JpaRepository<AcquisitionTransferAudit, Long> {
    fun findAllByTransferCandidateIdOrderByRecordedAtAsc(candidateId: Long): List<AcquisitionTransferAudit>
}
