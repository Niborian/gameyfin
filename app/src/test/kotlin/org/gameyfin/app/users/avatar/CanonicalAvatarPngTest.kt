package org.gameyfin.app.users.avatar

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.*

class CanonicalAvatarPngTest {
    private fun png(width: Int = 4, height: Int = width): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0x80402010.toInt())
        return try { ByteArrayOutputStream().use { out ->
            check(ImageIO.write(image, "PNG", out)); out.toByteArray()
        } } finally { image.flush() }
    }
    private fun hash(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun refused(bytes: ByteArray) = assertFailsWith<IllegalArgumentException> {
        CanonicalAvatarPng.validate(bytes, hash(bytes))
    }
    private fun chunk(type: String, content: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(typeBytes); update(content) }.value
        return ByteBuffer.allocate(content.size + 12).putInt(content.size).put(typeBytes)
            .put(content).putInt(crc.toInt()).array()
    }
    @Test fun `retains exact square PNG bytes hash alpha and defensive ownership`() {
        val original = png()
        val source = original.copyOf()
        val checked = CanonicalAvatarPng.validate(source, hash(source))
        source.fill(0)
        assertContentEquals(original, checked.copyBytes())
        assertEquals(hash(original), checked.sha256)
        assertEquals(4, checked.dimension)
        val returned = checked.copyBytes(); returned.fill(0)
        assertContentEquals(original, checked.copyBytes())
    }
    @Test fun `accepts maximum square dimensions without rewriting pixels`() {
        val bytes = png(512)
        assertContentEquals(bytes, CanonicalAvatarPng.validate(bytes, hash(bytes)).copyBytes())
    }
    @Test fun `bounded stream preserves bytes and leaves caller stream open`() {
        val bytes = png()
        var closed = false
        val stream = object : ByteArrayInputStream(bytes) {
            override fun close() { closed = true; super.close() }
        }
        assertContentEquals(bytes, CanonicalAvatarPng.validate(stream, hash(bytes)).copyBytes())
        assertFalse(closed)
    }
    @Test fun `unbounded input stops after exactly limit plus one without decoding`() {
        var consumed = 0
        val stream = object : InputStream() {
            override fun read(): Int { consumed++; return 0 }
        }
        assertFailsWith<IllegalArgumentException> {
            CanonicalAvatarPng.validate(stream, "0".repeat(64))
        }
        assertEquals(CanonicalAvatarPng.MAX_BYTES + 1, consumed)
    }
    @Test fun `invalid hash refuses stream before reading`() {
        val stream = object : InputStream() {
            override fun read(): Int = error("Must not read invalid intent")
        }
        assertFailsWith<IllegalArgumentException> { CanonicalAvatarPng.validate(stream, "invalid") }
    }
    @Test fun `rejects oversized nonsquare empty and non PNG input`() {
        refused(png(513)); refused(png(4, 3)); refused(byteArrayOf())
        refused(ByteArray(CanonicalAvatarPng.MAX_BYTES + 1))
        refused("<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray())
    }
    @Test fun `requires the pinned actual content hash`() {
        val bytes = png()
        assertFailsWith<IllegalArgumentException> { CanonicalAvatarPng.validate(bytes, "0".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { CanonicalAvatarPng.validate(bytes, "not-a-hash") }
    }
    @Test fun `rejects all APNG chunk types even with correct hashes and CRCs`() {
        val bytes = png()
        for (type in listOf("acTL", "fcTL", "fdAT")) {
            refused(bytes.copyOfRange(0, 33) + chunk(type, ByteArray(8)) + bytes.copyOfRange(33, bytes.size))
        }
    }
    @Test fun `rejects corrupted CRC truncated chunks and trailing data`() {
        val bytes = png()
        refused(bytes.copyOf().also { it[29] = (it[29].toInt() xor 1).toByte() })
        refused(bytes.copyOf(bytes.size - 1)); refused(bytes + byteArrayOf(0))
        refused(bytes.copyOf().also { ByteBuffer.wrap(it, 33, 4).putInt(Int.MAX_VALUE) })
    }
    @Test fun `rejects unknown critical duplicate header and missing data`() {
        val bytes = png()
        refused(bytes.copyOfRange(0, 33) + chunk("ABCD", byteArrayOf()) + bytes.copyOfRange(33, bytes.size))
        refused(bytes.copyOfRange(0, 33) + bytes.copyOfRange(8, 33) + bytes.copyOfRange(33, bytes.size))
        refused(bytes.copyOfRange(0, 33) + chunk("IEND", byteArrayOf()))
    }
    @Test fun `rejects malformed compressed pixels despite matching byte hash and CRC`() {
        val bytes = png()
        refused(bytes.copyOfRange(0, 33) + chunk("IDAT", byteArrayOf(1, 2, 3)) + chunk("IEND", byteArrayOf()))
    }
}
