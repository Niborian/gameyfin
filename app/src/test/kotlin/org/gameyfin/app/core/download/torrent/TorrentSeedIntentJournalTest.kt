package org.gameyfin.app.core.download.torrent

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
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
@Import(TorrentSeedIntentJournal::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TorrentSeedIntentJournalTest @Autowired constructor(
    private val journal: TorrentSeedIntentJournal, private val repository: TorrentSeedIntentRepository,
    private val manager: PlatformTransactionManager, private val entityManager: EntityManager
) {
    private val digest = "a".repeat(64)
    private fun reserve() = journal.reserve(UUID.randomUUID(), UUID.randomUUID(), digest)

    @Test fun `committed intent survives service recreation and cannot duplicate creation`() {
        val reserved = reserve()
        val started = journal.beginCreation(reserved.id)
        assertNotNull(started.operationToken)
        TransactionTemplate(manager).execute { entityManager.clear() }
        val restarted = TorrentSeedIntentJournal(repository, manager)
        val recovered = restarted.reserve(started.clientId, started.snapshotId, digest)
        assertEquals(started, recovered)
        assertFailsWith<IllegalArgumentException> { restarted.beginCreation(started.id) }
        assertFailsWith<IllegalArgumentException> { restarted.reserve(started.clientId, started.snapshotId, "b".repeat(64)) }
    }

    @Test fun `uncertain outcome refuses retry and late completion`() {
        val started = journal.beginCreation(reserve().id)
        val token = requireNotNull(started.operationToken)
        journal.markUncertain(started.id, token)
        assertFailsWith<IllegalArgumentException> { journal.beginCreation(started.id) }
        assertFailsWith<IllegalArgumentException> { journal.recordMetadata(started.id, token, "owned-task", digest, 100) }
        assertEquals(TorrentSeedIntentState.CREATION_UNCERTAIN, repository.findById(started.id).orElseThrow().state)
    }

    @Test fun `metadata is bounded pending validation and cannot imply seeding`() {
        val started = journal.beginCreation(reserve().id)
        val token = requireNotNull(started.operationToken)
        assertFailsWith<IllegalArgumentException> { journal.recordMetadata(started.id, UUID.randomUUID(), "task", digest, 100) }
        assertFailsWith<IllegalArgumentException> { journal.recordMetadata(started.id, token, "../../foreign", digest, 100) }
        assertFailsWith<IllegalArgumentException> { journal.recordMetadata(started.id, token, "task", digest, 1024 * 1024 + 1L) }
        val recorded = journal.recordMetadata(started.id, token, "task", "b".repeat(64), 100)
        assertEquals(TorrentSeedIntentState.METADATA_PENDING_VALIDATION, recorded.state)
        assertEquals("b".repeat(64), recorded.metadataDigest)
        assertFailsWith<IllegalArgumentException> { journal.beginCreation(started.id) }
        assertFailsWith<IllegalArgumentException> { journal.refuseBeforeCreation(started.id) }
    }

    @Test fun `unknown intents and other client snapshots are never adopted`() {
        assertFailsWith<IllegalStateException> { journal.beginCreation(UUID.randomUUID()) }
        val first = reserve()
        val otherClient = journal.reserve(UUID.randomUUID(), first.snapshotId, digest)
        assertNotEquals(first.id, otherClient.id)
        val refused = journal.refuseBeforeCreation(first.id)
        assertEquals(TorrentSeedIntentState.REFUSED, refused.state)
        assertFailsWith<IllegalArgumentException> { journal.beginCreation(first.id) }
    }

    @Test fun `creation intent remains committed when an outer caller transaction rolls back`() {
        var committed: TorrentSeedIntentView? = null
        TransactionTemplate(manager).execute { outer ->
            committed = journal.beginCreation(reserve().id)
            outer.setRollbackOnly()
        }
        val expected = requireNotNull(committed)
        val restored = repository.findById(expected.id).orElseThrow()
        assertEquals(expected.operationToken, restored.operationToken)
        assertEquals(TorrentSeedIntentState.CREATION_IN_FLIGHT, restored.state)
    }

    @Test fun `concurrent callers commit only one creation operation token`() {
        val intent = reserve()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                executor.submit(Callable {
                    start.await()
                    try { journal.beginCreation(intent.id) }
                    catch (refusal: IllegalArgumentException) { null }
                })
            }
            start.countDown()
            val winners = futures.mapNotNull { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, winners.size)
            assertEquals(winners.single().operationToken, repository.findById(intent.id).orElseThrow().operationToken)
        } finally { executor.shutdownNow() }
    }
}
