package org.gameyfin.app.core.download.torrent

import org.gameyfin.app.core.plugins.PluginEndpointAccess
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.util.UUID
import java.time.Instant
import org.gameyfin.snapshot.SnapshotProtocol
import org.springframework.data.domain.PageRequest

internal class OwnedTorrentRuntime(val revision: Long, val config: OwnedTorrentConfiguration,
    val policy: QbDistributionPolicy, val credentials: QbDistributionCredentials)

@Service
internal class OwnedTorrentSettings(private val repository: OwnedTorrentSettingsRepository,
    private val requests: OwnedTorrentRequestRepository, private val mapper: ObjectMapper,
    private val access: PluginEndpointAccess, manager: PlatformTransactionManager,
    @Value("\${gameyfin.owned-torrent.core-parity-enabled:false}") private val parity: Boolean) {
    private val tx = TransactionTemplate(manager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
    fun view(): OwnedTorrentSettingsView {
        val actor = access.captureAdministrator()
        return requireNotNull(tx.execute {
            val row = repository.locked()
            access.requireCurrent(actor)
            OwnedTorrentSettingsView(row.revision.toString(), decode(row), row.username != null && row.password != null, parity)
                .also { access.requireCurrent(actor) }
        })
    }
    fun update(update: OwnedTorrentSettingsUpdate): OwnedTorrentSettingsView {
        val actor = access.captureAdministrator()
        tx.executeWithoutResult {
            val row = repository.locked()
            access.requireCurrent(actor)
            require(update.expectedRevision == row.revision.toString()) { "Settings changed; reload first" }
            val previous = decode(row)
            val sameProfile = previous.copy(enabled = update.configuration.enabled) == update.configuration
            val admissionOnly = sameProfile &&
                update.username == null && update.password == null && !update.clearCredentials
            val rotation = update.rotateRetainedCredentials && sameProfile && !update.clearCredentials &&
                update.username != null && update.password != null
            require(!update.rotateRetainedCredentials || rotation) { "Cleanup rotation requires credentials and identical immutable configuration" }
            if (!admissionOnly && !rotation) require(requests.countByStateNotIn(listOf("REJECTED", "RETIRED")) == 0L) {
                "Retire or reconcile requests before rebinding their profile; admission can be disabled independently"
            }
            require(row.revision < Long.MAX_VALUE)
            require(!(update.clearCredentials && (update.username != null || update.password != null)))
            require((update.username == null) == (update.password == null))
            if (update.clearCredentials) { row.username = null; row.password = null }
            if (update.username != null && update.password != null) {
                QbDistributionCredentials(update.username, update.password)
                row.username = update.username; row.password = update.password
            }
            // Validate the complete mapping even when disabled; an untouched default may remain disabled.
            if (update.configuration.enabled || update.configuration.apiOrigin.isNotEmpty()) {
                policy(update.configuration)
                OwnedSnapshotSettings(Path.of(update.configuration.hostCacheRoot), update.configuration.byteLimit,
                    update.configuration.fileLimit, update.configuration.reserveBytes)
            }
            if (update.configuration.enabled) {
                if (!previous.enabled) require(parity) { "Core parity has not been enabled by the operator" }
                require(row.username != null && row.password != null) { "Credentials required" }
                policy(update.configuration).requireAdmission()
            }
            if (rotation) {
                val candidates = requests.retained(PageRequest.of(0, 64))
                for (candidate in candidates) {
                    val request = requireNotNull(requests.locked(candidate.id))
                    val historical = mapper.readValue(request.profile, OwnedTorrentConfiguration::class.java)
                    // Never rebind a path, client UUID, policy or quota. Enabled is admission only.
                    if (historical.copy(enabled = update.configuration.enabled) != update.configuration) continue
                    require(policy(historical).mappingDigest == policy(update.configuration).mappingDigest)
                    access.requireCurrent(actor)
                    request.cleanupUsername = update.username; request.cleanupPassword = update.password
                    request.cleanupProfileDigest = SnapshotProtocol.digest(request.profile.toByteArray())
                    request.cleanupCredentialActorId = requireNotNull(actor.account).id
                    request.cleanupCredentialAt = Instant.now(); request.cleanupCredentialRevision = row.revision + 1
                    requests.saveAndFlush(request)
                }
            }
            row.configuration = mapper.writeValueAsString(update.configuration)
            access.requireCurrent(actor)
            row.revision++; repository.saveAndFlush(row)
        }
        return view()
    }
    private fun supportedHost(): Boolean = System.getProperty("os.name") == "Linux" &&
        System.getProperty("os.arch") == "amd64"
    fun available(): Boolean = parity && supportedHost() && requireNotNull(tx.execute {
        val row = repository.locked(); decode(row).enabled && row.username != null && row.password != null
    })
    fun runtime(expectedRevision: Long? = null): OwnedTorrentRuntime = requireNotNull(tx.execute {
        require(parity && supportedHost()) { "Owned torrent unavailable" }
        val row = repository.locked(); val config = decode(row)
        require(config.enabled && (expectedRevision == null || row.revision == expectedRevision)) { "Owned torrent configuration changed or disabled" }
        val policy = policy(config); policy.requireAdmission()
        OwnedTorrentRuntime(row.revision, config, policy,
            QbDistributionCredentials(requireNotNull(row.username), requireNotNull(row.password)))
    })
    private fun decode(row: OwnedTorrentSettingsRow) = mapper.readValue(row.configuration, OwnedTorrentConfiguration::class.java)
    /** Cleanup uses its immutable historical mapping, never a newly configured client. */
    fun historical(row: OwnedTorrentRequestRow): OwnedTorrentRuntime {
        val config = mapper.readValue(row.profile, OwnedTorrentConfiguration::class.java)
        val policy = policy(config); policy.requireAdmission()
        val override = row.cleanupUsername != null || row.cleanupPassword != null
        if (override) require(row.cleanupUsername != null && row.cleanupPassword != null &&
            row.cleanupProfileDigest == SnapshotProtocol.digest(row.profile.toByteArray()) &&
            row.cleanupCredentialActorId != null && row.cleanupCredentialAt != null && row.cleanupCredentialRevision != null) {
            "Cleanup credentials are not bound to this immutable profile"
        }
        return OwnedTorrentRuntime(row.settingsRevision, config, policy, QbDistributionCredentials(
            requireNotNull(if (override) row.cleanupUsername else row.profileUsername),
            requireNotNull(if (override) row.cleanupPassword else row.profilePassword)))
    }
    private fun policy(config: OwnedTorrentConfiguration): QbDistributionPolicy {
        require(config.webApiVersion == "2.15.1") { "Only reviewed qB 5.2.3 WebAPI 2.15.1 is supported" }
        require(config.trackers.size in 1..8 && config.trackers.size == config.trackers.toSet().size)
        return QbDistributionPolicy(UUID.fromString(config.clientId), config.apiOrigin,
            expectedWebApiVersion = config.webApiVersion, qbCacheRoot = config.qbCacheRoot,
            cacheIdentity = config.cacheIdentity, approvedTrackers = config.trackers.toSet(),
            dedicatedReadOnlyClientConfirmed = config.readOnlyMappingConfirmed,
            privatePeerEgressConfirmed = config.privatePeerEgressConfirmed)
    }
}
