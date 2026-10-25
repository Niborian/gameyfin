package org.gameyfin.app.core.download.torrent

/** No credentials, local paths, manifest paths or remote API replies are returned to downloaders. */
data class OwnedTorrentSelection(val gameId: Long, val variantId: Long?, val contentIds: List<Long>?, val explicitSelection: Boolean = false)
data class OwnedTorrentStatus(val id: String, val state: String, val downloadUrl: String?)
enum class OwnedTorrentRecoveryAction { RECONCILE_CREATION, VALIDATE_CREATION, RECONCILE_ADD }

/** Administrator configuration only. Admission confirmations are not deployment/parity evidence. */
data class OwnedTorrentConfiguration(
    val enabled: Boolean = false,
    val clientId: String = "",
    val apiOrigin: String = "",
    val webApiVersion: String = "2.15.1",
    val hostCacheRoot: String = "",
    val qbCacheRoot: String = "",
    val cacheIdentity: String = "",
    val trackers: List<String> = emptyList(),
    val readOnlyMappingConfirmed: Boolean = false,
    val privatePeerEgressConfirmed: Boolean = false,
    val byteLimit: Long = 10737418240,
    val fileLimit: Int = 4096,
    val reserveBytes: Long = 1073741824,
)
data class OwnedTorrentSettingsView(val revision: String, val configuration: OwnedTorrentConfiguration,
    val credentialsConfigured: Boolean, val coreParityEnabled: Boolean)

/** Deliberately not a data class: generated toString must never print a password. */
class OwnedTorrentSettingsUpdate(
    val expectedRevision: String,
    val configuration: OwnedTorrentConfiguration,
    val username: String? = null,
    val password: String? = null,
    val clearCredentials: Boolean = false,
    val rotateRetainedCredentials: Boolean = false,
) {
    override fun toString() = "OwnedTorrentSettingsUpdate(redacted)"
}
