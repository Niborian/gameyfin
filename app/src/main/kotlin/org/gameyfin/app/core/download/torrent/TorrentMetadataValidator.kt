package org.gameyfin.app.core.download.torrent

import java.io.InputStream
import java.security.MessageDigest

/** Host-only capability. Implementations must read an already verified, leased immutable
 * snapshot. Never implement this with a caller supplied path or a mutable library source. */
internal fun interface OwnedSnapshotReader {
    fun openMember(generatedName: String): InputStream
}

internal data class SnapshotMember(val generatedName: String, val bytes: Long, val sha256: String)
internal data class ValidatedTorrentMetadata(val metadataSha256: String, val infoHash: String, val rootName: String, val sourceBytes: Long, val members: Int)

/** Pure inactive validation; no provider, path resolution, client call or seeding authority.
 * Owns and closes metadata and member streams. Bounds allocations and actual bytes read;
 * interruption is cooperative, not a guarantee against a blocked InputStream implementation. */
internal class TorrentMetadataValidator(private val maxSourceBytes: Long = 64L * 1024 * 1024 * 1024) {
    init { require(maxSourceBytes in 1..(1024L * 1024 * 1024 * 1024)) }

    fun validate(metadata: InputStream, expected: List<SnapshotMember>, reader: OwnedSnapshotReader): ValidatedTorrentMetadata = metadata.use { input ->
        require(expected.size in 1..4096) { "Invalid member count" }
        var expectedBytes = 0L
        val manifest = expected.associateBy {
            require(safeName(it.generatedName) && it.bytes >= 0 && it.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid owned manifest" }
            require(it.bytes <= maxSourceBytes - expectedBytes) { "Snapshot exceeds byte budget" }
            expectedBytes += it.bytes
            it.generatedName
        }
        require(manifest.size == expected.size) { "Duplicate owned member" }
        require(manifest.keys.map { it.lowercase(java.util.Locale.ROOT) }.toSet().size == manifest.size) { "Case-colliding owned members" }
        val encoded = input.readNBytes(1024 * 1024 + 1)
        require(encoded.size <= 1024 * 1024) { "Metadata exceeds byte budget" }
        val parser = Parser(encoded)
        val root = parser.parse() as? Map<*, *> ?: error("Expected torrent dictionary")
        require(parser.position == encoded.size) { "Trailing metadata" }
        val info = root["info"] as? Map<*, *> ?: error("Missing info dictionary")
        require(info.keys == setOf("files", "name", "piece length", "pieces", "private")) { "Unsupported info fields" }
        require(info["private"] == 1L) { "Private torrent required" }
        val rootName = ascii(info["name"])
        require(safeName(rootName) && !rootName.contains('/')) { "Unsafe root name" }
        val pieceLength = info["piece length"] as? Long ?: error("Missing piece size")
        require(pieceLength in 16384..(16 * 1024 * 1024) && pieceLength and (pieceLength - 1) == 0L) { "Invalid piece size" }
        val pieces = info["pieces"] as? ByteArray ?: error("Missing pieces")
        val pieceCount = if (expectedBytes == 0L) 0 else (expectedBytes - 1) / pieceLength + 1
        require(pieceCount <= 52428 && pieces.size.toLong() == pieceCount * 20) { "Invalid piece count" }
        val files = info["files"] as? List<*> ?: error("Missing files")
        require(files.size == expected.size) { "Incorrect member count" }
        val ordered = files.map { value ->
            val file = value as? Map<*, *> ?: error("Invalid file dictionary")
            require(file.keys == setOf("length", "path")) { "Unsupported member fields" }
            val path = file["path"] as? List<*> ?: error("Invalid member path")
            require(path.size in 1..32) { "Invalid path depth" }
            val name = path.joinToString("/") { ascii(it).also { component -> require(safeName(component) && !component.contains('/')) } }
            val member = manifest[name] ?: error("Unexpected member")
            require(file["length"] == member.bytes) { "Incorrect member size" }
            member
        }
        require(ordered.map { it.generatedName }.toSet().size == manifest.size) { "Duplicate or missing member" }
        val buffer = ByteArray(65536)
        var piece = MessageDigest.getInstance("SHA-1")
        var withinPiece = 0L
        var pieceIndex = 0
        fun finishPiece() {
            require(MessageDigest.isEqual(piece.digest(), pieces.copyOfRange(pieceIndex * 20, (pieceIndex + 1) * 20))) { "Piece mismatch" }
            pieceIndex++
            piece = MessageDigest.getInstance("SHA-1")
            withinPiece = 0
        }
        for (member in ordered) reader.openMember(member.generatedName).use { source ->
            val memberDigest = MessageDigest.getInstance("SHA-256")
            var remaining = member.bytes
            while (remaining > 0) {
                check(!Thread.currentThread().isInterrupted) { "Validation interrupted" }
                val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining, pieceLength - withinPiece).toInt())
                require(count > 0) { "Truncated or stalled member" }
                memberDigest.update(buffer, 0, count)
                piece.update(buffer, 0, count)
                remaining -= count
                withinPiece += count
                if (withinPiece == pieceLength) finishPiece()
            }
            require(source.read() == -1) { "Member exceeds manifest size" }
            require(memberDigest.digest().hex() == member.sha256) { "Member digest mismatch" }
        }
        if (withinPiece > 0) finishPiece()
        require(pieceIndex.toLong() == pieceCount) { "Incomplete pieces" }
        val span = requireNotNull(parser.infoSpan)
        ValidatedTorrentMetadata(MessageDigest.getInstance("SHA-256").digest(encoded).hex(), MessageDigest.getInstance("SHA-1").digest(encoded.copyOfRange(span.first, span.second)).hex(), rootName, expectedBytes, ordered.size)
    }

    private fun safeName(value: String) = value.length in 1..255 && value.split('/').all {
        it.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}")) && it != "." && it != ".."
    }
    private fun ascii(value: Any?): String {
        val bytes = value as? ByteArray ?: error("Expected byte string")
        require(bytes.all { it.toInt() in 32..126 }) { "Non-ASCII generated name" }
        return bytes.toString(Charsets.US_ASCII)
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private class Parser(private val bytes: ByteArray) {
        var position = 0
        var infoSpan: Pair<Int, Int>? = null
        private var nodes = 0
        fun parse(depth: Int = 0): Any {
            require(depth <= 16 && ++nodes <= 32768 && position < bytes.size) { "Excessive or truncated metadata" }
            return when (bytes[position].toInt().toChar()) {
                'i' -> {
                    position++
                    val start = position
                    while (position < bytes.size && bytes[position] != 'e'.code.toByte()) position++
                    require(position < bytes.size && position - start <= 19) { "Invalid integer" }
                    val raw = bytes.copyOfRange(start, position++).toString(Charsets.US_ASCII)
                    require(raw.matches(Regex("0|[1-9][0-9]*"))) { "Noncanonical or negative integer" }
                    raw.toLongOrNull() ?: error("Integer overflow")
                }
                'l' -> {
                    position++
                    val result = mutableListOf<Any>()
                    while (position < bytes.size && bytes[position] != 'e'.code.toByte()) result += parse(depth + 1)
                    require(position < bytes.size) { "Unterminated list" }; position++
                    result
                }
                'd' -> {
                    position++
                    val result = linkedMapOf<String, Any>()
                    var previous: String? = null
                    while (position < bytes.size && bytes[position] != 'e'.code.toByte()) {
                        val keyBytes = parse(depth + 1) as? ByteArray ?: error("Invalid dictionary key")
                        require(keyBytes.size in 1..128 && keyBytes.all { it.toInt() in 32..126 }) { "Invalid dictionary key" }
                        val key = keyBytes.toString(Charsets.US_ASCII)
                        require(previous == null || previous < key) { "Duplicate or unordered key" }
                        previous = key
                        val start = position
                        result[key] = parse(depth + 1)
                        if (depth == 0 && key == "info") infoSpan = start to position
                    }
                    require(position < bytes.size) { "Unterminated dictionary" }; position++
                    result
                }
                else -> {
                    val start = position
                    while (position < bytes.size && bytes[position] != ':'.code.toByte()) {
                        require(bytes[position].toInt() in '0'.code..'9'.code && position - start < 7) { "Invalid byte length" }
                        position++
                    }
                    require(position < bytes.size) { "Truncated byte length" }
                    val raw = bytes.copyOfRange(start, position++).toString(Charsets.US_ASCII)
                    require(raw.matches(Regex("0|[1-9][0-9]*"))) { "Noncanonical byte length" }
                    val length = raw.toInt()
                    require(length <= bytes.size - position) { "Truncated byte string" }
                    bytes.copyOfRange(position, position + length).also { position += length }
                }
            }
        }
    }
}
