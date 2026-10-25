package org.gameyfin.app.core.download.torrent

import java.util.UUID
import org.gameyfin.snapshot.SnapshotProtocol

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

internal fun ownedTorrentHostRequestDigest(request: OwnedTorrentRequestRow): String = SnapshotProtocol.digest(
    listOf("owned-torrent-host-request-v1", request.id.toString(), request.selection,
        SnapshotProtocol.digest(request.profile.toByteArray())).joinToString("\u0000").toByteArray())

internal fun ownedTorrentSnapshotBinding(request: OwnedTorrentRequestRow, snapshot: SnapshotRecordView?): UUID? {
    require(request.plannedSnapshotId == request.id && request.hostRequestDigest != null &&
        request.hostRequestDigest == ownedTorrentHostRequestDigest(request)) { "Request predates exact host binding or differs" }
    require(request.snapshotId == null || request.snapshotId == request.plannedSnapshotId) { "Unexpected snapshot linkage" }
    if (snapshot == null) { require(request.snapshotId == null) { "Linked snapshot is missing" }; return null }
    require(snapshot.id == request.plannedSnapshotId && snapshot.hostRequestDigest == request.hostRequestDigest) { "Snapshot belongs to another request" }
    return snapshot.id
}

internal enum class OwnedTorrentCreatorExit { LOCAL_EXIT, FOREIGN_ATTESTED }

internal fun ownedTorrentCreatorExit(identity: SnapshotProcessIdentity, probe: SnapshotProcessProbe,
    attestation: OwnedTorrentRequestExitAttestation?): OwnedTorrentCreatorExit? {
    if (probe.exitProved(identity)) return OwnedTorrentCreatorExit.LOCAL_EXIT
    // Same-scope live/unknown observations can never be overridden by a receipt.
    val currentScope = probe.scope() ?: return null
    if (currentScope == identity.scope || attestation == null) return null
    require(attestation.expectedScope == identity.scope && attestation.expectedPid == identity.pid &&
        attestation.expectedStartedMillis == identity.startedMillis) { "Creator attestation identity differs" }
    require(attestation.originalContainerId.matches(Regex("[0-9a-f]{64}")) &&
        attestation.evidenceSha256.matches(Regex("[0-9a-f]{64}")) &&
        attestation.originalContainerRemoved && attestation.allOldWorkersExited) { "Exact old container and worker exit receipt required" }
    return OwnedTorrentCreatorExit.FOREIGN_ATTESTED
}

internal fun ownedTorrentResourceFreeCancellation(request: OwnedTorrentRequestRow, probe: SnapshotProcessProbe,
    attestation: OwnedTorrentRequestExitAttestation?, snapshotLookup: () -> SnapshotRecordView?,
    intentLookup: () -> QbDistributionRecord?): OwnedTorrentCreatorExit {
    require(request.state in setOf("QUEUED", "RUNNING", "UNCERTAIN") && request.snapshotId == null && request.distributionId == null)
    require(request.plannedSnapshotId == request.id && request.hostRequestDigest == ownedTorrentHostRequestDigest(request))
    val identity = SnapshotProcessIdentity(requireNotNull(request.creatorScope), requireNotNull(request.creatorPid), requireNotNull(request.creatorStartedMillis))
    val proof = ownedTorrentCreatorExit(identity, probe, attestation) ?: error("Original creator exit remains unproved")
    require(snapshotLookup() == null && intentLookup() == null) { "Planned resources exist; reconcile exact linkage instead" }
    return proof
}
