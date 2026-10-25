package org.gameyfin.app.core.download.torrent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import java.util.UUID
import io.mockk.*

/** These pure decisions are the actual facade routing/admission/failure functions, not parallel mocks. */
class OwnedTorrentRecoveryTest {
    @Test fun `removed journal with release pending finalizes exact release before snapshot retirement`() {
        assertEquals(OwnedTorrentRetirement.FINALIZE_REMOVAL, ownedTorrentRetirement(QbDistributionState.REMOVED, false))
        assertEquals(OwnedTorrentRetirement.RETIRE_SNAPSHOT, ownedTorrentRetirement(QbDistributionState.REMOVED, true))
        assertEquals(OwnedTorrentRetirement.FINALIZE_CANCELLATION, ownedTorrentRetirement(QbDistributionState.CANCELLED, false))
    }
    @Test fun `partial acquisition has reachable descriptor retirement and uncertain pre-add states cannot replay`() {
        assertEquals(OwnedTorrentRetirement.RETIRE_SNAPSHOT, ownedTorrentRetirement(null, false))
        for (state in listOf(QbDistributionState.RESERVED, QbDistributionState.CREATED, QbDistributionState.VALIDATED))
            assertEquals(OwnedTorrentRetirement.CANCEL_BEFORE_ADD, ownedTorrentRetirement(state, false))
        for (state in listOf(QbDistributionState.CREATING, QbDistributionState.ADDING))
            assertEquals(OwnedTorrentRetirement.RECONCILE_REQUIRED, ownedTorrentRetirement(state, false))
    }
    @Test fun `only proven pre-acquisition or safely cleaned failure is rejected`() {
        assertEquals("REJECTED", ownedTorrentFailureState(false, false))
        assertEquals("REJECTED", ownedTorrentFailureState(true, true))
        assertEquals("UNCERTAIN", ownedTorrentFailureState(true, false))
    }
    @Test fun `retained uncertainty consumes capacity without globally freezing unrelated admission`() {
        assertTrue(ownedTorrentAdmissionOpen(0)); assertTrue(ownedTorrentAdmissionOpen(1)); assertTrue(ownedTorrentAdmissionOpen(63))
        assertFalse(ownedTorrentAdmissionOpen(64)); assertFalse(ownedTorrentAdmissionOpen(-1))
    }
    @Test fun `durable planned ID recovers a lost linkage but missing and mismatched rows cannot be adopted`() {
        val request = UUID(0, 1); val snapshot = UUID(0, 2); val client = UUID(0, 3)
        val found = QbDistributionRecord(request, client, snapshot, UUID(0, 4), "a".repeat(64), "b".repeat(64),
            QbDistributionState.RESERVED, null, null, null)
        assertEquals(found, ownedTorrentLinkedIntent(request, snapshot, null, client, found.mappingDigest) { found })
        assertNull(ownedTorrentLinkedIntent(request, snapshot, null, client, found.mappingDigest) { null })
        assertFailsWith<IllegalArgumentException> {
            ownedTorrentLinkedIntent(request, snapshot, request, client, found.mappingDigest) { null }
        }
        for (mismatch in listOf(found.copy(id = UUID(0, 9)), found.copy(snapshotId = UUID(0, 9)),
            found.copy(clientId = UUID(0, 9)), found.copy(mappingDigest = "c".repeat(64)))) {
            assertFailsWith<IllegalArgumentException> {
                ownedTorrentLinkedIntent(request, snapshot, null, client, found.mappingDigest) { mismatch }
            }
        }
        assertFailsWith<IllegalStateException> {
            ownedTorrentLinkedIntent(request, snapshot, null, client, found.mappingDigest) { error("Synthetic unknown commit lookup") }
        }
    }
    @Test fun `planned snapshot repairs a lost callback link only with immutable request digest`() {
        val request = request()
        val snapshot = mockk<SnapshotRecordView>()
        every { snapshot.id } returns request.id
        every { snapshot.hostRequestDigest } returns request.hostRequestDigest
        assertEquals(request.id, ownedTorrentSnapshotBinding(request, snapshot))
        assertNull(ownedTorrentSnapshotBinding(request, null))
        every { snapshot.hostRequestDigest } returns "e".repeat(64)
        assertFailsWith<IllegalArgumentException> { ownedTorrentSnapshotBinding(request, snapshot) }
        request.snapshotId = request.id
        assertFailsWith<IllegalArgumentException> { ownedTorrentSnapshotBinding(request, null) }
        request.snapshotId = null; request.profile = "changed-profile"
        assertFailsWith<IllegalArgumentException> { ownedTorrentSnapshotBinding(request, null) }
    }
    @Test fun `queued restart cancellation requires creator exit and both successful absent lookups`() {
        val request = request()
        val probe = probe("a".repeat(64), SnapshotProcessObservation.Absent)
        assertEquals(OwnedTorrentCreatorExit.LOCAL_EXIT, ownedTorrentResourceFreeCancellation(request, probe, null, { null }, { null }))
        assertEquals("QUEUED", request.state) // The facade's row-locked commit, not the proof helper, performs rejection.
        assertFailsWith<IllegalStateException> {
            ownedTorrentResourceFreeCancellation(request, probe, null, { error("Synthetic failed lookup") }, { null })
        }
        assertFailsWith<IllegalArgumentException> {
            ownedTorrentResourceFreeCancellation(request, probe, null, { mockk<SnapshotRecordView>() }, { null })
        }
        assertEquals("QUEUED", request.state)
        assertFailsWith<IllegalStateException> {
            ownedTorrentResourceFreeCancellation(request, probe, null, { null }, { error("Synthetic intent lookup failure") })
        }
    }
    @Test fun `foreign receipt never overrides known local live or unknown creator and is tuple bound`() {
        val identity = SnapshotProcessIdentity("a".repeat(64), 42, 100)
        val receipt = OwnedTorrentRequestExitAttestation(identity.scope, identity.pid, identity.startedMillis,
            "b".repeat(64), true, true, "c".repeat(64))
        assertNull(ownedTorrentCreatorExit(identity, probe(identity.scope, SnapshotProcessObservation.Present(100)), receipt))
        assertNull(ownedTorrentCreatorExit(identity, probe(identity.scope, SnapshotProcessObservation.Unknown), receipt))
        assertNull(ownedTorrentCreatorExit(identity, probe(null, SnapshotProcessObservation.Absent), receipt))
        assertEquals(OwnedTorrentCreatorExit.LOCAL_EXIT,
            ownedTorrentCreatorExit(identity, probe(identity.scope, SnapshotProcessObservation.Present(101)), null))
        assertEquals(OwnedTorrentCreatorExit.FOREIGN_ATTESTED,
            ownedTorrentCreatorExit(identity, probe("d".repeat(64), SnapshotProcessObservation.Unknown), receipt))
        assertFailsWith<IllegalArgumentException> {
            ownedTorrentCreatorExit(identity, probe("d".repeat(64), SnapshotProcessObservation.Absent), receipt.copy(expectedPid = 43))
        }
    }
    private fun request() = OwnedTorrentRequestRow(id = UUID(0, 1), selection = "canonical-selection", profile = "immutable-profile",
        creatorScope = "a".repeat(64), creatorPid = 42, creatorStartedMillis = 100).also { it.hostRequestDigest = ownedTorrentHostRequestDigest(it) }
    private fun probe(currentScope: String?, observation: SnapshotProcessObservation) = object : SnapshotProcessProbe {
        override fun scope() = currentScope
        override fun observe(pid: Long) = observation
    }
}
