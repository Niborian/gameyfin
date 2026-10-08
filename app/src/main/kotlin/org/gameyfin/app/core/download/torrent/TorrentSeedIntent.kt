package org.gameyfin.app.core.download.torrent

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

enum class TorrentSeedIntentState { DRAFT, CREATION_IN_FLIGHT, CREATION_UNCERTAIN, METADATA_PENDING_VALIDATION, REFUSED }

/** Host-owned creation journal. Metadata recorded here is not yet accepted for seeding. */
@Entity
@Table(uniqueConstraints = [UniqueConstraint(columnNames = ["client_id", "snapshot_id"])])
class TorrentSeedIntent(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "client_id", nullable = false) val clientId: UUID,
    @Column(name = "snapshot_id", nullable = false) val snapshotId: UUID,
    @Column(nullable = false, length = 64) val manifestDigest: String,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
) {
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false)
    var state: TorrentSeedIntentState = TorrentSeedIntentState.DRAFT
        internal set
    @Column var operationToken: UUID? = null
        internal set
    @Column(length = 128) var metadataTaskId: String? = null
        internal set
    @Column(length = 64) var metadataDigest: String? = null
        internal set
    @Column var metadataBytes: Long? = null
        internal set
    @Column(nullable = false) var updatedAt: Instant = createdAt
        internal set
    @Version var version: Long = 0
        internal set
}

interface TorrentSeedIntentRepository : JpaRepository<TorrentSeedIntent, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByClientIdAndSnapshotId(clientId: UUID, snapshotId: UUID): TorrentSeedIntent?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select intent from TorrentSeedIntent intent where intent.id = :id")
    fun findLocked(@Param("id") id: UUID): TorrentSeedIntent?
}
