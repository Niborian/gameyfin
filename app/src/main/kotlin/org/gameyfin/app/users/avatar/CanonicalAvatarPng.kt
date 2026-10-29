package org.gameyfin.app.users.avatar

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.CRC32
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/**
 * Bounded byte validation only. This does NOT authenticate Core, establish an OIDC
 * binding, grant avatar-write permission, or accept a URL. The future HTTP boundary
 * must use the bounded stream overload (and a request deadline), and independently
 * authenticate current source/binding/installation authority before and at commit.
 * Original PNG bytes/pixels are retained, never resized or re-encoded.
 */
class CanonicalAvatarPng private constructor(
    private val content: ByteArray,
    val sha256: String,
    val dimension: Int
) {
    fun copyBytes(): ByteArray = content.copyOf()

    companion object {
        const val MAX_BYTES = 1024 * 1024
        const val MAX_DIMENSION = 512
        private val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

        /** Reads at most MAX_BYTES + 1; caller owns stream lifetime and transport timeout. */
        fun validate(input: InputStream, expectedSha256: String): CanonicalAvatarPng {
            require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "Avatar hash format refused" }
            val bytes = input.readNBytes(MAX_BYTES + 1)
            require(bytes.size <= MAX_BYTES) { "Avatar PNG size refused" }
            return validate(bytes, expectedSha256)
        }

        fun validate(input: ByteArray, expectedSha256: String): CanonicalAvatarPng {
            require(input.size in 45..MAX_BYTES) { "Avatar PNG size refused" }
            require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "Avatar hash format refused" }
            // Own a bounded immutable snapshot; caller mutations cannot change the checked bytes.
            val bytes = input.copyOf()
            require(bytes.copyOfRange(0, 8).contentEquals(signature)) { "Avatar PNG signature refused" }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            require(MessageDigest.isEqual(digest, HexFormat.of().parseHex(expectedSha256))) {
                "Avatar hash mismatch"
            }
            var offset = 8
            var dimension = 0
            var colorType = -1
            var palette = false
            var data = false
            var dataEnded = false
            var ended = false
            while (offset < bytes.size) {
                require(bytes.size - offset >= 12) { "Avatar PNG chunk truncated" }
                val length = ByteBuffer.wrap(bytes, offset, 4).int
                require(length >= 0 && length.toLong() + 12 <= bytes.size - offset) {
                    "Avatar PNG chunk length refused"
                }
                val typeBytes = bytes.copyOfRange(offset + 4, offset + 8)
                require(typeBytes.all { it.toInt() in 65..90 || it.toInt() in 97..122 } &&
                    typeBytes[2].toInt() in 65..90) { "Avatar PNG chunk type refused" }
                val type = String(typeBytes, Charsets.US_ASCII)
                require(type !in setOf("acTL", "fcTL", "fdAT")) { "Animated avatar refused" }
                val crc = CRC32().apply { update(bytes, offset + 4, length + 4) }.value
                val recordedCrc = ByteBuffer.wrap(bytes, offset + 8 + length, 4).int.toLong() and 0xffffffffL
                require(crc == recordedCrc) { "Avatar PNG checksum refused" }
                if (offset == 8) require(type == "IHDR") { "Avatar PNG header missing" }
                if (data && type != "IDAT") dataEnded = true
                when (type) {
                    "IHDR" -> {
                        require(offset == 8 && length == 13) { "Avatar PNG header refused" }
                        val header = ByteBuffer.wrap(bytes, offset + 8, length)
                        val width = header.int
                        val height = header.int
                        require(width in 1..MAX_DIMENSION && height == width) { "Avatar dimensions refused" }
                        val depth = header.get().toInt() and 255
                        colorType = header.get().toInt() and 255
                        val depths = when (colorType) {
                            0 -> setOf(1, 2, 4, 8, 16)
                            2, 4, 6 -> setOf(8, 16)
                            3 -> setOf(1, 2, 4, 8)
                            else -> emptySet()
                        }
                        require(depth in depths && header.get().toInt() == 0 &&
                            header.get().toInt() == 0 && header.get().toInt() in 0..1) {
                            "Avatar PNG encoding refused"
                        }
                        dimension = width
                    }
                    "PLTE" -> {
                        require(!palette && !data && colorType !in setOf(0, 4) &&
                            length in 3..768 && length % 3 == 0) { "Avatar PNG palette refused" }
                        palette = true
                    }
                    "IDAT" -> {
                        require(!dataEnded && (colorType != 3 || palette)) { "Avatar PNG data order refused" }
                        data = true
                    }
                    "IEND" -> {
                        require(length == 0 && data && offset + 12 == bytes.size) { "Avatar PNG end refused" }
                        ended = true
                    }
                    else -> require(typeBytes[0].toInt() in 97..122) { "Avatar PNG critical chunk refused" }
                }
                offset += length + 12
            }
            require(ended && dimension > 0) { "Avatar PNG incomplete" }
            // Use an explicit memory-backed stream, no temporary file/cache or remote fetch.
            // Header bounds precede decode; metadata (including compressed text) is ignored.
            MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
                val readers = ImageIO.getImageReadersByFormatName("PNG")
                require(readers.hasNext()) { "Avatar PNG decoder unavailable" }
                val reader = readers.next()
                try {
                    reader.setInput(stream, true, true)
                    val decoded = reader.read(0) ?: throw IllegalArgumentException("Avatar PNG decode refused")
                    try {
                        require(decoded.width == dimension && decoded.height == dimension) {
                            "Avatar decoded dimensions refused"
                        }
                    } finally {
                        decoded.flush()
                    }
                } catch (_: java.io.IOException) {
                    throw IllegalArgumentException("Avatar PNG decode refused")
                } finally {
                    reader.dispose()
                }
            }
            return CanonicalAvatarPng(bytes, expectedSha256, dimension)
        }
    }
}
