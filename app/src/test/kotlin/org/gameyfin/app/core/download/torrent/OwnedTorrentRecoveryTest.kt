package org.gameyfin.app.core.download.torrent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import java.util.UUID

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
}
