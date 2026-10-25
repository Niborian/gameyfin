package org.gameyfin.app.core.download.torrent

import jakarta.annotation.PreDestroy
import org.gameyfin.app.core.download.files.DownloadPathLeases
import org.gameyfin.app.core.download.files.DownloadSelectionResolver
import org.gameyfin.app.core.plugins.PluginAudience
import org.gameyfin.app.core.plugins.PluginEndpointAccess
import org.gameyfin.app.games.GameService
import org.gameyfin.snapshot.SnapshotProtocol
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One bounded task queue, not the unbounded legacy download executor. No worker auth lookup. */
@Service
internal class OwnedTorrentFacade(private val settings: OwnedTorrentSettings,
    private val requests: OwnedTorrentRequestRepository, private val journal: QbDistributionJournal,
    private val snapshots: OwnedSnapshotRegistry, private val resolver: DownloadSelectionResolver,
    private val paths: DownloadPathLeases, private val games: GameService,
    private val access: PluginEndpointAccess, private val mapper: ObjectMapper, manager: PlatformTransactionManager) {
    private val tx = TransactionTemplate(manager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(2),
        { task -> Thread(task, "owned-torrent").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val active = ConcurrentHashMap.newKeySet<UUID>()
    private var acquisitionInstance: Pair<String, OwnedSnapshotAcquisitionService>? = null
    fun available(): Boolean = settings.available()

    @Synchronized fun create(selection: OwnedTorrentSelection): OwnedTorrentStatus {
        val actor = capture(); val runtime = settings.runtime()
        require(selection.gameId > 0 && (selection.variantId == null || selection.variantId > 0))
        require(selection.contentIds == null || (selection.contentIds.size <= 4096 && selection.contentIds.all { it > 0 }))
        // Resolve before queue admission, and again in acquisition immediately before source leasing.
        val resolved = resolver.resolveSelection(games.getById(selection.gameId), selection.variantId, selected(selection))
        requireSession(actor)
        val exact = selection.copy(variantId = requireNotNull(resolved.first.id),
            contentIds = resolved.second.mapNotNull { it.id }.distinct().sorted(), explicitSelection = true)
        val id = UUID.randomUUID()
        val creator = SnapshotProcessIdentity.current()
        tx.executeWithoutResult {
            requireSession(actor)
            require(ownedTorrentAdmissionOpen(requests.countByStateNotIn(listOf("REJECTED", "RETIRED")))) { "Owned torrent retention capacity reached" }
            requests.saveAndFlush(OwnedTorrentRequestRow(id, owner(actor), mapper.writeValueAsString(exact), runtime.revision).apply {
                profile = mapper.writeValueAsString(runtime.config)
                profileUsername = runtime.credentials.username; profilePassword = runtime.credentials.password
                plannedSnapshotId = id
                creatorScope = creator.scope; creatorPid = creator.pid; creatorStartedMillis = creator.startedMillis
                hostRequestDigest = ownedTorrentHostRequestDigest(this)
            })
        }
        submit(id, onRejected = "REJECTED") { acquire(id, actor) }
        return status(id)
    }
    fun status(id: UUID): OwnedTorrentStatus {
        val actor = capture(); val row = row(id); requireOwner(actor, row)
        return statusOf(row).also { requireSession(actor) }
    }
    fun inspect(id: UUID): OwnedTorrentStatus {
        val actor = access.captureAdministrator(); requireSession(actor)
        return statusOf(row(id)).also { requireSession(actor) }
    }
    fun retained(): List<OwnedTorrentStatus> {
        val actor = access.captureAdministrator(); requireSession(actor)
        val rows = requireNotNull(tx.execute { requests.retained(PageRequest.of(0, 64)) })
        requireSession(actor)
        return rows.map { OwnedTorrentStatus(it.id.toString(), visibleState(it.id, it.state), null) }
            .also { requireSession(actor) }
    }
    fun creatorIdentity(id: UUID): OwnedTorrentCreatorIdentity {
        val actor = access.captureAdministrator(); requireSession(actor)
        val row = row(id); requireSession(actor)
        return OwnedTorrentCreatorIdentity(id.toString(), row.creatorScope, row.creatorPid, row.creatorStartedMillis)
    }
    /** This retires NO resource: both exact planned resources must be authoritatively absent. */
    fun cancelResourceFree(id: UUID, attestation: OwnedTorrentRequestExitAttestation?): OwnedTorrentStatus {
        val actor = access.captureAdministrator(); requireSession(actor)
        check(active.add(id)) { "Request still has a local owner" }
        try {
            tx.executeWithoutResult {
                val row = requireNotNull(requests.locked(id))
                // Null comes only from successful exact lookups; exceptions roll back and retain ownership.
                val proof = ownedTorrentResourceFreeCancellation(row, SnapshotProcessProbe.SYSTEM, attestation,
                    { snapshots.find(row.plannedSnapshotId) }, { journal.find(id) })
                requireSession(actor)
                if (proof == OwnedTorrentCreatorExit.FOREIGN_ATTESTED) {
                    val receipt = requireNotNull(attestation)
                    require(row.creatorExitEvidenceSha256 == null || row.creatorExitEvidenceSha256 == receipt.evidenceSha256) { "Exit audit cannot be rebound" }
                    row.creatorExitExpectedScope = row.creatorScope; row.creatorExitExpectedPid = row.creatorPid
                    row.creatorExitExpectedStartedMillis = row.creatorStartedMillis
                    row.creatorExitEvidenceSha256 = receipt.evidenceSha256; row.creatorExitContainerId = receipt.originalContainerId
                    row.creatorExitActorId = requireNotNull(actor.account).id; row.creatorExitAt = Instant.now()
                }
                row.state = "REJECTED"; requests.saveAndFlush(row)
            }
        } finally { active.remove(id) }
        return OwnedTorrentStatus(id.toString(), "REJECTED", null)
    }
    private fun statusOf(row: OwnedTorrentRequestRow): OwnedTorrentStatus {
        val id = row.id
        val state = visibleState(id, row.state)
        return OwnedTorrentStatus(id.toString(), state, if (state == "READY") "/owned-torrent/$id/metadata" else null)
    }
    private fun visibleState(id: UUID, state: String) =
        if (state in setOf("QUEUED", "RUNNING", "RETIRING", "RECOVERING") && id !in active) "NEEDS_OPERATOR" else state
    fun metadata(id: UUID): ByteArray {
        val actor = capture(); val row = row(id); requireOwner(actor, row)
        settings.runtime(row.settingsRevision)
        require(row.state == "READY") { "Torrent is not ready" }
        val distribution = journal.get(requireNotNull(row.distributionId))
        require(distribution.state == QbDistributionState.SEEDING)
        requireSession(actor)
        return requireNotNull(distribution.metadata).copyOf()
    }
    fun retire(id: UUID, retryUncertainRemoval: Boolean = false): OwnedTorrentStatus {
        val actor = access.captureAdministrator(); requireSession(actor)
        submit(id, onRejected = "UNCERTAIN", prepare = {
            repairSnapshotLink(id)
            repairIntentLink(id)
            mutate(id) {
                require((it.snapshotId != null || it.distributionId != null) &&
                    it.state in setOf("READY", "UNCERTAIN", "RUNNING", "RETIRING", "RECOVERED", "RECOVERING")) { "Request requires operator reconciliation" }
                it.state = "RETIRING"
            }
        }) { retireOwned(id, actor, retryUncertainRemoval) }
        return OwnedTorrentStatus(id.toString(), "RETIRING", null)
    }
    fun recover(id: UUID, action: OwnedTorrentRecoveryAction): OwnedTorrentStatus {
        val actor = access.captureAdministrator(); requireSession(actor)
        submit(id, onRejected = "UNCERTAIN", prepare = {
            repairSnapshotLink(id)
            repairIntentLink(id)
            mutate(id) {
                require(it.distributionId != null && it.state in setOf("UNCERTAIN", "RUNNING", "RECOVERING", "RECOVERED"))
                it.state = "RECOVERING"
            }
        }) {
            var lease: OwnedSnapshotLease? = null
            try {
                val row = row(id); val runtime = settings.historical(row)
                val intentId = requireNotNull(row.distributionId)
                val checkpoint = { current(actor, null) }
                QbDistributionApi(runtime.policy, runtime.credentials, checkpoint).use { api ->
                    val lifecycle = QbDistributionLifecycle(runtime.policy, api, journal, checkpoint)
                    val held = lifecycle.resume(intentId, acquisition(runtime)); lease = held
                    requireSession(actor)
                    when (action) {
                        OwnedTorrentRecoveryAction.RECONCILE_CREATION -> lifecycle.reconcileCreation(intentId, held)
                        OwnedTorrentRecoveryAction.VALIDATE_CREATION -> lifecycle.validate(intentId, held)
                        OwnedTorrentRecoveryAction.RECONCILE_ADD -> await(actor, null, 30) {
                            try { lifecycle.reconcileAdded(intentId, held); true }
                            catch (_: QbDistributionPendingConfirmation) { false }
                        }
                    }
                    held.closeAndRequireExit(); requireSession(actor)
                    mutate(id) { it.state = "RECOVERED" }
                }
            } catch (_: Exception) { mutate(id) { it.state = "UNCERTAIN" } }
            finally { lease?.close() }
        }
        return OwnedTorrentStatus(id.toString(), "RECOVERING", null)
    }
    private fun acquire(id: UUID, actor: PluginAudience) {
        var lease: OwnedSnapshotLease? = null
        var acquisitionStarted = false
        try {
            val row = row(id); val runtime = settings.runtime(row.settingsRevision)
            current(actor, runtime.revision); mutate(id) { it.state = "RUNNING" }
            val selection = mapper.readValue(row.selection, OwnedTorrentSelection::class.java)
            val host = acquisition(runtime)
            val game = games.getById(selection.gameId)
            acquisitionStarted = true
            val held = host.acquire(game, selection.variantId, selected(selection), onReserved = { snapshotId ->
                mutate(id) { require(it.snapshotId == null || it.snapshotId == snapshotId); it.snapshotId = snapshotId }
            }, plannedSnapshotId = row.plannedSnapshotId, hostRequestDigest = requireNotNull(row.hostRequestDigest))
            lease = held
            mutate(id) { it.snapshotId = held.snapshotId }
            val checkpoint = { current(actor, runtime.revision) }
            QbDistributionApi(runtime.policy, runtime.credentials, checkpoint).use { api ->
                val lifecycle = QbDistributionLifecycle(runtime.policy, api, journal, checkpoint)
                current(actor, runtime.revision)
                val intent = lifecycle.reserve(held, id)
                mutate(id) { it.distributionId = intent.id }
                lifecycle.create(intent.id, held)
                await(actor, runtime.revision, 120) {
                    api.ownedTask(requireNotNull(journal.get(intent.id).taskId), held.snapshotId, intent.id)["status"]?.asText() == "Finished"
                }
                current(actor, runtime.revision); lifecycle.validate(intent.id, held)
                current(actor, runtime.revision); lifecycle.addStopped(intent.id, held)
                await(actor, runtime.revision, 30) {
                    try { lifecycle.reconcileAdded(intent.id, held); true }
                    catch (_: QbDistributionPendingConfirmation) { false }
                }
                current(actor, runtime.revision); lifecycle.recheck(intent.id, held)
                await(actor, runtime.revision, 240) {
                    val info = api.find(requireNotNull(journal.get(intent.id).infoHash))
                    info != null && info["state"]?.asText() in setOf("stoppedUP", "stoppedDL") && info["progress"]?.doubleValue() == 1.0
                }
                current(actor, runtime.revision); lifecycle.confirmChecked(intent.id, held)
                current(actor, runtime.revision); lifecycle.start(intent.id, held)
            }
            held.closeAndRequireExit()
            current(actor, runtime.revision); mutate(id) { it.state = "READY" }
        } catch (failure: Exception) {
            // Never repeat create/add/start, nor release client ownership on an uncertain outcome.
            val acquisitionFailure = failure as? OwnedSnapshotAcquisitionFailure
            mutate(id) {
                acquisitionFailure?.snapshotId?.takeUnless { acquisitionFailure.reservationRefused }?.let { snapshotId ->
                    require(it.snapshotId == null || it.snapshotId == snapshotId); it.snapshotId = snapshotId
                }
                it.state = ownedTorrentFailureState(acquisitionStarted, acquisitionFailure?.safeToReject == true)
            }
        } finally { lease?.close() }
    }
    private fun retireOwned(id: UUID, actor: PluginAudience, retryUncertainRemoval: Boolean) {
        var lease: OwnedSnapshotLease? = null
        try {
            val row = row(id); val runtime = settings.historical(row)
            val host = acquisition(runtime)
            val intent = row.distributionId?.let(journal::get)
            if (intent != null) {
                val binding = snapshots.leaseBindingState(intent.snapshotId, intent.leaseId, intent.clientId, intent.id)
                if (binding == SnapshotLeaseBindingState.UNBOUND_ACTIVE || binding == SnapshotLeaseBindingState.UNBOUND_RELEASED) {
                    require(intent.state in setOf(QbDistributionState.RESERVED, QbDistributionState.CANCELLED)) { "Unbound intent cannot have authorized creator I/O" }
                    requireSession(actor)
                    val checkpoint = { current(actor, null) }
                    QbDistributionApi(runtime.policy, runtime.credentials, checkpoint).use { api ->
                        QbDistributionLifecycle(runtime.policy, api, journal, checkpoint).cancelUnboundReserved(intent.id, snapshots)
                    }
                    requireSession(actor); host.retire(intent.snapshotId)
                    mutate(id) { it.state = "RETIRED" }
                    return
                }
            }
            val released = intent?.takeIf { it.state in setOf(QbDistributionState.REMOVED, QbDistributionState.CANCELLED) }?.let {
                snapshots.clientLeaseReleased(it.snapshotId, it.leaseId, it.clientId, it.id)
            } ?: false
            val action = ownedTorrentRetirement(intent?.state, released)
            if (action == OwnedTorrentRetirement.RETIRE_SNAPSHOT) {
                requireSession(actor); host.retire(requireNotNull(row.snapshotId))
                mutate(id) { it.state = "RETIRED" }
                return
            }
            require(action != OwnedTorrentRetirement.RECONCILE_REQUIRED) { "Explicit lifecycle reconciliation is required before cancellation" }
            val intentId = requireNotNull(intent).id
            val checkpoint = { current(actor, null) }
            QbDistributionApi(runtime.policy, runtime.credentials, checkpoint).use { api ->
                val lifecycle = QbDistributionLifecycle(runtime.policy, api, journal, checkpoint)
                val held = lifecycle.resume(intentId, host)
                lease = held
                requireSession(actor)
                when (action) {
                    OwnedTorrentRetirement.FINALIZE_REMOVAL -> lifecycle.reconcileRemoval(intentId, held)
                    OwnedTorrentRetirement.FINALIZE_CANCELLATION, OwnedTorrentRetirement.CONFIRM_CANCELLATION ->
                        lifecycle.confirmCancellation(intentId, held)
                    OwnedTorrentRetirement.CANCEL_BEFORE_ADD -> lifecycle.cancelBeforeAdd(intentId, held)
                    OwnedTorrentRetirement.STOP_AND_REMOVE -> {
                        val state = journal.get(intentId).state
                        if (state !in setOf(QbDistributionState.STOPPING, QbDistributionState.REMOVING)) lifecycle.stopForRemoval(intentId, held)
                        if (journal.get(intentId).state == QbDistributionState.STOPPING) await(actor, null, 60) {
                            api.find(requireNotNull(journal.get(intentId).infoHash))?.get("state")?.asText() in setOf("stoppedUP", "stoppedDL")
                        }
                        requireSession(actor)
                        if (journal.get(intentId).state == QbDistributionState.STOPPING) lifecycle.removeStopped(intentId, held)
                        else if (journal.get(intentId).state == QbDistributionState.REMOVING && retryUncertainRemoval) lifecycle.retryRemoval(intentId, held)
                        requireSession(actor); lifecycle.reconcileRemoval(intentId, held)
                    }
                    else -> error("Unexpected retirement route")
                }
                require(journal.get(intentId).state in setOf(QbDistributionState.REMOVED, QbDistributionState.CANCELLED))
                held.closeAndRequireExit()
                requireSession(actor); host.retire(held.snapshotId)
                mutate(id) { it.state = "RETIRED" }
            }
        } catch (_: Exception) { mutate(id) { it.state = "UNCERTAIN" } }
        finally { lease?.close() }
    }
    @Synchronized private fun acquisition(runtime: OwnedTorrentRuntime): OwnedSnapshotAcquisitionService {
        val config = runtime.config
        val key = listOf(config.hostCacheRoot, config.byteLimit, config.fileLimit, config.reserveBytes).joinToString("\n")
        acquisitionInstance?.let { if (it.first == key) return it.second }
        require(acquisitionInstance == null || requests.countBySettingsRevisionNotAndStateNotIn(runtime.revision, listOf("REJECTED", "RETIRED")) == 0L) {
            "Previous acquisition profile still owns resources"
        }
        return OwnedSnapshotAcquisitionService(resolver, paths, snapshots, SnapshotWorkerSupervisor(Duration.ofMinutes(5)),
            OwnedSnapshotSettings(Path.of(config.hostCacheRoot), config.byteLimit, config.fileLimit, config.reserveBytes)).also {
            acquisitionInstance = key to it
        }
    }
    private fun repairIntentLink(id: UUID) {
        val row = row(id); val runtime = settings.historical(row)
        // Request UUID was durable before reserve/bind. No resend or tuple adoption on a missing link.
        val found = ownedTorrentLinkedIntent(id, row.snapshotId, row.distributionId,
            runtime.policy.clientId, runtime.policy.mappingDigest) { journal.find(id) } ?: return
        require(snapshots.get(found.snapshotId).manifestDigest == found.manifestDigest) { "Manifest identity differs" }
        mutate(id) { require(it.distributionId == null || it.distributionId == found.id); it.distributionId = found.id }
    }
    private fun repairSnapshotLink(id: UUID) {
        val request = row(id)
        val found = snapshots.find(request.plannedSnapshotId)
        val snapshotId = ownedTorrentSnapshotBinding(request, found) ?: return
        val selection = mapper.readValue(request.selection, OwnedTorrentSelection::class.java)
        require(found != null && found.gameId == selection.gameId && found.variantId == selection.variantId) { "Snapshot selection identity differs" }
        mutate(id) { require(it.snapshotId == null || it.snapshotId == snapshotId); it.snapshotId = snapshotId }
    }
    private fun selected(selection: OwnedTorrentSelection) = if (selection.explicitSelection) selection.contentIds ?: emptyList() else selection.contentIds
    private fun capture(): PluginAudience = access.capture().also { actor ->
        if (actor.authentication == null || actor.account == null) throw AccessDeniedException("Authentication required")
        requireSession(actor)
    }
    private fun requireSession(actor: PluginAudience) {
        if (actor.session == null || actor.sessionId == null) throw AccessDeniedException("Persisted browser session required")
        access.requireCurrent(actor)
    }
    private fun owner(actor: PluginAudience): String {
        val account = requireNotNull(actor.account)
        return SnapshotProtocol.digest(listOf(account.id.toString(), account.username, account.oidcSubject ?: "", actor.sessionId).joinToString("\n").toByteArray())
    }
    private fun requireOwner(actor: PluginAudience, row: OwnedTorrentRequestRow) {
        if (row.ownerKey != owner(actor)) throw AccessDeniedException("Request belongs to another browser session")
        requireSession(actor)
    }
    internal fun current(actor: PluginAudience, revision: Long?) {
        requireSession(actor)
        if (revision != null) settings.runtime(revision)
        requireSession(actor)
        check(!Thread.currentThread().isInterrupted)
    }
    private fun await(actor: PluginAudience, revision: Long?, seconds: Long, complete: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            current(actor, revision)
            if (complete()) return
            check(System.nanoTime() < deadline) { "Owned torrent deadline exceeded" }
            Thread.sleep(1000)
        }
    }
    private fun row(id: UUID): OwnedTorrentRequestRow = requireNotNull(tx.execute { requests.findById(id).orElse(null) })
    private fun mutate(id: UUID, change: (OwnedTorrentRequestRow) -> Unit) = tx.executeWithoutResult {
        val row = requireNotNull(requests.locked(id)); change(row); requests.saveAndFlush(row)
    }
    private fun submit(id: UUID, onRejected: String, prepare: () -> Unit = {}, action: () -> Unit) {
        check(active.add(id)) { "Request already active" }
        var prepared = false
        try { prepare(); prepared = true; executor.execute { try { action() } finally { active.remove(id) } } }
        catch (failure: RuntimeException) { active.remove(id); if (prepared) mutate(id) { it.state = onRejected }; throw failure }
    }
    @PreDestroy fun shutdown() { executor.shutdownNow() }
}
