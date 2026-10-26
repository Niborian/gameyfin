package org.gameyfin.app.core.download.torrent

import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/** Administrator-owned configuration, never an endpoint argument. Confirmation flags record
 * prerequisites, not proof that a remote container mount/firewall actually enforces them. */
internal class QbDistributionPolicy(
    val clientId: UUID,
    api: String,
    val expectedVersion: String = "v5.2.3",
    val expectedWebApiVersion: String,
    val qbCacheRoot: String,
    val cacheIdentity: String,
    approvedTrackers: Set<String>,
    private val dedicatedReadOnlyClientConfirmed: Boolean,
    private val privatePeerEgressConfirmed: Boolean,
    val category: String = "gameyfin-distribution",
) {
    val origin: URI
    val approvedTrackers: Set<String> = java.util.Collections.unmodifiableSet(LinkedHashSet(approvedTrackers))
    val mappingDigest: String
    init {
        origin = try { URI(api) } catch (_: Exception) { error("Invalid distribution API origin") }
        require(origin.scheme in setOf("http", "https") && origin.rawUserInfo == null && origin.rawQuery == null && origin.rawFragment == null)
        require(origin.path in setOf("", "/") && origin.port in 1..65535)
        val host = requireNotNull(origin.host)
        // Literal IPv4 only: no DNS rebinding, environment proxy or public API target.
        require(host.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")))
        require(host.split('.').all { it.toInt() in 0..255 && (it == "0" || !it.startsWith('0')) })
        val address = InetAddress.getByAddress(host.split('.').map { it.toInt().toByte() }.toByteArray())
        require(address.isSiteLocalAddress || address.isLoopbackAddress)
        require(address.isLoopbackAddress || origin.scheme == "https") { "Remote distribution API requires HTTPS" }
        require(expectedVersion == "v5.2.3" && expectedWebApiVersion.matches(Regex("2\\.[0-9]{1,3}\\.[0-9]{1,3}"))) { "Unreviewed qB protocol version" }
        // The remote mount is POSIX, regardless of the host running this adapter.
        require(qbCacheRoot.matches(Regex("/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+")) &&
            qbCacheRoot.split('/').drop(1).none { it == "." || it == ".." })
        require(cacheIdentity.isNotBlank() && cacheIdentity.length <= 512)
        require(category.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        TorrentNetworkPolicy(this.approvedTrackers, privateTrackerAdmissionConfirmed = true)
        mappingDigest = digest(listOf(clientId, origin, expectedVersion, expectedWebApiVersion, qbCacheRoot,
            cacheIdentity, category, this.approvedTrackers.sorted()).joinToString("\n").toByteArray())
    }
    fun requireAdmission() = require(dedicatedReadOnlyClientConfirmed && privatePeerEgressConfirmed) {
        "Dedicated read-only snapshot mapping and private peer egress require explicit verification"
    }
    fun source(snapshotId: UUID) = "$qbCacheRoot/$snapshotId/data"
    fun savePath(snapshotId: UUID) = "$qbCacheRoot/$snapshotId"
    fun comment(operation: UUID) = "gameyfin-owned-distribution:$operation"
    fun tag(operation: UUID) = "gameyfin-owned-$operation"
    override fun toString() = "QbDistributionPolicy(clientId=$clientId)"
    companion object {
        fun digest(bytes: ByteArray) = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}

/** Credentials are deliberately not a data class; no generated string representation. */
internal class QbDistributionCredentials(val username: String, val password: String) {
    init { require(username.length in 1..128 && password.length in 1..1024 && (username + password).none(Char::isISOControl)) }
    override fun toString() = "QbDistributionCredentials(redacted)"
}
