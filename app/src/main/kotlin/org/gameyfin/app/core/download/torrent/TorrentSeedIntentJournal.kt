package org.gameyfin.app.core.download.torrent

import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

data class TorrentSeedIntentView(
    val id: UUID, val clientId: UUID, val snapshotId: UUID, val manifestDigest: String,
    val state: TorrentSeedIntentState, val operationToken: UUID?, val metadataTaskId: String?,
    val metadataDigest: String?, val metadataBytes: Long?
)

/** Internal journal, not an endpoint or active provider. Every mutation commits before return.
 * It has no seeding/retry/delete methods: ambiguous creation requires later verified recovery,
 * and recorded metadata still requires exact-selection and piece validation before any add.
 */
@Service
class TorrentSeedIntentJournal(private val repository: TorrentSeedIntentRepository, manager: PlatformTransactionManager) {
    private val transaction = TransactionTemplate(manager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    fun reserve(clientId: UUID, snapshotId: UUID, manifestDigest: String): TorrentSeedIntentView {
        requireDigest(manifestDigest)
        return requireNotNull(transaction.execute {
            val existing = repository.findByClientIdAndSnapshotId(clientId, snapshotId)
            if (existing != null) {
                require(existing.manifestDigest == manifestDigest) { "Snapshot identity cannot be rebound to different content" }
                view(existing)
            } else view(repository.saveAndFlush(TorrentSeedIntent(clientId = clientId, snapshotId = snapshotId, manifestDigest = manifestDigest)))
        })
    }

    fun beginCreation(id: UUID): TorrentSeedIntentView = mutate(id) {
        require(it.state == TorrentSeedIntentState.DRAFT) { "Creation is not retryable; reconcile uncertain outcomes first" }
        it.operationToken = UUID.randomUUID()
        it.state = TorrentSeedIntentState.CREATION_IN_FLIGHT
    }

    fun recordMetadata(id: UUID, token: UUID, taskId: String, metadataDigest: String, metadataBytes: Long): TorrentSeedIntentView {
        require(taskId.matches(Regex("[A-Za-z0-9-]{1,128}"))) { "Invalid metadata task identity" }
        requireDigest(metadataDigest)
        require(metadataBytes in 1..1024 * 1024) { "Metadata exceeds the bounded validation envelope" }
        return mutate(id) {
            requireCompletion(it, token)
            it.metadataTaskId = taskId
            it.metadataDigest = metadataDigest
            it.metadataBytes = metadataBytes
            it.state = TorrentSeedIntentState.METADATA_PENDING_VALIDATION
        }
    }

    fun markUncertain(id: UUID, token: UUID): TorrentSeedIntentView = mutate(id) {
        requireCompletion(it, token)
        it.state = TorrentSeedIntentState.CREATION_UNCERTAIN
    }

    fun refuseBeforeCreation(id: UUID): TorrentSeedIntentView = mutate(id) {
        require(it.state == TorrentSeedIntentState.DRAFT) { "A refusal cannot erase an external operation" }
        it.state = TorrentSeedIntentState.REFUSED
    }

    private fun requireCompletion(intent: TorrentSeedIntent, token: UUID) {
        require(intent.state == TorrentSeedIntentState.CREATION_IN_FLIGHT && intent.operationToken == token) {
            "Stale or uncertain completion must be reconciled, not accepted"
        }
    }

    private fun mutate(id: UUID, update: (TorrentSeedIntent) -> Unit): TorrentSeedIntentView = requireNotNull(transaction.execute {
        val intent = repository.findLocked(id) ?: error("No host-owned seed intent")
        update(intent)
        intent.updatedAt = Instant.now()
        view(repository.saveAndFlush(intent))
    })

    private fun requireDigest(value: String) = require(value.matches(Regex("[0-9a-f]{64}"))) { "Invalid artifact digest" }
    private fun view(intent: TorrentSeedIntent) = TorrentSeedIntentView(intent.id, intent.clientId, intent.snapshotId, intent.manifestDigest,
        intent.state, intent.operationToken, intent.metadataTaskId, intent.metadataDigest, intent.metadataBytes)
}
