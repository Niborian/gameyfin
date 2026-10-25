package org.gameyfin.app.core.download.torrent

import java.util.UUID

internal enum class OwnedTorrentRetirement {
    RETIRE_SNAPSHOT, FINALIZE_REMOVAL, FINALIZE_CANCELLATION, CANCEL_BEFORE_ADD,
    CONFIRM_CANCELLATION, STOP_AND_REMOVE, RECONCILE_REQUIRED
}

/** Actual facade routing: terminal journal state alone never proves primary lease release. */
internal fun ownedTorrentRetirement(state: QbDistributionState?, clientLeaseReleased: Boolean): OwnedTorrentRetirement = when (state) {
    null -> OwnedTorrentRetirement.RETIRE_SNAPSHOT
    QbDistributionState.REMOVED -> if (clientLeaseReleased) OwnedTorrentRetirement.RETIRE_SNAPSHOT else OwnedTorrentRetirement.FINALIZE_REMOVAL
    QbDistributionState.CANCELLED -> if (clientLeaseReleased) OwnedTorrentRetirement.RETIRE_SNAPSHOT else OwnedTorrentRetirement.FINALIZE_CANCELLATION
    QbDistributionState.CANCELLING -> OwnedTorrentRetirement.CONFIRM_CANCELLATION
    QbDistributionState.RESERVED, QbDistributionState.CREATED, QbDistributionState.VALIDATED -> OwnedTorrentRetirement.CANCEL_BEFORE_ADD
    QbDistributionState.CREATING, QbDistributionState.ADDING, QbDistributionState.UNCERTAIN -> OwnedTorrentRetirement.RECONCILE_REQUIRED
    else -> OwnedTorrentRetirement.STOP_AND_REMOVE
}

/** Retained uncertainty is charged by the snapshot registry, not a permanent global admission lock. */
internal fun ownedTorrentAdmissionOpen(retainedRequests: Long): Boolean = retainedRequests >= 0L && retainedRequests < 64L

internal fun ownedTorrentFailureState(acquisitionStarted: Boolean, safeToReject: Boolean): String =
    if (!acquisitionStarted || safeToReject) "REJECTED" else "UNCERTAIN"

/** A lookup failure must propagate. Only a successful absent lookup permits the no-intent route. */
internal fun ownedTorrentLinkedIntent(requestId: UUID, snapshotId: UUID?, linkedId: UUID?,
    clientId: UUID, mappingDigest: String, lookup: () -> QbDistributionRecord?): QbDistributionRecord? {
    require(linkedId == null || linkedId == requestId) { "Unexpected distribution linkage" }
    val found = lookup()
    if (found == null) { require(linkedId == null) { "Linked intent is missing" }; return null }
    require(found.id == requestId && snapshotId != null && found.snapshotId == snapshotId &&
        found.clientId == clientId && found.mappingDigest == mappingDigest) { "Distribution identity differs from the persisted request" }
    return found
}
