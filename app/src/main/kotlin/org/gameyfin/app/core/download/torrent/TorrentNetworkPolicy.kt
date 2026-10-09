package org.gameyfin.app.core.download.torrent

import java.net.URI

/** Inactive host-only gate for already bounded/canonically parsed torrent metadata.
 * Approved URLs come from administrator configuration, never a request or plugin.
 * Performs no DNS resolution or network request. URL approval alone does not prove
 * tracker authentication, private routing, peer policy or safe client egress. */
internal class TorrentNetworkPolicy(
    approvedHttpsTrackerUrls: Set<String>,
    privateTrackerAdmissionConfirmed: Boolean
) {
    private val approved = approvedHttpsTrackerUrls.also {
        require(it.size in 1..8) { "Invalid approved tracker count" }
    }.toSet()

    init {
        require(privateTrackerAdmissionConfirmed) { "Private tracker admission policy must be confirmed" }
        approved.forEach(::requireHttpsTracker)
    }

    /** Success is only a metadata network-field check, never permission to add or seed.
     * Unknown root/info fields and all webseed forms are deliberately refused. */
    fun validate(root: Map<String, Any>) {
        require(root.keys.all { it in ROOT_FIELDS } && root.size <= ROOT_FIELDS.size) { "Unsupported torrent network fields" }
        val info = root["info"] as? Map<*, *> ?: error("Missing torrent info")
        require(info.keys == INFO_FIELDS && info["private"] == 1L) { "Private supported torrent info required" }
        for (key in listOf("comment", "created by", "encoding")) root[key]?.let {
            require(it is ByteArray && it.size <= 4096) { "Invalid descriptive metadata" }
        }
        root["creation date"]?.let { require(it is Long && it >= 0) { "Invalid creation date" } }
        val primary = tracker(root["announce"])
        require(primary in approved) { "Unapproved primary tracker" }
        root["announce-list"]?.let { value ->
            val tiers = value as? List<*> ?: error("Invalid tracker tiers")
            require(tiers.size in 1..8) { "Invalid tracker tier count" }
            val destinations = mutableSetOf<String>()
            var count = 0
            for (tierValue in tiers) {
                val tier = tierValue as? List<*> ?: error("Invalid tracker tier")
                require(tier.size in 1..8) { "Invalid tracker tier size" }
                for (entry in tier) {
                    require(++count <= 8) { "Excessive tracker destinations" }
                    val destination = tracker(entry)
                    require(destination in approved && destinations.add(destination)) { "Unapproved or duplicate tracker" }
                }
            }
            require(primary in destinations) { "Primary tracker missing from tiers" }
        }
    }

    private fun tracker(value: Any?): String {
        val bytes = value as? ByteArray ?: error("Invalid tracker encoding")
        require(bytes.size in 1..2048 && bytes.all { it.toInt() in 33..126 }) { "Invalid tracker encoding" }
        return bytes.toString(Charsets.US_ASCII).also(::requireHttpsTracker)
    }

    private fun requireHttpsTracker(value: String) {
        require(value.length in 1..2048 && value.all { it.code in 33..126 }) { "Invalid tracker URL" }
        // Parser errors are deliberately sanitized: URI exceptions can contain passkeys.
        val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("Invalid tracker URL") }
        require(uri.scheme == "https" && !uri.isOpaque && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null &&
                (uri.port == -1 || uri.port in 1..65535) && !uri.rawPath.isNullOrEmpty() && uri.toASCIIString() == value) { "Invalid tracker URL" }
        require(uri.rawAuthority == uri.host + if (uri.port == -1) "" else ":${uri.port}") { "Ambiguous tracker authority" }
    }

    private companion object {
        val ROOT_FIELDS = setOf("info", "announce", "announce-list", "comment", "created by", "creation date", "encoding")
        val INFO_FIELDS = setOf("files", "name", "piece length", "pieces", "private")
    }
}
