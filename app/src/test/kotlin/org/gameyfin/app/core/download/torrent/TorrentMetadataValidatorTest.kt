package org.gameyfin.app.core.download.torrent

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlin.test.*

class TorrentMetadataValidatorTest {
    private val tracker = "https://tracker.invalid/private/announce"
    private fun validator(maxBytes: Long = 64L * 1024 * 1024 * 1024) = TorrentMetadataValidator(TorrentNetworkPolicy(setOf(tracker), true), maxBytes)
    private val payload = linkedMapOf("Grouped/base-a.bin" to ByteArray(12000) { 1 }, "Grouped/base-b.bin" to ByteArray(9000) { 2 }, "Optional/music.bin" to byteArrayOf(3, 4))
    private fun digest(bytes: ByteArray, algorithm: String = "SHA-256") = MessageDigest.getInstance(algorithm).digest(bytes)
    private fun manifest(data: Map<String, ByteArray> = payload) = data.map { (name, bytes) -> SnapshotMember(name, bytes.size.toLong(), digest(bytes).joinToString("") { "%02x".format(it) }) }
    private fun info(data: Map<String, ByteArray> = payload): MutableMap<String, Any> {
        val joined = data.values.fold(byteArrayOf()) { a, b -> a + b }
        val pieces = joined.asList().chunked(16384).fold(byteArrayOf()) { a, b -> a + digest(b.toByteArray(), "SHA-1") }
        return linkedMapOf("files" to data.map { (name, bytes) -> mapOf("length" to bytes.size.toLong(), "path" to name.split('/')) }, "name" to "Owned_snapshot", "piece length" to 16384L, "pieces" to pieces, "private" to 1L)
    }
    private fun encode(value: Any): ByteArray = when (value) {
        is String -> encode(value.toByteArray())
        is ByteArray -> "${value.size}:".toByteArray() + value
        is Long -> "i${value}e".toByteArray()
        is List<*> -> byteArrayOf('l'.code.toByte()) + value.fold(byteArrayOf()) { a, b -> a + encode(requireNotNull(b)) } + byteArrayOf('e'.code.toByte())
        is Map<*, *> -> byteArrayOf('d'.code.toByte()) + value.entries.sortedBy { it.key.toString() }.fold(byteArrayOf()) { a, b -> a + encode(b.key.toString()) + encode(requireNotNull(b.value)) } + byteArrayOf('e'.code.toByte())
        else -> error("Unsupported test value")
    }
    private fun metadata(value: Map<String, Any> = info()) = encode(mapOf("announce" to tracker, "info" to value))
    private class Tracked(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }

    @Test fun `grouped and optional members span exact torrent pieces with streams closed`() {
        val streams = mutableListOf<Tracked>()
        val input = Tracked(metadata())
        val result = validator().validate(input, manifest()) { name -> Tracked(payload.getValue(name)).also { streams += it } }
        assertEquals(21002L, result.sourceBytes)
        assertEquals(3, result.members)
        assertEquals(40, result.infoHash.length)
        assertTrue(input.closed && streams.all { it.closed })
    }

    @Test fun `required only and empty members validate without optional leakage`() {
        val selected = linkedMapOf("Grouped/base-a.bin" to payload.getValue("Grouped/base-a.bin"), "Grouped/empty.bin" to byteArrayOf())
        assertEquals(2, validator().validate(ByteArrayInputStream(metadata(info(selected))), manifest(selected)) { ByteArrayInputStream(selected.getValue(it)) }.members)
    }

    @Test fun `truncation growth and changed source digest close all streams`() {
        for (changed in listOf(payload.getValue("Grouped/base-a.bin").dropLast(1).toByteArray(), payload.getValue("Grouped/base-a.bin") + 5, ByteArray(12000) { 9 })) {
            val streams = mutableListOf<Tracked>()
            val input = Tracked(metadata())
            assertFails { validator().validate(input, manifest()) { name -> Tracked(if (name == "Grouped/base-a.bin") changed else payload.getValue(name)).also { streams += it } } }
            assertTrue(input.closed && streams.all { it.closed })
        }
    }

    @Test fun `missing extra duplicate unsafe and mismatched file entries fail before source access`() {
        val cases = listOf(
            info().apply { this["files"] = (this["files"] as List<*>).dropLast(1) },
            info().apply { this["files"] = (this["files"] as List<*>) + mapOf("length" to 1L, "path" to listOf("extra.bin")) },
            info().apply { val files = this["files"] as List<*>; this["files"] = listOf(files[0], files[0], files[2]) },
            info().apply { this["files"] = listOf(mapOf("length" to 12000L, "path" to listOf("..", "outside")), (this["files"] as List<*>)[1], (this["files"] as List<*>)[2]) },
            info().apply { this["name"] = "../outside" },
            info().apply { this["private"] = 0L },
            info().apply { this["piece length"] = 17000L },
            info().apply { this["pieces"] = byteArrayOf() },
            info().apply { this["meta version"] = 2L }
        )
        for (bad in cases) assertFails { validator().validate(ByteArrayInputStream(metadata(bad)), manifest()) { error("Must not read source") } }
    }

    @Test fun `corrupt piece digest is refused`() {
        val bad = info().apply { this["pieces"] = ByteArray(40) }
        assertFails { validator().validate(ByteArrayInputStream(metadata(bad)), manifest()) { ByteArrayInputStream(payload.getValue(it)) } }
        assertFails { validator().validate(ByteArrayInputStream(metadata()), manifest().map { it.copy(sha256 = "0".repeat(64)) }) { ByteArrayInputStream(payload.getValue(it)) } }
    }

    @Test fun `canonical parsing rejects malformed nesting duplicates integers lengths and trailing bytes`() {
        val malformed = listOf("d4:infoi0e4:infoi0ee", "d4:infoi01ee", "d4:infoi-0ee", "d4:infoi9223372036854775808ee", "d04:infoi0ee", "d4:info3:xe", "l".repeat(18) + "e".repeat(18))
        for (raw in malformed.map { it.toByteArray() } + listOf(metadata() + 'x'.code.toByte(), ("l" + "0:".repeat(32769) + "e").toByteArray())) {
            val input = Tracked(raw)
            assertFails { validator().validate(input, manifest()) { error("Must not read") } }
            assertTrue(input.closed)
        }
    }

    @Test fun `metadata member and actual byte budgets fail closed`() {
        val input = Tracked(ByteArray(1024 * 1024 + 1))
        assertFails { validator().validate(input, manifest()) { error("Must not read") } }
        assertTrue(input.closed)
        assertFails { validator(21001).validate(ByteArrayInputStream(metadata()), manifest()) { error("Must not read") } }
        assertFails { validator().validate(ByteArrayInputStream(metadata()), List(4097) { manifest().first() }) { error("Must not read") } }
        assertFails { validator().validate(ByteArrayInputStream(metadata()), listOf(manifest().first(), manifest().first().copy(generatedName = "grouped/base-a.bin"))) { error("Must not read") } }
    }

    @Test fun `preexisting interruption closes metadata without opening members or clearing flag`() {
        val input = Tracked(metadata())
        var opened = 0
        Thread.currentThread().interrupt()
        try {
            assertFails { validator().validate(input, manifest()) { opened++; error("Must not open") } }
            assertEquals(0, opened)
            assertTrue(input.closed)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun `all empty members require no pieces and still verify exact EOF`() {
        val selected = linkedMapOf("Grouped/empty.bin" to byteArrayOf())
        assertEquals(0L, validator().validate(ByteArrayInputStream(metadata(info(selected))), manifest(selected)) { ByteArrayInputStream(byteArrayOf()) }.sourceBytes)
    }

    @Test fun `preinterrupted all-empty snapshot never opens members or returns success`() {
        val selected = linkedMapOf("Grouped/empty.bin" to byteArrayOf())
        val input = Tracked(metadata(info(selected)))
        var opened = 0
        Thread.currentThread().interrupt()
        try {
            assertFails { validator().validate(input, manifest(selected)) { opened++; ByteArrayInputStream(byteArrayOf()) } }
            assertEquals(0, opened)
            assertTrue(input.closed)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun `interruption at empty member EOF is observed before opening next member`() {
        val selected = linkedMapOf("Grouped/empty-a.bin" to byteArrayOf(), "Grouped/empty-b.bin" to byteArrayOf())
        val input = Tracked(metadata(info(selected)))
        var opened = 0
        var closed = false
        val source = object : ByteArrayInputStream(byteArrayOf()) {
            override fun read(): Int { Thread.currentThread().interrupt(); return super.read() }
            override fun close() { closed = true; super.close() }
        }
        try {
            assertFails { validator().validate(input, manifest(selected)) { opened++; source } }
            assertEquals(1, opened)
            assertTrue(input.closed && closed)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun `stalled stream and reader failure close metadata without unbounded retry`() {
        val input = Tracked(metadata())
        var closed = false
        val stalled = object : java.io.InputStream() {
            override fun read() = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
            override fun close() { closed = true }
        }
        assertFails { validator().validate(input, manifest()) { stalled } }
        assertTrue(closed && input.closed)
        val throwingInput = Tracked(metadata())
        assertFails { validator().validate(throwingInput, manifest()) { error("Synthetic reader refusal") } }
        assertTrue(throwingInput.closed)
    }

    @Test fun `network refusal prevents any snapshot read or accepted metadata output`() {
        val roots = listOf(
            mapOf("announce" to "https://foreign.invalid/announce", "info" to info()),
            mapOf("announce" to tracker, "info" to info(), "announce-list" to listOf(listOf(tracker, "https://foreign.invalid/announce"))),
            mapOf("announce" to tracker, "info" to info(), "url-list" to "https://foreign.invalid/seed"),
            mapOf("announce" to tracker, "info" to info(), "x-network" to "https://foreign.invalid/unknown"),
            mapOf("info" to info())
        )
        for (root in roots) {
            val input = Tracked(encode(root))
            var reads = 0
            assertFails { validator().validate(input, manifest()) { reads++; error("Must not open snapshot") } }
            assertEquals(0, reads)
            assertTrue(input.closed)
        }
    }

    @Test fun `approved network cannot bypass content failure or metadata digest binding`() {
        val bytes = metadata()
        val result = validator().validate(ByteArrayInputStream(bytes), manifest()) { ByteArrayInputStream(payload.getValue(it)) }
        assertEquals(digest(bytes).joinToString("") { "%02x".format(it) }, result.metadataSha256)
        assertEquals("Owned_snapshot", result.rootName)
        assertEquals(digest(encode(info()), "SHA-1").joinToString("") { "%02x".format(it) }, result.infoHash)
        val input = Tracked(bytes)
        assertFails { validator().validate(input, manifest()) { ByteArrayInputStream(byteArrayOf(99)) } }
        assertTrue(input.closed)
    }
}
