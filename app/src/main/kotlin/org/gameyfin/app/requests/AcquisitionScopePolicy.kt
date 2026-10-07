package org.gameyfin.app.requests

import com.vaadin.hilla.Endpoint
import jakarta.annotation.security.RolesAllowed
import org.gameyfin.app.config.ConfigProperties
import org.gameyfin.app.config.ConfigService
import org.gameyfin.app.core.Role
import org.springframework.stereotype.Service

data class AcquisitionScopeDto(
    val providerEnabled: Boolean,
    val approvedIndexerIds: List<String>,
    val activeIndexerIds: List<String>,
    val category: String,
    val managedTag: String,
    val reason: String
)

/** Scope guards for a future adapter; this service has no credentials, transport or provider action. */
@Service
class AcquisitionScopePolicy(private val config: ConfigService) {
    companion object {
        const val CATEGORY = "gameyfin-acquisition"
        const val MANAGED_TAG = "gameyfin-managed"
    }

    fun summary(): AcquisitionScopeDto = AcquisitionScopeDto(
        providerEnabled = false,
        approvedIndexerIds = approvedIndexerIds(),
        activeIndexerIds = emptyList(),
        category = CATEGORY,
        managedTag = MANAGED_TAG,
        reason = "External acquisition is unavailable. No indexers are active and no torrent client is connected."
    )

    fun approvedIndexerIds(): List<String> {
        val ids = config.get(ConfigProperties.Requests.Acquisition.ApprovedIndexerIds).orEmpty()
            .map { it.trim() }.distinct()
        require(ids.all { it.matches(Regex("[1-9][0-9]*")) && it.toLongOrNull() != null }) {
            "Approved indexers must be positive numeric IDs; no wildcard or all-indexer scope is allowed"
        }
        return ids.sortedBy { it.toLong() }
    }

    fun requireApprovedIndexer(indexerId: String) {
        require(indexerId in approvedIndexerIds()) { "Indexer is not administrator-approved" }
    }

    fun ownershipTags(requestId: Long, candidateId: Long): Set<String> {
        require(requestId > 0 && candidateId > 0) { "An owned torrent must reference a persisted request and candidate" }
        return setOf(MANAGED_TAG, "gameyfin-request-$requestId", "gameyfin-candidate-$candidateId")
    }

    fun requireOwnedTorrent(category: String, tags: Set<String>, requestId: Long, candidateId: Long) {
        require(category == CATEGORY && tags.containsAll(ownershipTags(requestId, candidateId))) {
            "Torrent category and all request/candidate ownership tags must match before a future cancel/retry action"
        }
    }
}

@Endpoint
@RolesAllowed(Role.Names.ADMIN)
class AcquisitionScopeEndpoint(private val policy: AcquisitionScopePolicy) {
    fun summary() = policy.summary()
}
