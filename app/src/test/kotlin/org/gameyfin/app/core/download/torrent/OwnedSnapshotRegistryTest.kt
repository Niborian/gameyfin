package org.gameyfin.app.core.download.torrent

import jakarta.persistence.EntityManager
import org.gameyfin.snapshot.SnapshotCapture
import org.gameyfin.snapshot.SnapshotManifest
import org.gameyfin.snapshot.SnapshotMember
import org.gameyfin.snapshot.SnapshotProtocol
import org.gameyfin.snapshot.SnapshotSelection
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.*
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest
@Import(OwnedSnapshotRegistry::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OwnedSnapshotRegistryTest @Autowired internal constructor(private val registry: OwnedSnapshotRegistry,
    private val snapshots: OwnedSnapshotRepository, private val leases: OwnedSnapshotLeaseRepository,
    private val manager: PlatformTransactionManager, private val entityManager: EntityManager,
    private val consumers: OwnedSnapshotConsumerRepository, private val admission: OwnedSnapshotAdmissionRepository) {
    private fun reserve(): SnapshotRecordView {
        val request = SnapshotCapture(UUID.randomUUID(), UUID.randomUUID(), "/source", "/cache", "owned-root",
            listOf(SnapshotSelection("base.bin", "content-1")), 1024, 8, 0)
        return registry.reserve(request.snapshotId, request.operation, 42, 84, request.bytes())
    }
    private fun manifest(record: SnapshotRecordView) = SnapshotManifest(record.id, record.operation, record.requestDigest,
        "owned-root", "owned-directory", listOf(SnapshotMember("content-1", 3, "a".repeat(64), "copied", "original"))).bytes()

    @Test fun `committed operation survives outer rollback and cannot begin twice`() {
        val reserved = reserve()
        TransactionTemplate(manager).execute { outer -> registry.begin(reserved.id); outer.setRollbackOnly() }
        TransactionTemplate(manager).execute { entityManager.clear() }
        val restarted = OwnedSnapshotRegistry(snapshots, leases, manager, consumers, admission)
        assertEquals(OwnedSnapshotState.IN_FLIGHT, restarted.get(reserved.id).state)
        assertFails { restarted.begin(reserved.id) }
        restarted.uncertain(reserved.id)
        assertFails { restarted.begin(reserved.id) }
    }
    @Test fun `uncertainty cannot grant lease but verified exact manifest can reconcile`() {
        val reserved = reserve(); registry.begin(reserved.id); registry.uncertain(reserved.id)
        assertFails { registry.lease(reserved.id) }
        val valid = manifest(reserved)
        val ready = registry.ready(reserved.id, valid)
        assertEquals(OwnedSnapshotState.READY, ready.state)
        assertEquals(SnapshotProtocol.digest(valid), ready.manifestDigest)
        val foreign = SnapshotManifest.read(valid).copy(operation = UUID.randomUUID()).bytes()
        assertFails { registry.ready(reserved.id, foreign) }
        val rebound = SnapshotManifest.read(valid).copy(members = listOf(SnapshotMember("different", 3, "b".repeat(64), "copied", "original"))).bytes()
        assertFails { registry.ready(reserved.id, rebound) }
        assertEquals(ready.manifestDigest, registry.get(reserved.id).manifestDigest)
    }
    @Test fun `durable lease survives registry recreation and is not released by uncertainty`() {
        val reserved = reserve(); registry.begin(reserved.id); registry.ready(reserved.id, manifest(reserved))
        val token = registry.lease(reserved.id)
        val restarted = OwnedSnapshotRegistry(snapshots, leases, manager, consumers, admission)
        restarted.uncertain(reserved.id)
        assertEquals(1, restarted.activeLeases(reserved.id))
        assertFails { restarted.lease(reserved.id) }
        assertFails { restarted.release(UUID.randomUUID(), token) }
        assertEquals(1, restarted.activeLeases(reserved.id))
        restarted.release(reserved.id, token); restarted.release(reserved.id, token)
        assertEquals(0, restarted.activeLeases(reserved.id))
    }
    @Test fun `concurrent begin has exactly one committed winner`() {
        val reserved = reserve(); val start = CountDownLatch(1); val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = (1..2).map { executor.submit(Callable {
                start.await(); try { registry.begin(reserved.id); true } catch (_: IllegalArgumentException) { false }
            }) }
            start.countDown()
            assertEquals(1, attempts.count { it.get(15, TimeUnit.SECONDS) })
        } finally { executor.shutdownNow() }
    }
    @Test fun `reservation cannot imply ready state or release a foreign lease`() {
        val first = reserve(); val second = reserve()
        assertFails { registry.ready(first.id, manifest(first)) }
        registry.begin(first.id); registry.ready(first.id, manifest(first))
        val token = registry.lease(first.id)
        assertFails { registry.release(second.id, token) }
        assertEquals(1, registry.activeLeases(first.id))
    }
    @Test fun `duplicate resumed consumers fence external lease release until every worker exited`() {
        val reserved = reserve(); registry.begin(reserved.id); registry.ready(reserved.id, manifest(reserved))
        val lease = registry.lease(reserved.id); val client = UUID.randomUUID(); val ownership = UUID.randomUUID()
        val original = registry.bindClient(reserved.id, lease, client, ownership)
        assertEquals(SnapshotLeaseBindingState.BOUND_ACTIVE, registry.leaseBindingState(reserved.id, lease, client, ownership))
        assertFails { registry.leaseBindingState(reserved.id, lease, UUID.randomUUID(), ownership) }
        assertFails { registry.releaseUnboundLeaseAfterExit(reserved.id, lease, object : SnapshotProcessProbe {
            override fun scope() = "a".repeat(64); override fun observe(pid: Long) = SnapshotProcessObservation.Absent
        }) }
        assertFalse(registry.clientLeaseReleased(reserved.id, lease, client, ownership))
        assertFails { registry.clientLeaseReleased(reserved.id, lease, UUID.randomUUID(), ownership) }
        val first = registry.claimClientLease(reserved.id, lease, client, ownership)
        val second = registry.claimClientLease(reserved.id, lease, client, ownership)
        assertNotEquals(first, second)
        registry.releaseConsumer(reserved.id, lease, original)
        registry.releaseConsumer(reserved.id, lease, first)
        assertFails { registry.releaseClient(reserved.id, lease, client, ownership) }
        assertEquals(1, registry.activeLeases(reserved.id))
        registry.releaseConsumer(reserved.id, lease, second)
        registry.releaseClient(reserved.id, lease, client, ownership)
        assertTrue(registry.clientLeaseReleased(reserved.id, lease, client, ownership))
        assertEquals(SnapshotLeaseBindingState.BOUND_RELEASED, registry.leaseBindingState(reserved.id, lease, client, ownership))
        assertEquals(0, registry.activeLeases(reserved.id))
        assertFails { registry.claimClientLease(reserved.id, lease, client, ownership) }
    }
    @Test fun `orphan descriptor claims reconcile only exact same host boot process identity and retain external lease`() {
        val reserved = reserve(); registry.begin(reserved.id); registry.ready(reserved.id, manifest(reserved))
        val owner = SnapshotProcessIdentity("a".repeat(64), 100, 1000)
        val worker = SnapshotProcessIdentity(owner.scope, 200, 2000)
        val lease = registry.lease(reserved.id, owner); registry.bindLeaseWorker(reserved.id, lease, worker)
        val client = UUID.randomUUID(); val ownership = UUID.randomUUID()
        registry.bindClient(reserved.id, lease, client, ownership)
        fun probe(scope: String?, observation: SnapshotProcessObservation) = object : SnapshotProcessProbe {
            override fun scope() = scope
            override fun observe(pid: Long) = observation
        }
        val restarted = OwnedSnapshotRegistry(snapshots, leases, manager, consumers, admission)
        assertEquals(0, restarted.reconcileExitedConsumers(reserved.id, lease, client, ownership, probe("b".repeat(64), SnapshotProcessObservation.Absent)))
        assertFails { restarted.releaseClient(reserved.id, lease, client, ownership) }
        assertEquals(0, restarted.reconcileExitedConsumers(reserved.id, lease, client, ownership, probe(owner.scope, SnapshotProcessObservation.Present(worker.startedMillis))))
        assertEquals(1, restarted.reconcileExitedConsumers(reserved.id, lease, client, ownership, probe(owner.scope, SnapshotProcessObservation.Absent)))
        assertEquals(1, restarted.activeLeases(reserved.id), "Exited worker is not evidence that qB ownership was removed")
        restarted.releaseClient(reserved.id, lease, client, ownership)
        assertEquals(0, restarted.activeLeases(reserved.id))
    }
    @Test fun `normal orphan session lease can reconcile without pretending foreign namespace absence is exit`() {
        val reserved = reserve(); registry.begin(reserved.id); registry.ready(reserved.id, manifest(reserved))
        val owner = SnapshotProcessIdentity("a".repeat(64), 100, 1000)
        val worker = SnapshotProcessIdentity(owner.scope, 200, 2000)
        val lease = registry.lease(reserved.id, owner); registry.bindLeaseWorker(reserved.id, lease, worker)
        val foreign = object : SnapshotProcessProbe { override fun scope() = "b".repeat(64); override fun observe(pid: Long) = SnapshotProcessObservation.Absent }
        assertEquals(0, registry.reconcileExitedSessionLeases(reserved.id, foreign))
        assertEquals(1, registry.activeLeases(reserved.id))
        val local = object : SnapshotProcessProbe { override fun scope() = owner.scope; override fun observe(pid: Long) = SnapshotProcessObservation.Absent }
        assertEquals(1, registry.reconcileExitedSessionLeases(reserved.id, local))
        assertEquals(0, registry.activeLeases(reserved.id))
    }
    @Test fun `prepared identity commits before capture and retirement fences readers and old workers`() {
        val request = SnapshotCapture(UUID.randomUUID(), UUID.randomUUID(), "/source", "/cache", "owned-root",
            listOf(SnapshotSelection("base.bin", "base")), 1024, 8, 0)
        val owner = SnapshotProcessIdentity("a".repeat(64), 100, 1000)
        val oldWorker = SnapshotProcessIdentity(owner.scope, 200, 2000)
        val record = registry.reserve(request.snapshotId, request.operation, 42, 84, request.bytes(), owner, oldWorker)
        registry.begin(record.id)
        val prepared = org.gameyfin.snapshot.SnapshotPreparation(record.id, record.operation, record.requestDigest,
            "owned-root", "owned-directory", "owned-data").bytes()
        registry.recordPreparation(record.id, prepared)
        assertContentEquals(prepared, registry.get(record.id).preparation)
        assertFails { registry.recordPreparation(record.id, org.gameyfin.snapshot.SnapshotPreparation.read(prepared).copy(dataIdentity = "foreign").bytes()) }
        assertFails { registry.ready(record.id, SnapshotManifest.read(manifest(record)).copy(directoryIdentity = "foreign").bytes()) }
        registry.ready(record.id, manifest(record))
        val token = registry.lease(record.id)
        val cleanup = SnapshotProcessIdentity(owner.scope, 300, 3000)
        val exited = object : SnapshotProcessProbe { override fun scope() = owner.scope; override fun observe(pid: Long) = SnapshotProcessObservation.Absent }
        assertFails { registry.beginRetirement(record.id, owner, cleanup, exited) }
        registry.release(record.id, token)
        val unknown = object : SnapshotProcessProbe { override fun scope() = "b".repeat(64); override fun observe(pid: Long) = SnapshotProcessObservation.Absent }
        assertFails { registry.beginRetirement(record.id, owner, cleanup, unknown) }
        registry.beginRetirement(record.id, owner, cleanup, exited)
        assertFails { registry.reserveReopenLease(record.id, owner) }
        assertFails { registry.finishRetirement(record.id, oldWorker) }
        registry.finishRetirement(record.id, cleanup)
        assertEquals(OwnedSnapshotState.RETIRED, registry.get(record.id).state)
        assertFails { registry.uncertain(record.id) }
        assertFails { registry.ready(record.id, manifest(record)) }
    }
    @Test fun `uncertain operations remain charged to global count and aggregate copy budget`() {
        fun request() = SnapshotCapture(UUID.randomUUID(), UUID.randomUUID(), "/source", "/cache", "owned-root",
            listOf(SnapshotSelection("base.bin", "base")), 1024, 8, 0)
        val first = request()
        val countLimit = (snapshots.unretiredCount() + 1).toInt()
        val byteLimit = snapshots.unretiredBytes() + first.byteLimit
        registry.reserve(first.snapshotId, first.operation, 42, 84, first.bytes(),
            maxUnretiredSnapshots = countLimit, maxUnretiredBytes = byteLimit)
        registry.begin(first.snapshotId); registry.uncertain(first.snapshotId)
        val second = request()
        assertFailsWith<SnapshotAdmissionRefused> { registry.reserve(second.snapshotId, second.operation, 42, 84, second.bytes(),
            maxUnretiredSnapshots = countLimit, maxUnretiredBytes = byteLimit + 1024) }
        assertFailsWith<SnapshotAdmissionRefused> { registry.reserve(second.snapshotId, second.operation, 42, 84, second.bytes(),
            maxUnretiredSnapshots = 256, maxUnretiredBytes = byteLimit) }
        assertFalse(snapshots.existsById(second.snapshotId))
        assertEquals(countLimit.toLong(), snapshots.unretiredCount())
        assertEquals(byteLimit, snapshots.unretiredBytes())
    }
    @Test fun `reserved cancellation can reconcile only its exact unbound original worker or preopen creator lease`() {
        val reserved = reserve(); registry.begin(reserved.id); registry.ready(reserved.id, manifest(reserved))
        val creator = SnapshotProcessIdentity("a".repeat(64), 100, 1000)
        val worker = creator.copy(pid = 200, startedMillis = 2000)
        val token = registry.lease(reserved.id, creator); registry.bindLeaseWorker(reserved.id, token, worker)
        val client = UUID.randomUUID(); val ownership = UUID.randomUUID()
        assertEquals(SnapshotLeaseBindingState.UNBOUND_ACTIVE, registry.leaseBindingState(reserved.id, token, client, ownership))
        fun probe(scope: String, observation: SnapshotProcessObservation) = object : SnapshotProcessProbe {
            override fun scope() = scope; override fun observe(pid: Long) = observation
        }
        assertFalse(registry.releaseUnboundLeaseAfterExit(reserved.id, token, probe("b".repeat(64), SnapshotProcessObservation.Absent)))
        assertFalse(registry.releaseUnboundLeaseAfterExit(reserved.id, token, probe(creator.scope, SnapshotProcessObservation.Present(worker.startedMillis))))
        assertFalse(registry.releaseUnboundLeaseAfterExit(reserved.id, token, probe(creator.scope, SnapshotProcessObservation.Unknown)))
        assertTrue(registry.releaseUnboundLeaseAfterExit(reserved.id, token, probe(creator.scope, SnapshotProcessObservation.Absent)))
        assertEquals(SnapshotLeaseBindingState.UNBOUND_RELEASED, registry.leaseBindingState(reserved.id, token, client, ownership))
        assertFails { registry.releaseUnboundLeaseAfterExit(UUID.randomUUID(), token, probe(creator.scope, SnapshotProcessObservation.Absent)) }
        // Protective reopen claim was committed, but no worker binding/OPEN occurred.
        val preopen = registry.reserveReopenLease(reserved.id, creator)
        assertTrue(registry.releaseUnboundLeaseAfterExit(reserved.id, preopen, probe(creator.scope, SnapshotProcessObservation.Absent)))
        assertEquals(0, registry.activeLeases(reserved.id))
    }
}
