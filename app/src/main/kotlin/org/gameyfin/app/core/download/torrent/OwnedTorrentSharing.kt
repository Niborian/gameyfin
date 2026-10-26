package org.gameyfin.app.core.download.torrent

import jakarta.persistence.*
import org.gameyfin.snapshot.SnapshotProtocol
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Service
import java.util.UUID
import java.time.Instant
import tools.jackson.databind.ObjectMapper

internal fun ownedTorrentSharedProfileDigest(mapper: ObjectMapper, config: OwnedTorrentConfiguration) =
    SnapshotProtocol.digest(mapper.writeValueAsBytes(config.copy(enabled = false, trackers = config.trackers.sorted())))

internal fun ownedTorrentSharedSelectionKey(selection: OwnedSnapshotLogicalSelection, profileDigest: String): String =
    SnapshotProtocol.digest(listOf("owned-shared-selection-v1", selection.gameId, selection.variantId,
        selection.contentIds.joinToString(","), selection.selections.joinToString("\u0000") { it.generatedRelative },
        profileDigest).joinToString("\u0000").toByteArray())

/** Lexical DB membership only; no filesystem observation or payload authorization. */
internal fun ownedTorrentSharedMembershipDigest(selection: OwnedSnapshotLogicalSelection): String =
    SnapshotProtocol.digest(listOf("owned-shared-membership-v1", selection.gameId, selection.variantId,
        selection.contentIds.joinToString(","), selection.sourceRoot.toString(),
        selection.selections.joinToString("\u0000") { "${it.sourceRelative}\u0000${it.generatedRelative}" })
        .joinToString("\u0000").toByteArray())

internal fun ownedTorrentSharedVisibleState(state: String, requestActive: Boolean, ownerActive: Boolean): String =
    if (state in setOf("QUEUED", "RUNNING", "RETIRING", "RECOVERING") && !requestActive && !ownerActive)
        "NEEDS_OPERATOR" else state

@Entity
@Table(name = "owned_torrent_shared_owner")
internal class OwnedTorrentSharedOwner(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(nullable = false, updatable = false, length = 64) var selectionKey: String = "",
    @Column(nullable = false, updatable = false, length = 64) var profileDigest: String = "",
    @Column(nullable = false, length = 24) var state: String = "PREPARING",
    var snapshotId: UUID? = null,
    var intentId: UUID? = null,
    var clientId: UUID? = null,
    var leaseId: UUID? = null,
    @Column(length = 64) var manifestDigest: String? = null,
    @Column(length = 64) var mappingDigest: String? = null,
    @Column(length = 40) var infoHash: String? = null,
    @Column(length = 64) var metadataDigest: String? = null,
)

@Entity
@Table(name = "owned_torrent_shared_reference")
internal class OwnedTorrentSharedReference(
    @Id var requestId: UUID = UUID.randomUUID(),
    @Column(nullable = false, updatable = false) var ownerId: UUID = UUID.randomUUID(),
    @Column(nullable = false, length = 16) var state: String = "ACTIVE",
    @Column(nullable = false, updatable = false, length = 64) var verificationDigest: String = "",
    @Column(nullable = false, updatable = false) var verifiedAt: Instant = Instant.now(),
    @Column(nullable = false, updatable = false, length = 64) var sourceDigest: String = "",
    @Column(nullable = false, updatable = false, length = 40) var verificationKind: String = "ORIGINAL_CAPTURE",
    @Column(nullable = false, updatable = false, length = 64) var membershipDigest: String = "",
)

internal interface OwnedTorrentSharedOwnerRepository : JpaRepository<OwnedTorrentSharedOwner, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OwnedTorrentSharedOwner o where o.id = :id")
    fun locked(id: UUID): OwnedTorrentSharedOwner?
    @Query("select o from OwnedTorrentSharedOwner o where o.selectionKey = :key and o.state <> 'RETIRED' order by o.id")
    fun candidates(key: String, page: Pageable): List<OwnedTorrentSharedOwner>
}

internal interface OwnedTorrentSharedReferenceRepository : JpaRepository<OwnedTorrentSharedReference, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from OwnedTorrentSharedReference r where r.requestId = :id")
    fun locked(id: UUID): OwnedTorrentSharedReference?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from OwnedTorrentSharedReference r where r.ownerId = :id order by r.requestId")
    fun lockedForOwner(id: UUID): List<OwnedTorrentSharedReference>
}

internal data class OwnedTorrentSharedCandidate(val ownerId: UUID, val selectionKey: String,
    val profileDigest: String, val proof: QbDistributionSharedProof)
internal data class OwnedTorrentSharedRelease(val ownerId: UUID, val last: Boolean)

/** No filesystem/network calls or nested transactions inside any writer callback. */
@Service
internal class OwnedTorrentSharing(private val settings: OwnedTorrentSettings,
    private val owners: OwnedTorrentSharedOwnerRepository,
    private val references: OwnedTorrentSharedReferenceRepository,
    private val requests: OwnedTorrentRequestRepository,
    private val intents: QbDistributionIntentRepository) {

    fun candidates(key: String): List<OwnedTorrentSharedCandidate> = settings.withWriterLock(null) {
        val rows = owners.candidates(key, PageRequest.of(0, 17))
        require(rows.size <= 16) { "Shared owner bound exceeded" }
        require(rows.none { it.state != "ACTIVE" }) { "Matching owner requires completion or reconciliation" }
        rows.filter { it.state == "ACTIVE" }.map(::candidate)
    }

    fun reference(id: UUID): OwnedTorrentSharedReference? = references.findById(id).orElse(null)
    fun owner(id: UUID): OwnedTorrentSharedCandidate? = owners.findById(id).orElse(null)?.let {
        if (it.state == "ACTIVE") candidate(it) else null
    }

    fun begin(id: UUID, key: String, profileDigest: String, revision: Long,
              mismatchedOwners: Set<UUID>, authorize: () -> Unit) {
        settings.withWriterLock(revision) {
            val existing = owners.candidates(key, PageRequest.of(0, 17))
            require(existing.size <= 16 && existing.all { it.state == "ACTIVE" && it.id in mismatchedOwners }) {
                "Shared owner changed after source verification"
            }
            require(!owners.existsById(id) && !references.existsById(id))
            val request = requireNotNull(requests.locked(id))
            require(request.state == "RUNNING" && request.snapshotId == null && request.distributionId == null)
            authorize()
            owners.saveAndFlush(OwnedTorrentSharedOwner(id, key, profileDigest))
        }
    }

    fun publish(id: UUID, proof: QbDistributionSharedProof, membershipDigest: String, revision: Long, authorize: () -> Unit) {
        require(membershipDigest.matches(Regex("[a-f0-9]{64}")))
        settings.withWriterLock(revision) {
            val owner = requireNotNull(owners.locked(id))
            require(owner.state == "PREPARING" && proof.intentId == id && proof.snapshotId == id)
            val reference = references.locked(id); require(reference == null)
            val request = requireNotNull(requests.locked(id))
            require(request.snapshotId == proof.snapshotId && request.distributionId == proof.intentId)
            requireIntent(proof)
            authorize()
            owner.snapshotId = proof.snapshotId; owner.intentId = proof.intentId
            owner.clientId = proof.clientId; owner.leaseId = proof.leaseId
            owner.manifestDigest = proof.manifestDigest; owner.mappingDigest = proof.mappingDigest
            owner.infoHash = proof.infoHash; owner.metadataDigest = proof.metadataDigest
            owner.state = "ACTIVE"
            references.saveAndFlush(OwnedTorrentSharedReference(id, id,
                verificationDigest = verificationDigest(proof, proof.manifestDigest, "ORIGINAL_CAPTURE", membershipDigest),
                sourceDigest = proof.manifestDigest, membershipDigest = membershipDigest))
            request.state = "READY"; requests.saveAndFlush(request); owners.saveAndFlush(owner)
        }
    }

    /** Caller already proved current sources/cache/seed outside locks; tuple is rechecked here. */
    fun attach(id: UUID, expected: OwnedTorrentSharedCandidate, proof: QbDistributionSharedProof,
               sourceDigest: String, verificationKind: String, membershipDigest: String, revision: Long, authorize: () -> Unit) {
        require(sourceDigest.matches(Regex("[a-f0-9]{64}")) && verificationKind in setOf("MATCH", "SAME_CONTENT_DIFFERENT_SOURCE"))
        require(membershipDigest.matches(Regex("[a-f0-9]{64}")))
        settings.withWriterLock(revision) {
            val owner = requireNotNull(owners.locked(expected.ownerId))
            require(owner.state == "ACTIVE" && candidate(owner) == expected && proof == expected.proof)
            val reference = references.locked(id); require(reference == null)
            val request = requireNotNull(requests.locked(id))
            require(request.state == "RUNNING" && request.snapshotId == null && request.distributionId == null)
            requireIntent(proof)
            authorize()
            references.saveAndFlush(OwnedTorrentSharedReference(id, owner.id,
                verificationDigest = verificationDigest(proof, sourceDigest, verificationKind, membershipDigest),
                sourceDigest = sourceDigest, verificationKind = verificationKind, membershipDigest = membershipDigest))
            request.state = "READY"; requests.saveAndFlush(request)
        }
    }

    fun metadata(id: UUID, revision: Long, authorize: (String) -> Unit): ByteArray = settings.withWriterLock(revision) {
        val observed = requireNotNull(reference(id))
        val owner = requireNotNull(owners.locked(observed.ownerId))
        val reference = requireNotNull(references.locked(id))
        val request = requireNotNull(requests.locked(id))
        require(owner.state == "ACTIVE" && reference.ownerId == owner.id && reference.state == "ACTIVE" && request.state == "READY")
        val proof = candidate(owner).proof
        val intent = requireIntent(proof)
        authorize(reference.membershipDigest)
        requireNotNull(intent.metadata).copyOf()
    }

    /** Fences last release before any stop/remove; attach must observe ACTIVE under the same lock. */
    fun release(id: UUID, authorize: () -> Unit): OwnedTorrentSharedRelease? = settings.withWriterLock(null) {
        val observed = reference(id) ?: return@withWriterLock nullRelease()
        val owner = requireNotNull(owners.locked(observed.ownerId))
        val refs = references.lockedForOwner(owner.id)
        val ref = requireNotNull(refs.find { it.requestId == id })
        val request = requireNotNull(requests.locked(id))
        require(owner.state in setOf("ACTIVE", "RETIRING", "UNCERTAIN"))
        authorize()
        ref.state = "RELEASED"; references.saveAndFlush(ref)
        val last = refs.none { it.state == "ACTIVE" }
        request.state = if (last) "RETIRING" else "RELEASED"; requests.saveAndFlush(request)
        if (last) { owner.state = "RETIRING"; owners.saveAndFlush(owner) }
        OwnedTorrentSharedRelease(owner.id, last)
    }.takeUnless { it.ownerId == ZERO }

    fun retired(ownerId: UUID, authorize: () -> Unit) {
        settings.withWriterLock(null) {
            val owner = owners.locked(ownerId) ?: return@withWriterLock Unit
            require(owner.state in setOf("PREPARING", "RETIRING", "UNCERTAIN"))
            val refs = references.lockedForOwner(ownerId)
            require(refs.none { it.state == "ACTIVE" })
            for (ref in refs.sortedBy { it.requestId }) {
                val request = requireNotNull(requests.locked(ref.requestId))
                authorize(); request.state = "RETIRED"; requests.saveAndFlush(request)
            }
            if (refs.none { it.requestId == ownerId }) {
                val request = requireNotNull(requests.locked(ownerId))
                authorize(); request.state = "RETIRED"; requests.saveAndFlush(request)
            }
            authorize(); owner.state = "RETIRED"; owners.saveAndFlush(owner)
        }
    }

    fun uncertain(ownerId: UUID) = settings.withWriterLock(null) {
        owners.locked(ownerId)?.let { if (it.state != "RETIRED") { it.state = "UNCERTAIN"; owners.saveAndFlush(it) } }
        Unit
    }

    /** Caller proves exact absence (and creator exit for operator cancellation) under these
     * locks. Rejection and clearing its PREPARING fence commit together, never in two steps. */
    fun rejectResourceFree(id: UUID, verify: (OwnedTorrentRequestRow) -> Unit) {
        settings.withWriterLock(null) {
            val owner = owners.locked(id)
            val refs = if (owner == null) emptyList() else references.lockedForOwner(id)
            require(references.locked(id) == null && refs.isEmpty()) { "Shared references require shared release" }
            val request = requireNotNull(requests.locked(id))
            require(request.state in setOf("QUEUED", "RUNNING", "UNCERTAIN") &&
                request.snapshotId == null && request.distributionId == null) { "Request requires resource reconciliation" }
            require(owner == null || owner.state in setOf("PREPARING", "UNCERTAIN", "RETIRED"))
            require(owner == null || (owner.snapshotId == null && owner.intentId == null && owner.leaseId == null &&
                owner.clientId == null && owner.manifestDigest == null && owner.mappingDigest == null &&
                owner.infoHash == null && owner.metadataDigest == null)) { "Shared owner still carries resource identity" }
            verify(request) // Any absence/creator/auth/audit failure rolls back the whole writer.
            if (owner != null) { owner.state = "RETIRED"; owners.saveAndFlush(owner) }
            request.state = "REJECTED"; requests.saveAndFlush(request)
        }
    }

    /** Records uncertainty only; never releases a reference, lease or resource. */
    fun acquisitionFailed(id: UUID, snapshotId: UUID?) {
        settings.withWriterLock(null) {
            val owner = owners.locked(id)
            if (owner != null) references.lockedForOwner(id)
            references.locked(id)
            val request = requireNotNull(requests.locked(id))
            if (request.state == "REJECTED" && (owner == null || owner.state == "RETIRED")) return@withWriterLock Unit
            snapshotId?.let {
                require(it == request.plannedSnapshotId && (request.snapshotId == null || request.snapshotId == it))
                request.snapshotId = it
            }
            request.state = "UNCERTAIN"; requests.saveAndFlush(request)
            if (owner != null && owner.state != "RETIRED") { owner.state = "UNCERTAIN"; owners.saveAndFlush(owner) }
        }
    }

    private fun requireIntent(proof: QbDistributionSharedProof): QbDistributionIntent {
        val intent = requireNotNull(intents.findLockedById(proof.intentId))
        require(intent.state == QbDistributionState.SEEDING && intent.clientId == proof.clientId &&
            intent.snapshotId == proof.snapshotId && intent.leaseId == proof.leaseId &&
            intent.manifestDigest == proof.manifestDigest && intent.mappingDigest == proof.mappingDigest &&
            intent.infoHash == proof.infoHash && intent.metadata?.let(SnapshotProtocol::digest) == proof.metadataDigest)
        return intent
    }

    private fun candidate(row: OwnedTorrentSharedOwner) = OwnedTorrentSharedCandidate(row.id, row.selectionKey,
        row.profileDigest, QbDistributionSharedProof(requireNotNull(row.intentId), requireNotNull(row.clientId),
            requireNotNull(row.snapshotId), requireNotNull(row.leaseId), requireNotNull(row.manifestDigest),
            requireNotNull(row.mappingDigest), requireNotNull(row.infoHash), requireNotNull(row.metadataDigest)))
    private fun nullRelease() = OwnedTorrentSharedRelease(ZERO, false)
    // Provenance of the completed source/cache/seed checks, NEVER a reusable authorization token.
    private fun verificationDigest(proof: QbDistributionSharedProof, sourceDigest: String, kind: String, membershipDigest: String) = SnapshotProtocol.digest(
        listOf("source-cache-seed-checked-v1", proof.intentId, proof.clientId, proof.snapshotId, proof.leaseId,
            proof.manifestDigest, proof.mappingDigest, proof.infoHash, proof.metadataDigest, sourceDigest, kind, membershipDigest).joinToString("\u0000").toByteArray())
    private companion object { val ZERO = UUID(0, 0) }
}
