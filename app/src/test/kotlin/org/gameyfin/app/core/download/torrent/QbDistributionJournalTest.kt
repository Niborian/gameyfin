package org.gameyfin.app.core.download.torrent

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.gameyfin.snapshot.SnapshotCapture
import org.gameyfin.snapshot.SnapshotManifest
import org.gameyfin.snapshot.SnapshotMember
import org.gameyfin.snapshot.SnapshotSelection
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

@DataJpaTest
@Import(QbDistributionJournal::class, OwnedSnapshotRegistry::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QbDistributionJournalTest @Autowired internal constructor(private val journal: QbDistributionJournal,
    private val repository: QbDistributionIntentRepository, private val manager: PlatformTransactionManager,
    private val registry: OwnedSnapshotRegistry) {
    private fun reserve(operationId: UUID? = null): QbDistributionRecord {
        val request = SnapshotCapture(UUID.randomUUID(), UUID.randomUUID(), "/source", "/cache", "cache",
            listOf(SnapshotSelection("base.bin", "content-1")), 1024, 8, 0)
        val snapshot = registry.reserve(request.snapshotId, request.operation, 42, 84, request.bytes())
        registry.begin(snapshot.id)
        val manifest = SnapshotManifest(snapshot.id, snapshot.operation, snapshot.requestDigest, "cache", "directory",
            listOf(SnapshotMember("content-1", 3, "a".repeat(64), "copy", "source"))).bytes()
        val ready = registry.ready(snapshot.id, manifest)
        return journal.reserve(UUID.randomUUID(), snapshot.id, registry.lease(snapshot.id), requireNotNull(ready.manifestDigest), "b".repeat(64), operationId)
    }
    @Test fun `exact retained lease and metadata survive recreation and cannot be rebound`() {
        val row = reserve()
        journal.transition(row.id, setOf(row.state), QbDistributionState.CREATING)
        journal.transition(row.id, setOf(QbDistributionState.CREATING), QbDistributionState.CREATED, taskId = "task")
        journal.transition(row.id, setOf(QbDistributionState.CREATED), QbDistributionState.VALIDATED, infoHash = "c".repeat(40), metadata = byteArrayOf(1, 2))
        val restarted = QbDistributionJournal(repository, manager)
        assertContentEquals(byteArrayOf(1, 2), restarted.get(row.id).metadata)
        assertFailsWith<IllegalArgumentException> { restarted.reserve(row.clientId, row.snapshotId, UUID.randomUUID(), row.manifestDigest, row.mappingDigest) }
        assertFailsWith<IllegalArgumentException> { restarted.transition(row.id, setOf(QbDistributionState.VALIDATED), QbDistributionState.ADDING, metadata = byteArrayOf(3)) }
        assertEquals(QbDistributionState.VALIDATED, restarted.get(row.id).state)
    }
    @Test fun `external call intent commits despite caller rollback and cannot duplicate`() {
        val row = reserve()
        TransactionTemplate(manager).execute { outer -> journal.transition(row.id, setOf(row.state), QbDistributionState.CREATING); outer.setRollbackOnly() }
        assertEquals(QbDistributionState.CREATING, journal.get(row.id).state)
        assertFailsWith<IllegalArgumentException> { journal.transition(row.id, setOf(QbDistributionState.RESERVED), QbDistributionState.CREATING) }
    }
    @Test fun `two callers cannot both own the next remote mutation`() {
        val row = reserve(); val latch = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val calls = (1..2).map { executor.submit(Callable { latch.await(); try {
                journal.transition(row.id, setOf(row.state), QbDistributionState.CREATING); true
            } catch (_: IllegalArgumentException) { false } }) }
            latch.countDown(); assertEquals(1, calls.count { it.get(15, TimeUnit.SECONDS) })
        }
    }
    @Test fun `reserved no-external-operation cancellation persists without invented task identity`() {
        val row = reserve()
        assertNull(row.taskId)
        journal.transition(row.id, setOf(QbDistributionState.RESERVED), QbDistributionState.CANCELLING)
        val cancelled = journal.transition(row.id, setOf(QbDistributionState.CANCELLING), QbDistributionState.CANCELLED)
        assertEquals(QbDistributionState.CANCELLED, cancelled.state)
        assertNull(cancelled.taskId); assertNull(cancelled.infoHash); assertNull(cancelled.metadata)
        assertEquals(row.leaseId, QbDistributionJournal(repository, manager).get(row.id).leaseId)
    }
    @Test fun `planned request identity is durable and cannot adopt an existing differently owned intent`() {
        val planned = UUID.randomUUID(); val row = reserve(planned)
        assertEquals(planned, row.id)
        assertEquals(row.id, journal.reserve(row.clientId, row.snapshotId, row.leaseId, row.manifestDigest, row.mappingDigest, planned).id)
        assertFailsWith<IllegalArgumentException> {
            journal.reserve(row.clientId, row.snapshotId, row.leaseId, row.manifestDigest, row.mappingDigest, UUID.randomUUID())
        }
        assertEquals(planned, journal.find(planned)?.id)
        assertNull(journal.find(UUID.randomUUID()))
    }
    @Test fun `intent discovery does not convert persistence failure into missing permission`() {
        val failing = mockk<QbDistributionIntentRepository>()
        every { failing.findById(any()) } throws IllegalStateException("Synthetic persistence outage")
        assertFailsWith<IllegalStateException> { QbDistributionJournal(failing, manager).find(UUID.randomUUID()) }
    }
}
