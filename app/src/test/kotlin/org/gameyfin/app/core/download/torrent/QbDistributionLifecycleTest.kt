package org.gameyfin.app.core.download.torrent

import io.mockk.*
import org.gameyfin.snapshot.SnapshotManifest
import org.gameyfin.snapshot.SnapshotProtocol
import org.junit.jupiter.api.Test
import java.util.UUID
import tools.jackson.databind.json.JsonMapper
import kotlin.test.*

class QbDistributionLifecycleTest {
    private val policy = QbDistributionPolicy(UUID.randomUUID(), "http://127.0.0.1:1234", expectedWebApiVersion = "2.11.0",
        qbCacheRoot = "/owned/cache", cacheIdentity = "cache", approvedTrackers = setOf("https://tracker.example/announce"),
        dedicatedReadOnlyClientConfirmed = true, privatePeerEgressConfirmed = true)
    private val snapshot = UUID.randomUUID(); private val token = UUID.randomUUID(); private val id = UUID.randomUUID()
    private val manifest = SnapshotManifest(snapshot, UUID.randomUUID(), "a".repeat(64), "cache", "directory", emptyList())
    private fun record(state: QbDistributionState) = QbDistributionRecord(id, policy.clientId, snapshot, token,
        SnapshotProtocol.digest(manifest.bytes()), policy.mappingDigest, state, "task", "b".repeat(40), byteArrayOf(1))

    @Test fun `unknown creator outcome remains committed and is never automatically recreated`() {
        val api = mockk<QbDistributionApi>(); val journal = mockk<QbDistributionJournal>(); val lease = mockk<OwnedSnapshotLease>()
        var row = record(QbDistributionState.RESERVED)
        every { lease.snapshotId } returns snapshot; every { lease.leaseId } returns token; every { lease.manifest } returns manifest
        every { lease.retainForClient(policy.clientId, id) } just Runs; every { lease.verifyForClient("cache") } just Runs
        every { journal.get(id) } answers { row }
        every { journal.transition(id, setOf(QbDistributionState.RESERVED), QbDistributionState.CREATING, any(), any(), any()) } answers { row = row.copy(state = QbDistributionState.CREATING); row }
        every { api.verifyClient() } just Runs
        every { api.create(snapshot, id) } throws IllegalStateException("synthetic unknown outcome")
        val lifecycle = QbDistributionLifecycle(policy, api, journal)
        assertFails { lifecycle.create(id, lease) }
        assertEquals(QbDistributionState.CREATING, row.state)
        assertFails { lifecycle.create(id, lease) }
        verify(exactly = 1) { api.create(snapshot, id) }
        verify(exactly = 0) { lease.close() }
    }

    @Test fun `DB release outage after worker exit permits same closed lease retry without rebinding`() {
        val api = mockk<QbDistributionApi>(); val journal = mockk<QbDistributionJournal>()
        val worker = mockk<SnapshotWorkerConnection>(); val registry = mockk<OwnedSnapshotRegistry>()
        var attempts = 0
        every { worker.finish() } returns true
        every { registry.releaseClient(snapshot, token, policy.clientId, id) } answers { attempts++; if (attempts == 1) error("synthetic outage") }
        val lease = OwnedSnapshotLease(snapshot, token, manifest, worker, registry, SnapshotClientBinding(policy.clientId, id)) {}
        every { journal.get(id) } returns record(QbDistributionState.REMOVED)
        every { api.verifyClient() } just Runs; every { api.find("b".repeat(40)) } returns null; every { api.tasks() } returns emptyList()
        val lifecycle = QbDistributionLifecycle(policy, api, journal)
        assertFails { lifecycle.reconcileRemoval(id, lease) }
        assertEquals(QbDistributionState.REMOVED, lifecycle.reconcileRemoval(id, lease).state)
        assertEquals(2, attempts)
        verify(exactly = 1) { worker.finish() }
        verify(exactly = 0) { registry.bindClient(any(), any(), any(), any()) }
        verify(exactly = 0) { api.remove(any()) }
    }

    @Test fun `restart asks for exact durable token not a new snapshot lease`() {
        val api = mockk<QbDistributionApi>(); val journal = mockk<QbDistributionJournal>(); val snapshots = mockk<OwnedSnapshotAcquisitionService>()
        val lease = mockk<OwnedSnapshotLease>()
        every { journal.get(id) } returns record(QbDistributionState.ADDING)
        every { snapshots.resumeClientLease(snapshot, token, policy.clientId, id) } returns lease
        assertSame(lease, QbDistributionLifecycle(policy, api, journal).resume(id, snapshots))
        verify(exactly = 0) { snapshots.reopen(any()) }
    }

    @Test fun `foreign torrent at recorded hash is never stopped or otherwise mutated`() {
        val api = mockk<QbDistributionApi>(); val journal = mockk<QbDistributionJournal>(); val lease = mockk<OwnedSnapshotLease>()
        every { lease.snapshotId } returns snapshot; every { lease.leaseId } returns token; every { lease.manifest } returns manifest
        every { lease.retainForClient(policy.clientId, id) } just Runs; every { lease.verifyForClient("cache") } just Runs
        every { journal.get(id) } returns record(QbDistributionState.STARTING)
        every { api.verifyClient() } just Runs
        every { api.find("b".repeat(40)) } returns JsonMapper.builder().build().readTree("""{"category":"foreign","state":"downloading"}""")
        assertFailsWith<IllegalArgumentException> { QbDistributionLifecycle(policy, api, journal).confirmSeeding(id, lease) }
        verify(exactly = 0) { api.stop(any()) }
        verify(exactly = 0) { api.start(any()) }
        verify(exactly = 0) { api.remove(any()) }
        verify(exactly = 0) { api.deleteTask(any()) }
        verify(exactly = 0) { lease.close() }
    }

    @Test fun `rejected duplicate selection cancels only its finished creator and retains foreign torrent`() {
        val api = mockk<QbDistributionApi>(); val journal = mockk<QbDistributionJournal>(); val lease = mockk<OwnedSnapshotLease>()
        val payload = byteArrayOf(7)
        val actualManifest = manifest.copy(members = listOf(org.gameyfin.snapshot.SnapshotMember("base.bin", 1,
            QbDistributionPolicy.digest(payload), "copied", "original")))
        val info = mapOf("files" to listOf(mapOf("length" to 1L, "path" to listOf("base.bin"))), "name" to "data",
            "piece length" to 16384L, "pieces" to java.security.MessageDigest.getInstance("SHA-1").digest(payload), "private" to 1L)
        val metadata = bencode(mapOf("announce" to policy.approvedTrackers.single(), "info" to info))
        val hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(bencode(info)))
        var row = record(QbDistributionState.CREATED).copy(infoHash = null, metadata = null, manifestDigest = SnapshotProtocol.digest(actualManifest.bytes()))
        val mapper = JsonMapper.builder().build()
        val task = mapper.readTree("""{"taskID":"task","sourcePath":"${policy.source(snapshot)}","comment":"${policy.comment(id)}","status":"Finished"}""")
        var tasks = listOf(task)
        every { lease.snapshotId } returns snapshot; every { lease.leaseId } returns token; every { lease.manifest } returns actualManifest
        every { lease.openMember("base.bin") } answers { payload.inputStream() }
        every { lease.retainForClient(policy.clientId, id) } just Runs; every { lease.verifyForClient("cache") } just Runs
        every { lease.releaseAfterClientRemoval(policy.clientId, id) } just Runs
        every { journal.get(id) } answers { row }
        every { journal.transition(id, any(), any(), any(), any(), any()) } answers { row = row.copy(state = thirdArg()); row }
        every { api.verifyClient() } just Runs
        val foreign = mapper.readTree("""{"hash":"$hash","save_path":"/another/snapshot","content_path":"/another/snapshot/data","tags":"another-owned-operation"}""")
        every { api.inventory() } returns listOf(foreign)
        every { api.metadata("task") } returns metadata
        every { api.find(hash) } returns foreign
        every { api.tasks() } answers { tasks }
        every { api.ownedTask("task", snapshot, id) } returns task
        every { api.deleteTask("task") } answers { tasks = emptyList() }
        val lifecycle = QbDistributionLifecycle(policy, api, journal)
        assertEquals("Existing torrent is not adopted", assertFailsWith<IllegalArgumentException> { lifecycle.validate(id, lease) }.message)
        assertEquals(QbDistributionState.CREATED, row.state)
        assertEquals(QbDistributionState.CANCELLED, lifecycle.cancelBeforeAdd(id, lease).state)
        verify(exactly = 1) { api.deleteTask("task") }
        verify(exactly = 1) { lease.releaseAfterClientRemoval(policy.clientId, id) }
        verify(exactly = 0) { api.remove(any()) }
        verify(exactly = 0) { api.stop(any()) }
        verify(exactly = 0) { api.addStopped(any(), any(), any()) }
    }

    private fun bencode(value: Any): ByteArray = when (value) {
        is String -> bencode(value.toByteArray())
        is ByteArray -> "${value.size}:".toByteArray() + value
        is Long -> "i${value}e".toByteArray()
        is List<*> -> "l".toByteArray() + value.fold(byteArrayOf()) { bytes, item -> bytes + bencode(requireNotNull(item)) } + "e".toByteArray()
        is Map<*, *> -> "d".toByteArray() + value.entries.sortedBy { it.key.toString() }.fold(byteArrayOf()) { bytes, entry ->
            bytes + bencode(entry.key.toString()) + bencode(requireNotNull(entry.value)) } + "e".toByteArray()
        else -> error("Unsupported fixture value")
    }

    @Test fun `running creator and zero-match ambiguous creation cannot release snapshot`() {
        val api = mockk<QbDistributionApi>(); val journal = mockk<QbDistributionJournal>(); val lease = mockk<OwnedSnapshotLease>()
        var row = record(QbDistributionState.CREATED).copy(infoHash = null, metadata = null)
        val task = JsonMapper.builder().build().readTree("""{"taskID":"task","sourcePath":"${policy.source(snapshot)}","comment":"${policy.comment(id)}","status":"Running"}""")
        every { lease.snapshotId } returns snapshot; every { lease.leaseId } returns token; every { lease.manifest } returns manifest
        every { lease.retainForClient(policy.clientId, id) } just Runs; every { lease.verifyForClient("cache") } just Runs
        every { journal.get(id) } answers { row }
        every { api.verifyClient() } just Runs; every { api.inventory() } returns emptyList(); every { api.tasks() } returns listOf(task)
        every { api.ownedTask("task", snapshot, id) } returns task
        val lifecycle = QbDistributionLifecycle(policy, api, journal)
        assertFails { lifecycle.cancelBeforeAdd(id, lease) }
        row = row.copy(state = QbDistributionState.CREATING, taskId = null)
        every { api.tasks() } returns emptyList()
        assertFails { lifecycle.cancelBeforeAdd(id, lease) }
        assertFails { lifecycle.reconcileCreation(id, lease) }
        verify(exactly = 0) { api.deleteTask(any()) }
        verify(exactly = 0) { api.create(any(), any()) }
        verify(exactly = 0) { lease.releaseAfterClientRemoval(any(), any()) }
    }

    @Test fun `cancelled terminal DB release outage retries same closed lease without new ownership`() {
        val api = mockk<QbDistributionApi>(); val journal = mockk<QbDistributionJournal>()
        val worker = mockk<SnapshotWorkerConnection>(); val registry = mockk<OwnedSnapshotRegistry>()
        var attempts = 0
        every { worker.finish() } returns true
        every { registry.releaseClient(snapshot, token, policy.clientId, id) } answers { attempts++; if (attempts == 1) error("synthetic outage") }
        val lease = OwnedSnapshotLease(snapshot, token, manifest, worker, registry, SnapshotClientBinding(policy.clientId, id)) {}
        every { journal.get(id) } returns record(QbDistributionState.CANCELLED).copy(taskId = null, infoHash = null, metadata = null)
        every { api.verifyClient() } just Runs; every { api.inventory() } returns emptyList(); every { api.tasks() } returns emptyList()
        val lifecycle = QbDistributionLifecycle(policy, api, journal)
        assertFails { lifecycle.confirmCancellation(id, lease) }
        assertEquals(QbDistributionState.CANCELLED, lifecycle.confirmCancellation(id, lease).state)
        assertEquals(2, attempts)
        verify(exactly = 1) { worker.finish() }
        verify(exactly = 0) { registry.bindClient(any(), any(), any(), any()) }
        verify(exactly = 0) { api.deleteTask(any()) }
    }

    @Test fun `broad parent torrent or creator binding refuses cancellation without foreign mutation`() {
        val api = mockk<QbDistributionApi>(); val journal = mockk<QbDistributionJournal>(); val lease = mockk<OwnedSnapshotLease>()
        val mapper = JsonMapper.builder().build()
        val row = record(QbDistributionState.CREATED).copy(infoHash = null, metadata = null)
        every { lease.snapshotId } returns snapshot; every { lease.leaseId } returns token; every { lease.manifest } returns manifest
        every { lease.retainForClient(policy.clientId, id) } just Runs; every { lease.verifyForClient("cache") } just Runs
        every { journal.get(id) } returns row
        every { api.verifyClient() } just Runs
        val lifecycle = QbDistributionLifecycle(policy, api, journal)
        for (path in listOf(policy.savePath(snapshot), "/owned/cache", "/", policy.source(snapshot) + "/nested", "/owned/../cache")) {
            every { api.inventory() } returns listOf(mapper.readTree("""{"hash":"${"b".repeat(40)}","save_path":"/somewhere","content_path":"$path","tags":"foreign"}"""))
            assertFails { lifecycle.cancelBeforeAdd(id, lease) }
        }
        every { api.inventory() } returns emptyList()
        for (path in listOf(policy.savePath(snapshot), "/owned/cache", "/", policy.source(snapshot) + "/nested")) {
            every { api.tasks() } returns listOf(mapper.readTree("""{"taskID":"foreign-task","sourcePath":"$path","comment":"foreign","status":"Running"}"""))
            assertFails { lifecycle.cancelBeforeAdd(id, lease) }
        }
        verify(exactly = 0) { journal.transition(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { api.deleteTask(any()) }
        verify(exactly = 0) { api.remove(any()) }
        verify(exactly = 0) { api.stop(any()) }
        verify(exactly = 0) { lease.releaseAfterClientRemoval(any(), any()) }
    }

    @Test fun `reserve bind failure recovers only exact released never-bound lease without replacement`() {
        val api = mockk<QbDistributionApi>(); val journal = mockk<QbDistributionJournal>(); val snapshots = mockk<OwnedSnapshotRegistry>()
        var row = record(QbDistributionState.RESERVED).copy(taskId = null, infoHash = null, metadata = null)
        val snapshotRecord = mockk<SnapshotRecordView>()
        every { snapshotRecord.manifestDigest } returns row.manifestDigest
        every { journal.get(id) } answers { row }; every { snapshots.get(snapshot) } returns snapshotRecord
        var binding = SnapshotLeaseBindingState.UNBOUND_ACTIVE
        every { snapshots.leaseBindingState(snapshot, token, policy.clientId, id) } answers { binding }
        every { snapshots.releaseUnboundLeaseAfterExit(snapshot, token, SnapshotProcessProbe.SYSTEM) } returns false
        val lifecycle = QbDistributionLifecycle(policy, api, journal)
        val originalLease = mockk<OwnedSnapshotLease>()
        every { originalLease.snapshotId } returns snapshot; every { originalLease.leaseId } returns token; every { originalLease.manifest } returns manifest
        every { originalLease.verifyForClient(policy.cacheIdentity) } just Runs
        every { journal.reserve(policy.clientId, snapshot, token, row.manifestDigest, policy.mappingDigest, id) } returns row
        every { originalLease.retainForClient(policy.clientId, id) } throws IllegalStateException("Synthetic bind commit outage")
        assertFails { lifecycle.reserve(originalLease, id) }
        assertFails { lifecycle.cancelUnboundReserved(id, snapshots) }
        verify(exactly = 0) { api.verifyClient() }
        every { snapshots.releaseUnboundLeaseAfterExit(snapshot, token, SnapshotProcessProbe.SYSTEM) } answers { binding = SnapshotLeaseBindingState.UNBOUND_RELEASED; true }
        every { api.verifyClient() } just Runs; every { api.inventory() } returns emptyList(); every { api.tasks() } returns emptyList()
        every { journal.transition(id, setOf(QbDistributionState.RESERVED), QbDistributionState.CANCELLED, any(), any(), any()) } answers { row = row.copy(state = QbDistributionState.CANCELLED); row }
        assertEquals(QbDistributionState.CANCELLED, lifecycle.cancelUnboundReserved(id, snapshots).state)
        assertEquals(QbDistributionState.CANCELLED, lifecycle.cancelUnboundReserved(id, snapshots).state)
        verify(exactly = 0) { snapshots.bindClient(any(), any(), any(), any()) }
        verify(exactly = 0) { snapshots.lease(any()) }
        verify(exactly = 0) { snapshots.releaseClient(any(), any(), any(), any()) }
        verify(exactly = 0) { api.deleteTask(any()) }
    }

    @Test fun `unbound recovery refuses any bound lease and post-create lineage`() {
        val api = mockk<QbDistributionApi>(); val journal = mockk<QbDistributionJournal>(); val snapshots = mockk<OwnedSnapshotRegistry>()
        var row = record(QbDistributionState.RESERVED).copy(taskId = null, infoHash = null, metadata = null)
        val snapshotRecord = mockk<SnapshotRecordView>()
        every { snapshotRecord.manifestDigest } returns row.manifestDigest
        every { journal.get(id) } answers { row }; every { snapshots.get(snapshot) } returns snapshotRecord
        val lifecycle = QbDistributionLifecycle(policy, api, journal)
        for (binding in listOf(SnapshotLeaseBindingState.BOUND_ACTIVE, SnapshotLeaseBindingState.BOUND_RELEASED)) {
            every { snapshots.leaseBindingState(snapshot, token, policy.clientId, id) } returns binding
            assertFails { lifecycle.cancelUnboundReserved(id, snapshots) }
        }
        row = row.copy(state = QbDistributionState.CREATING)
        assertFails { lifecycle.cancelUnboundReserved(id, snapshots) }
        verify(exactly = 0) { snapshots.releaseUnboundLeaseAfterExit(any(), any(), any()) }
        verify(exactly = 0) { api.verifyClient() }
    }

    @Test fun `revocation after delayed proof retains committed creator uncertainty without POST`() {
        val api = mockk<QbDistributionApi>(); val journal = mockk<QbDistributionJournal>(); val lease = mockk<OwnedSnapshotLease>()
        var authorized = true
        var row = record(QbDistributionState.RESERVED)
        every { lease.snapshotId } returns snapshot; every { lease.leaseId } returns token; every { lease.manifest } returns manifest
        every { lease.retainForClient(policy.clientId, id) } just Runs; every { lease.verifyForClient(policy.cacheIdentity) } just Runs
        every { journal.get(id) } answers { row }
        every { journal.transition(id, setOf(QbDistributionState.RESERVED), QbDistributionState.CREATING, any(), any(), any()) } answers {
            row = row.copy(state = QbDistributionState.CREATING); row
        }
        every { api.verifyClient() } answers { authorized = false }
        val lifecycle = QbDistributionLifecycle(policy, api, journal) { check(authorized) { "Synthetic caller revoked" } }
        assertFailsWith<IllegalStateException> { lifecycle.create(id, lease) }
        assertEquals(QbDistributionState.CREATING, row.state)
        verify(exactly = 0) { api.create(any(), any()) }
        verify(exactly = 0) { lease.close() }
        verify(exactly = 0) { lease.releaseAfterClientRemoval(any(), any()) }
    }

    @Test fun `revocation while creator ownership proof waits prevents deletion and lease release`() {
        val api = mockk<QbDistributionApi>(); val journal = mockk<QbDistributionJournal>(); val lease = mockk<OwnedSnapshotLease>()
        var authorized = true
        val row = record(QbDistributionState.REMOVING)
        val task = JsonMapper.builder().build().readTree("""{"taskID":"task","status":"Finished"}""")
        every { lease.snapshotId } returns snapshot; every { lease.leaseId } returns token; every { lease.manifest } returns manifest
        every { lease.retainForClient(policy.clientId, id) } just Runs
        every { journal.get(id) } returns row
        every { api.verifyClient() } just Runs; every { api.find(requireNotNull(row.infoHash)) } returns null
        every { api.tasks() } returns listOf(task)
        every { api.ownedTask("task", snapshot, id) } answers { authorized = false; task }
        val lifecycle = QbDistributionLifecycle(policy, api, journal) { check(authorized) { "Synthetic caller revoked" } }
        assertFailsWith<IllegalStateException> { lifecycle.reconcileRemoval(id, lease) }
        verify(exactly = 0) { api.deleteTask(any()) }
        verify(exactly = 0) { lease.releaseAfterClientRemoval(any(), any()) }
        verify(exactly = 0) { journal.transition(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `one acknowledged add stays ADDING while owned client checks and confirms only actual stopped state`() {
        val api = mockk<QbDistributionApi>(); val journal = mockk<QbDistributionJournal>(); val lease = mockk<OwnedSnapshotLease>()
        val payload = byteArrayOf(7)
        val actualManifest = manifest.copy(members = listOf(org.gameyfin.snapshot.SnapshotMember("base.bin", 1,
            QbDistributionPolicy.digest(payload), "copied", "original")))
        val info = mapOf("files" to listOf(mapOf("length" to 1L, "path" to listOf("base.bin"))), "name" to "data",
            "piece length" to 16384L, "pieces" to java.security.MessageDigest.getInstance("SHA-1").digest(payload), "private" to 1L)
        val metadata = bencode(mapOf("announce" to policy.approvedTrackers.single(), "info" to info))
        val hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(bencode(info)))
        var row = record(QbDistributionState.VALIDATED).copy(infoHash = hash, metadata = metadata, manifestDigest = SnapshotProtocol.digest(actualManifest.bytes()))
        var added = false; var state = "checkingResumeData"; var category = policy.category
        val mapper = JsonMapper.builder().build()
        every { lease.snapshotId } returns snapshot; every { lease.leaseId } returns token; every { lease.manifest } returns actualManifest
        every { lease.retainForClient(policy.clientId, id) } just Runs; every { lease.verifyForClient(policy.cacheIdentity) } just Runs
        every { lease.openMember("base.bin") } answers { payload.inputStream() }
        every { journal.get(id) } answers { row }
        every { journal.transition(id, any(), any(), any(), any(), any()) } answers { row = row.copy(state = thirdArg()); row }
        every { api.verifyClient() } just Runs
        every { api.find(hash) } answers { if (!added) null else mapper.readTree("""{"category":"$category","tags":"${policy.tag(id)}","save_path":"${policy.savePath(snapshot)}","auto_tmm":false,"downloaded":0,"state":"$state"}""") }
        every { api.addStopped(snapshot, id, any()) } answers { added = true }
        every { api.properties(hash) } returns mapper.readTree("""{"comment":"${policy.comment(id)}","is_private":true}""")
        every { api.export(hash) } returns metadata
        every { api.files(hash) } returns listOf(mapper.readTree("""{"name":"data/base.bin","size":1,"priority":1,"progress":1}"""))
        every { api.webSeeds(hash) } returns emptyList()
        every { api.trackers(hash) } returns listOf(mapper.readTree("""{"url":"${policy.approvedTrackers.single()}"}"""))
        val lifecycle = QbDistributionLifecycle(policy, api, journal)
        assertEquals(QbDistributionState.ADDING, lifecycle.addStopped(id, lease).state)
        category = "foreign"
        assertFailsWith<IllegalArgumentException> { lifecycle.reconcileAdded(id, lease) }
        category = policy.category
        for (checking in listOf("checkingResumeData", "checkingDL", "checkingUP")) {
            state = checking
            assertFailsWith<QbDistributionPendingConfirmation> { lifecycle.reconcileAdded(id, lease) }
            assertEquals(QbDistributionState.ADDING, row.state)
        }
        state = "downloading"
        assertFailsWith<IllegalStateException> { lifecycle.reconcileAdded(id, lease) }
        assertEquals(QbDistributionState.ADDING, row.state)
        state = "stoppedUP"
        assertEquals(QbDistributionState.STOPPED, lifecycle.reconcileAdded(id, lease).state)
        verify(exactly = 1) { api.addStopped(snapshot, id, any()) }
        verify(exactly = 0) { api.start(any()) }
        verify(exactly = 0) { api.recheck(any()) }
    }

    @Test fun `shared proof binds complete owned seed and rejects foreign incomplete substituted or revoked observations without mutations`() {
        val api = mockk<QbDistributionApi>(); val journal = mockk<QbDistributionJournal>(); val lease = mockk<OwnedSnapshotLease>()
        val payload = byteArrayOf(7)
        val actualManifest = manifest.copy(members = listOf(org.gameyfin.snapshot.SnapshotMember("base.bin", 1,
            QbDistributionPolicy.digest(payload), "copied", "original")))
        val info = mapOf("files" to listOf(mapOf("length" to 1L, "path" to listOf("base.bin"))), "name" to "data",
            "piece length" to 16384L, "pieces" to java.security.MessageDigest.getInstance("SHA-1").digest(payload), "private" to 1L)
        val metadata = bencode(mapOf("announce" to policy.approvedTrackers.single(), "info" to info))
        val hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(bencode(info)))
        val row = record(QbDistributionState.SEEDING).copy(infoHash = hash, metadata = metadata, manifestDigest = SnapshotProtocol.digest(actualManifest.bytes()))
        var category = policy.category; var savePath = policy.savePath(snapshot); var progress = 1.0; var authorized = true
        var exported = metadata
        val mapper = JsonMapper.builder().build()
        every { lease.snapshotId } returns snapshot; every { lease.leaseId } returns token; every { lease.manifest } returns actualManifest
        every { lease.retainForClient(policy.clientId, id) } just Runs; every { lease.verifyForClient(policy.cacheIdentity) } just Runs
        every { lease.openMember("base.bin") } answers { payload.inputStream() }
        every { journal.get(id) } returns row
        every { api.verifyClient() } just Runs
        every { api.find(hash) } answers { mapper.readTree("""{"category":"$category","tags":"${policy.tag(id)}","save_path":"$savePath","auto_tmm":false,"downloaded":0,"state":"stalledUP","progress":$progress,"amount_left":0}""") }
        every { api.properties(hash) } returns mapper.readTree("""{"comment":"${policy.comment(id)}","is_private":true}""")
        every { api.export(hash) } answers { exported }
        every { api.files(hash) } returns listOf(mapper.readTree("""{"name":"data/base.bin","size":1,"priority":1,"progress":1}"""))
        every { api.webSeeds(hash) } returns emptyList()
        val trackers = listOf(mapper.readTree("""{"url":"${policy.approvedTrackers.single()}"}"""))
        every { api.trackers(hash) } returns trackers
        val lifecycle = QbDistributionLifecycle(policy, api, journal) { check(authorized) { "Synthetic caller revoked" } }
        assertEquals(QbDistributionSharedProof(id, policy.clientId, snapshot, token, row.manifestDigest,
            policy.mappingDigest, hash, QbDistributionPolicy.digest(metadata)), lifecycle.proveSharedSeed(id, lease))
        category = "foreign"; assertFails { lifecycle.proveSharedSeed(id, lease) }; category = policy.category
        savePath = "/foreign/snapshot"; assertFails { lifecycle.proveSharedSeed(id, lease) }; savePath = policy.savePath(snapshot)
        progress = 0.5; assertFails { lifecycle.proveSharedSeed(id, lease) }; progress = 1.0
        exported = bencode(mapOf("announce" to policy.approvedTrackers.single(), "info" to info, "comment" to "substituted artifact"))
        assertFails { lifecycle.proveSharedSeed(id, lease) }; exported = metadata
        every { api.trackers(hash) } answers { authorized = false; trackers }
        assertFails { lifecycle.proveSharedSeed(id, lease) }
        verify(exactly = 0) { api.create(any(), any()) }
        verify(exactly = 0) { api.addStopped(any(), any(), any()) }
        verify(exactly = 0) { api.start(any()) }
        verify(exactly = 0) { api.stop(any()) }
        verify(exactly = 0) { api.remove(any()) }
        verify(exactly = 0) { api.deleteTask(any()) }
        verify(exactly = 0) { journal.transition(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { lease.releaseAfterClientRemoval(any(), any()) }
    }
}
