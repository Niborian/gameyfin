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
class AcquisitionScopePolicy(private val config: ConfigService, private val settings: AcquisitionProviderSettings = AcquisitionProviderSettings()) {
    companion object {
        const val CATEGORY = "gameyfin-acquisition"
        const val MANAGED_TAG = "gameyfin-managed"
    }

    fun summary(): AcquisitionScopeDto = AcquisitionScopeDto(
        providerEnabled = false,
        approvedIndexerIds = approvedIndexerIds(),
        activeIndexerIds = emptyList(),
        category = category(),
        managedTag = managedTag(),
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
        return setOf(managedTag(), "${managedTag()}-request-$requestId", "${managedTag()}-candidate-$candidateId")
    }

    fun category() = scopeLabel(settings.category)
    fun managedTag() = scopeLabel(settings.managedTag)
    private fun scopeLabel(value: String) = value.also {
        require(it.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{2,63}"))) { "Dedicated scope must be a nonempty bounded category/tag label" }
    }

    fun requireOwnedTorrent(category: String, tags: Set<String>, requestId: Long, candidateId: Long) {
        require(category == category() && tags.containsAll(ownershipTags(requestId, candidateId))) {
            "Torrent category and all request/candidate ownership tags must match before a future cancel/retry action"
        }
    }
}

@Endpoint
@RolesAllowed(Role.Names.ADMIN)
class AcquisitionScopeEndpoint(private val policy: AcquisitionScopePolicy, private val settings: AcquisitionProviderSettings, private val provider: AcquisitionProvider) {
    fun summary(): AcquisitionScopeDto {
        val summary = policy.summary()
        if (!settings.enabled) return summary
        return summary.copy(providerEnabled = true, activeIndexerIds = provider.activeIndexers(),
            reason = "Dedicated acquisition provider enabled; only verified active approved indexers may be searched. Submission still requires explicit administrator authorization.")
    }
}
