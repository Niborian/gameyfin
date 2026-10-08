package org.gameyfin.pluginapi.download

import org.pf4j.ExtensionPoint
import java.io.InputStream
import java.util.UUID

/** Narrow metadata-only adapter; it receives an owned snapshot identity, never library paths.
 * Host orchestration owns authorization, snapshot construction, validation and durable intent.
 * Implementations map snapshotId under their configured read-only snapshot mount and must
 * explicitly disable automatic seeding. No add/start/delete/adoption capability is exposed.
 */
interface TorrentMetadataBackend : ExtensionPoint {
    fun createMetadata(snapshot: OwnedTorrentSnapshot, operationId: UUID): TorrentMetadataTask
    fun readMetadata(task: TorrentMetadataTask): InputStream
}

class OwnedTorrentSnapshot(val snapshotId: UUID, val manifestDigest: String) {
    init { require(manifestDigest.matches(Regex("[0-9a-f]{64}"))) }
}

class TorrentMetadataTask(val taskId: String) {
    init { require(taskId.matches(Regex("[A-Za-z0-9-]{1,128}"))) }
}
