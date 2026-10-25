package org.gameyfin.app.core.download.torrent

import jakarta.persistence.*
import org.gameyfin.app.core.security.EncryptionConverter
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.domain.Pageable
import java.util.UUID
import java.time.Instant

@Entity
@Table(name = "owned_torrent_settings")
class OwnedTorrentSettingsRow(
    @Id var id: Int = 1,
    @Lob var configuration: String = "{}",
    @Convert(converter = EncryptionConverter::class) @Column(length = 8192) var username: String? = null,
    @Convert(converter = EncryptionConverter::class) @Column(length = 8192) var password: String? = null,
    var revision: Long = 0,
)
interface OwnedTorrentSettingsRepository : JpaRepository<OwnedTorrentSettingsRow, Int> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from OwnedTorrentSettingsRow s where s.id = 1")
    fun locked(): OwnedTorrentSettingsRow
}

@Entity
@Table(name = "owned_torrent_request")
class OwnedTorrentRequestRow(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(length = 64, nullable = false) var ownerKey: String = "",
    @Lob var selection: String = "",
    var settingsRevision: Long = 0,
    @Column(length = 24) var state: String = "QUEUED",
    var distributionId: UUID? = null,
    var snapshotId: UUID? = null,
    @Lob var profile: String = "{}",
    @Convert(converter = EncryptionConverter::class) @Column(length = 8192) var profileUsername: String? = null,
    @Convert(converter = EncryptionConverter::class) @Column(length = 8192) var profilePassword: String? = null,
    @Convert(converter = EncryptionConverter::class) @Column(length = 8192) var cleanupUsername: String? = null,
    @Convert(converter = EncryptionConverter::class) @Column(length = 8192) var cleanupPassword: String? = null,
    @Column(length = 64) var cleanupProfileDigest: String? = null,
    var cleanupCredentialActorId: Long? = null,
    var cleanupCredentialAt: Instant? = null,
    var cleanupCredentialRevision: Long? = null,
)
interface OwnedTorrentRequestRepository : JpaRepository<OwnedTorrentRequestRow, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from OwnedTorrentRequestRow r where r.id = :id")
    fun locked(id: UUID): OwnedTorrentRequestRow?
    fun countByStateNotIn(states: Collection<String>): Long
    fun countBySettingsRevisionNotAndStateNotIn(revision: Long, states: Collection<String>): Long
    @Query("select r.id as id, r.state as state from OwnedTorrentRequestRow r where r.state <> 'RETIRED' and r.state <> 'REJECTED' order by r.id")
    fun retained(page: Pageable): List<OwnedTorrentRetainedRow>
}

interface OwnedTorrentRetainedRow { val id: UUID; val state: String }
