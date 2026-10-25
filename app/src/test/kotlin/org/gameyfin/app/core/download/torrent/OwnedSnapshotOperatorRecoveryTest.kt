package org.gameyfin.app.core.download.torrent

import org.gameyfin.snapshot.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.*

@DataJpaTest
@Import(OwnedSnapshotRegistry::class, OwnedSnapshotOperatorRecovery::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OwnedSnapshotOperatorRecoveryTest @Autowired internal constructor(
    private val registry: OwnedSnapshotRegistry, private val recovery: OwnedSnapshotOperatorRecovery,
    private val receipts: jakarta.persistence.EntityManager) {
    @AfterEach fun clearAuth() { SecurityContextHolder.clearContext() }
    private fun admin() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            "fixture-admin", "unused", listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
    }
    private fun fixture(): Pair<SnapshotRecordView, SnapshotProcessIdentity> {
        val worker = SnapshotProcessIdentity("a".repeat(64), 200, 1234)
        val request = SnapshotCapture(UUID.randomUUID(), UUID.randomUUID(), "/source", "/cache", "root",
            listOf(SnapshotSelection("base.bin", "base")), 1024, 8, 0)
        val record = registry.reserve(request.snapshotId, request.operation, 1, 2, request.bytes(), worker, worker)
        registry.begin(record.id)
        registry.ready(record.id, SnapshotManifest(record.id, record.operation, record.requestDigest, "root", "directory",
            listOf(SnapshotMember("base", 1, "a".repeat(64), "copy", "source"))).bytes())
        return record to worker
    }
    @Test fun `admin exact receipt can reconcile foreign acquisition but cannot be replayed or rebound`() {
        val (record, worker) = fixture()
        val proof = SnapshotExitAttestation(UUID.randomUUID(), record.id, record.operation,
            SnapshotRecoveryTarget.ACQUISITION, null, null, worker, "f".repeat(64), true)
        assertFails { recovery.attestExited(proof) }
        admin()
        assertFails { recovery.attestExited(proof.copy(expected = worker.copy(pid = 201))) }
        assertNull(registry.get(record.id).workerExitProvedAt)
        recovery.attestExited(proof)
        assertNotNull(registry.get(record.id).workerExitProvedAt)
        assertFails { recovery.attestExited(proof) }
        assertNotNull(receipts.find(OwnedSnapshotRecoveryReceipt::class.java, proof.receiptId))
    }
    @Test fun `consumer attestation retains external lease and requires explicit evidence acknowledgment`() {
        val (record, worker) = fixture(); admin()
        val token = registry.lease(record.id, worker); registry.bindLeaseWorker(record.id, token, worker)
        val client = UUID.randomUUID(); val ownership = UUID.randomUUID()
        val claim = registry.bindClient(record.id, token, client, ownership)
        val proof = SnapshotExitAttestation(UUID.randomUUID(), record.id, record.operation,
            SnapshotRecoveryTarget.CONSUMER, token, claim, worker, "f".repeat(64), false)
        assertFails { recovery.attestExited(proof) }
        assertFails { registry.releaseClient(record.id, token, client, ownership) }
        recovery.attestExited(proof.copy(confirmsOriginalContainerAndAllItsWorkersExited = true))
        assertEquals(1, registry.activeLeases(record.id))
        assertFails { recovery.attestExited(proof.copy(receiptId = UUID.randomUUID(), target = SnapshotRecoveryTarget.SESSION,
            consumerId = null, confirmsOriginalContainerAndAllItsWorkersExited = true)) }
        registry.releaseClient(record.id, token, client, ownership)
        assertEquals(0, registry.activeLeases(record.id))
    }
}
