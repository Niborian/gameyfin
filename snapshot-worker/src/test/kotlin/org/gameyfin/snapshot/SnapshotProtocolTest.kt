package org.gameyfin.snapshot

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.test.*

class SnapshotProtocolTest {
    @Test fun `framing refuses oversize and truncated messages`() {
        for (size in listOf(-1, 0, SnapshotProtocol.MAX_FRAME + 1)) {
            assertFails { SnapshotProtocol.frame(ByteArrayInputStream(SnapshotProtocol.encode { writeInt(size) })) }
        }
        assertFails { SnapshotProtocol.frame(ByteArrayInputStream(SnapshotProtocol.encode { writeInt(3); writeByte(1) })) }
        val encoded = ByteArrayOutputStream().also { SnapshotProtocol.frame(it, byteArrayOf(1, 2)) }.toByteArray()
        assertContentEquals(byteArrayOf(1, 2), SnapshotProtocol.frame(ByteArrayInputStream(encoded)))
    }
    @Test fun `member names refuse traversal absolute paths controls and empty components`() {
        for (name in listOf("/file", "a//b", "a/../b", "a/./b", "a\\b", "a\u0000b", "a\nb", "")) assertFails(name) { SnapshotProtocol.relative(name) }
        assertEquals("nested/.hidden", SnapshotProtocol.relative("nested/.hidden"))
    }
    @Test fun `decode rejects trailing bytes and invalid UTF8`() {
        assertFails { SnapshotProtocol.decode(byteArrayOf(1, 2)) { readByte() } }
        assertFails { SnapshotProtocol.decode(SnapshotProtocol.encode { writeInt(1); writeByte(0xff) }) { SnapshotProtocol.text(this) } }
    }
    @Test fun `capture refuses overlapping source selections and case-colliding destinations`() {
        fun capture(selections: List<SnapshotSelection>) = SnapshotCapture(UUID.randomUUID(), UUID.randomUUID(), "/source", "/cache", "id", selections, 1024, 4, 0)
        assertFails { capture(listOf(SnapshotSelection("a", "first"), SnapshotSelection("a/b", "second"))) }
        assertFails { capture(listOf(SnapshotSelection("a", "First"), SnapshotSelection("b", "first"))) }
        assertFails { capture(listOf(SnapshotSelection("a", "Group/one"), SnapshotSelection("b", "group/two"))) }
        val request = capture(listOf(SnapshotSelection("a", "first"), SnapshotSelection("b", "second")))
        val decoded = SnapshotProtocol.decode(request.bytes()) {
            assertEquals(SnapshotProtocol.VERSION, readInt()); assertEquals(SnapshotProtocol.CAPTURE, readInt()); SnapshotCapture.read(this)
        }
        assertEquals(request, decoded)
    }
    @Test fun `manifest is deterministic and binds source and copied identities`() {
        val manifest = SnapshotManifest(UUID.randomUUID(), UUID.randomUUID(), "0".repeat(64), "cache", "directory",
            listOf(SnapshotMember("content-1/base.bin", 3, "a".repeat(64), "copied", "original")))
        assertEquals(manifest, SnapshotManifest.read(manifest.bytes()))
        assertEquals(SnapshotProtocol.digest(manifest.bytes()), SnapshotProtocol.digest(manifest.bytes()))
        assertFails { SnapshotManifest.read(manifest.copy(members = manifest.members + manifest.members.single()).bytes()) }
    }
}
