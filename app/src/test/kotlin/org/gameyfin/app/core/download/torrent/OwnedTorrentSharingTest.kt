package org.gameyfin.app.core.download.torrent

import io.mockk.*
import org.gameyfin.snapshot.SnapshotProtocol
import org.gameyfin.snapshot.SnapshotSelection
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.init.ScriptUtils
import java.sql.DriverManager
import java.util.Optional
import java.util.UUID
import java.nio.file.Path
import kotlin.test.*

/** Coordinator orchestration only; real descriptor/qB parity belongs to the opt-in pair fixture. */
class OwnedTorrentSharingTest {
    private class Fixture {
        val settings = mockk<OwnedTorrentSettings>()
        val owners = mockk<OwnedTorrentSharedOwnerRepository>()
        val refs = mockk<OwnedTorrentSharedReferenceRepository>()
        val requests = mockk<OwnedTorrentRequestRepository>()
        val intents = mockk<QbDistributionIntentRepository>()
        val ownerRows = mutableMapOf<UUID, OwnedTorrentSharedOwner>()
        val refRows = mutableMapOf<UUID, OwnedTorrentSharedReference>()
        val requestRows = mutableMapOf<UUID, OwnedTorrentRequestRow>()
        val intentRows = mutableMapOf<UUID, QbDistributionIntent>()
        val locks = mutableListOf<String>()
        val service = OwnedTorrentSharing(settings, owners, refs, requests, intents)
        val key = "a".repeat(64)
        val profile = "b".repeat(64)
        val membership = "1".repeat(64)
        init {
            every { settings.withWriterLock<Any>(any(), any()) } answers {
                locks.add("settings"); secondArg<(OwnedTorrentRuntime?) -> Any>().invoke(null)
            }
            every { owners.locked(any()) } answers { locks.add("owner"); ownerRows[firstArg()] }
            every { owners.findById(any()) } answers { Optional.ofNullable(ownerRows[firstArg()]) }
            every { owners.existsById(any()) } answers { ownerRows.containsKey(firstArg()) }
            every { owners.candidates(any(), any()) } answers {
                ownerRows.values.filter { it.selectionKey == firstArg<String>() && it.state != "RETIRED" }.sortedBy { it.id }
            }
            every { owners.saveAndFlush(any()) } answers { firstArg<OwnedTorrentSharedOwner>().also { ownerRows[it.id] = it } }
            every { refs.locked(any()) } answers { locks.add("ref"); refRows[firstArg()] }
            every { refs.findById(any()) } answers { Optional.ofNullable(refRows[firstArg()]) }
            every { refs.existsById(any()) } answers { refRows.containsKey(firstArg()) }
            every { refs.lockedForOwner(any()) } answers { locks.add("ref"); refRows.values.filter { it.ownerId == firstArg<UUID>() }.sortedBy { it.requestId } }
            every { refs.saveAndFlush(any()) } answers { firstArg<OwnedTorrentSharedReference>().also { refRows[it.requestId] = it } }
            every { requests.locked(any()) } answers { locks.add("request"); requestRows[firstArg()] }
            every { requests.saveAndFlush(any()) } answers { firstArg<OwnedTorrentRequestRow>() }
            every { intents.findLockedById(any()) } answers { intentRows[firstArg()] }
        }
        fun request() = UUID.randomUUID().also { requestRows[it] = OwnedTorrentRequestRow(id = it, state = "RUNNING") }
        fun active(): OwnedTorrentSharedCandidate {
            val id = request()
            service.begin(id, key, profile, 1, emptySet()) {}
            val metadata = byteArrayOf(1, 2, 3)
            val proof = QbDistributionSharedProof(id, UUID.randomUUID(), id, UUID.randomUUID(),
                "c".repeat(64), "d".repeat(64), "e".repeat(40), SnapshotProtocol.digest(metadata))
            requestRows.getValue(id).apply { snapshotId = id; distributionId = id }
            intentRows[id] = QbDistributionIntent(id, proof.clientId, id, proof.leaseId, proof.manifestDigest,
                proof.mappingDigest, QbDistributionState.SEEDING, infoHash = proof.infoHash, metadata = metadata)
            service.publish(id, proof, membership, 1) {}
            return service.owner(id)!!
        }
        fun attach(candidate: OwnedTorrentSharedCandidate): UUID = request().also {
            service.attach(it, candidate, candidate.proof, "f".repeat(64), "MATCH", membership, 1) {}
        }
    }

    @Test fun `two durable references share exact tuple and only last release fences cleanup`() {
        val f = Fixture(); val owner = f.active(); val second = f.attach(owner)
        f.locks.clear()
        assertFalse(f.service.release(owner.ownerId) {}!!.last)
        assertEquals("RELEASED", f.requestRows.getValue(owner.ownerId).state)
        assertEquals("ACTIVE", f.ownerRows.getValue(owner.ownerId).state)
        assertEquals(listOf("settings", "owner", "ref", "request"), f.locks)
        assertFalse(f.service.release(owner.ownerId) {}!!.last) // duplicate nonlast does not stop
        val last = f.service.release(second) {}!!
        assertTrue(last.last); assertEquals(owner.ownerId, last.ownerId)
        assertEquals("RETIRING", ownedTorrentSharedVisibleState("RETIRING", false, true))
        assertEquals("NEEDS_OPERATOR", ownedTorrentSharedVisibleState("RETIRING", false, false))
        assertEquals("RETIRING", f.ownerRows.getValue(owner.ownerId).state)
        assertFailsWith<IllegalArgumentException> { f.attach(owner) }
        f.service.retired(owner.ownerId) {}
        assertEquals("RETIRED", f.requestRows.getValue(owner.ownerId).state)
        assertEquals("RETIRED", f.requestRows.getValue(second).state)
    }

    @Test fun `changed seed proof current intent and denied captured actor never attach`() {
        val f = Fixture(); val owner = f.active(); val next = f.request()
        assertFailsWith<IllegalArgumentException> {
            f.service.attach(next, owner, owner.proof.copy(leaseId = UUID.randomUUID()), "f".repeat(64), "MATCH", f.membership, 1) {}
        }
        f.intentRows.getValue(owner.ownerId).state = QbDistributionState.STOPPING
        assertFailsWith<IllegalArgumentException> {
            f.service.attach(next, owner, owner.proof, "f".repeat(64), "MATCH", f.membership, 1) {}
        }
        f.intentRows.getValue(owner.ownerId).state = QbDistributionState.SEEDING
        assertFailsWith<IllegalStateException> {
            f.service.attach(next, owner, owner.proof, "f".repeat(64), "MATCH", f.membership, 1) { error("Synthetic revoked actor") }
        }
        assertNull(f.refRows[next]); assertEquals("RUNNING", f.requestRows.getValue(next).state)
    }

    @Test fun `same payload changed source preserves owner receipt and stores new reference provenance`() {
        val f = Fixture(); val owner = f.active(); val next = f.request()
        val original = f.refRows.getValue(owner.ownerId).verificationDigest
        f.service.attach(next, owner, owner.proof, "f".repeat(64), "SAME_CONTENT_DIFFERENT_SOURCE", f.membership, 1) {}
        assertEquals(owner, f.service.owner(owner.ownerId))
        assertEquals(original, f.refRows.getValue(owner.ownerId).verificationDigest)
        assertNotEquals(original, f.refRows.getValue(next).verificationDigest)
        assertEquals("SAME_CONTENT_DIFFERENT_SOURCE", f.refRows.getValue(next).verificationKind)
        assertEquals("f".repeat(64), f.refRows.getValue(next).sourceDigest)
        val root = Path.of("fixture-root").toAbsolutePath().normalize()
        val canonicalOriginal = OwnedSnapshotLogicalSelection(1, 2, listOf(3), root, listOf(root.resolve("a.bin")),
            listOf(SnapshotSelection("a.bin", "content-1/a.bin")))
        val changed = canonicalOriginal.copy(selections = listOf(SnapshotSelection("moved/a.bin", "content-1/a.bin")))
        assertNotEquals(ownedTorrentSharedMembershipDigest(canonicalOriginal), ownedTorrentSharedMembershipDigest(changed))
        assertEquals(ownedTorrentSharedSelectionKey(canonicalOriginal, f.profile), ownedTorrentSharedSelectionKey(changed, f.profile))
    }

    @Test fun `preparing restart fences duplicate copy and exact safe rejection drains owner`() {
        val f = Fixture(); val first = f.request()
        f.service.begin(first, f.key, f.profile, 1, emptySet()) {}
        assertFailsWith<IllegalArgumentException> { f.service.candidates(f.key) }
        assertFailsWith<IllegalArgumentException> { f.service.begin(f.request(), f.key, f.profile, 1, emptySet()) {} }
        assertFailsWith<IllegalStateException> { f.service.rejectResourceFree(first) { error("Synthetic failed absence/auth proof") } }
        assertEquals("PREPARING", f.ownerRows.getValue(first).state)
        assertEquals("RUNNING", f.requestRows.getValue(first).state)
        f.service.rejectResourceFree(first) {} // callback already proved both planned resources absent
        assertEquals("REJECTED", f.requestRows.getValue(first).state)
        assertEquals("RETIRED", f.ownerRows.getValue(first).state)
        assertTrue(f.service.candidates(f.key).isEmpty())
        val second = f.request(); f.service.begin(second, f.key, f.profile, 1, emptySet()) {}
        f.service.acquisitionFailed(second, null)
        assertEquals("UNCERTAIN", f.requestRows.getValue(second).state)
        assertFailsWith<IllegalArgumentException> { f.service.candidates(f.key) }
        f.service.rejectResourceFree(second) {}
        assertTrue(f.service.candidates(f.key).isEmpty())
    }

    @Test fun `only observed mismatched generations permit another preparing owner`() {
        val f = Fixture(); val owner = f.active()
        assertFailsWith<IllegalArgumentException> { f.service.begin(f.request(), f.key, f.profile, 1, emptySet()) {} }
        val next = f.request(); f.service.begin(next, f.key, f.profile, 1, setOf(owner.ownerId)) {}
        assertEquals("ACTIVE", f.ownerRows.getValue(owner.ownerId).state)
        assertEquals("PREPARING", f.ownerRows.getValue(next).state)
    }

    @Test fun `metadata requires active ref owner current intent and fresh authority`() {
        val f = Fixture(); val owner = f.active(); val second = f.attach(owner)
        assertContentEquals(byteArrayOf(1, 2, 3), f.service.metadata(second, 1) {})
        assertFailsWith<IllegalStateException> { f.service.metadata(second, 1) { error("Synthetic revoked actor") } }
        assertFailsWith<IllegalArgumentException> { f.service.metadata(second, 1) { require(it == "2".repeat(64)) } }
        f.service.release(second) {}
        assertFailsWith<IllegalArgumentException> { f.service.metadata(second, 1) {} }
    }

    @Test fun `migration state constraints survive creator connection close and reject illegal updates`() {
        val url = "jdbc:h2:mem:shared-${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
        val id = UUID.randomUUID()
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { sql ->
                sql.execute("CREATE TABLE owned_torrent_request(id UUID PRIMARY KEY)")
                sql.execute("CREATE TABLE owned_snapshot_record(id UUID PRIMARY KEY)")
                sql.execute("CREATE TABLE qb_distribution_intent(id UUID PRIMARY KEY)")
                sql.execute("INSERT INTO owned_torrent_request VALUES ('$id')")
            }
            ScriptUtils.executeSqlScript(connection, ClassPathResource("db/migration/V2.4.7.5__shared_owned_torrent.sql"))
        }
        try {
            DriverManager.getConnection(url).use { connection -> connection.createStatement().use { sql ->
                sql.execute("INSERT INTO owned_torrent_shared_owner(id,selection_key,profile_digest,state) VALUES ('$id','${"a".repeat(64)}','${"b".repeat(64)}','PREPARING')")
                sql.execute("UPDATE owned_torrent_shared_owner SET state='UNCERTAIN' WHERE id='$id'")
                assertFails { sql.execute("UPDATE owned_torrent_shared_owner SET state='INVALID' WHERE id='$id'") }
                sql.execute("INSERT INTO owned_torrent_shared_reference VALUES ('$id','$id','ACTIVE','${"a".repeat(64)}',CURRENT_TIMESTAMP,'${"b".repeat(64)}','MATCH','${"c".repeat(64)}')")
                sql.execute("UPDATE owned_torrent_shared_reference SET state='RELEASED' WHERE request_id='$id'")
                assertFails { sql.execute("UPDATE owned_torrent_shared_reference SET state='INVALID' WHERE request_id='$id'") }
            } }
        } finally { DriverManager.getConnection(url).use { it.createStatement().use { sql -> sql.execute("DROP ALL OBJECTS") } } }
    }
}
