package org.gameyfin.app.core.download.files

import org.gameyfin.pluginapi.download.DownloadSelection
import org.gameyfin.pluginapi.download.DownloadSelectionContent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.nio.file.SecureDirectoryStream
import org.junit.jupiter.api.Assumptions.assumeTrue

class SelectionSnapshotBuilderTest {
    @Test
    fun `exact grouped copy keeps original paths bytes and identity`(@TempDir root: Path) {
        val source = Files.createDirectory(root.resolve("sources"))
        assumeTrue(Files.newDirectoryStream(source).use { it is SecureDirectoryStream<*> }, "Secure source descriptors required; Linux CI must execute this fixture")
        val cache = Files.createDirectory(root.resolve("cache"))
        val first = Files.writeString(source.resolve("base-a.bin"), "first")
        val second = Files.writeString(source.resolve("base-b.bin"), "second")
        Files.writeString(source.resolve("unchecked.bin"), "must not appear")
        val snapshot = SelectionSnapshotBuilder(cache, 100, 10, Duration.ofSeconds(5)).build(
            DownloadSelection(listOf(DownloadSelectionContent("Grouped base", listOf(first, second)))))
        assertEquals(setOf("Grouped base/base-a.bin", "Grouped base/base-b.bin"), snapshot.hashes.keys)
        assertEquals(11L, snapshot.bytes)
        assertEquals("first", Files.readString(first))
        assertEquals("second", Files.readString(second))
        assertFalse(Files.isSameFile(first, snapshot.directory.resolve("Grouped base/base-a.bin")))
        assertFalse(Files.exists(snapshot.directory.resolve("unchecked.bin")))
    }

    @Test
    fun `bounds and name collisions fail without source change`(@TempDir root: Path) {
        val source = Files.writeString(root.resolve("source.bin"), "unchanged")
        val cache = Files.createDirectory(root.resolve("cache"))
        val content = DownloadSelectionContent("base", listOf(source))
        assertThrows(IllegalArgumentException::class.java) {
            SelectionSnapshotBuilder(cache, 2, 10, Duration.ofSeconds(5)).build(DownloadSelection(listOf(content)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SelectionSnapshotBuilder(cache, 100, 10, Duration.ofSeconds(5)).build(DownloadSelection(listOf(content, content)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SelectionSnapshotBuilder(cache, 100, 10, Duration.ofSeconds(5)).build(DownloadSelection(listOf(DownloadSelectionContent("../escape", listOf(source)))))
        }
        assertEquals("unchanged", Files.readString(source))
        Files.list(cache).use { assertEquals(0L, it.count()) }
    }

    @Test
    fun `directory and selected optional content contain only their exact bytes`(@TempDir root: Path) {
        assumeTrue(Files.newDirectoryStream(root).use { it is SecureDirectoryStream<*> })
        val source = Files.createDirectories(root.resolve("source/base/nested"))
        val member = Files.writeString(source.resolve("data.bin"), "base")
        val optional = Files.writeString(root.resolve("soundtrack.bin"), "soundtrack")
        val excluded = Files.writeString(root.resolve("excluded.bin"), "excluded")
        val cache = Files.createDirectory(root.resolve("cache"))
        val snapshot = SelectionSnapshotBuilder(cache, 100, 10, Duration.ofSeconds(5)).build(
            DownloadSelection(listOf(
                DownloadSelectionContent("Base", listOf(source.parent)),
                DownloadSelectionContent("Soundtrack", listOf(optional)))))
        assertEquals(setOf("Base/nested/data.bin", "Soundtrack"), snapshot.hashes.keys)
        assertEquals("base", Files.readString(snapshot.directory.resolve("Base/nested/data.bin")))
        assertEquals("soundtrack", Files.readString(snapshot.directory.resolve("Soundtrack")))
        assertEquals("base", Files.readString(member))
        assertEquals("excluded", Files.readString(excluded))
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.hashes as MutableMap)["extra"] = "invalid" }
    }

    @Test
    fun `source directory cannot contain snapshot cache`(@TempDir root: Path) {
        val cache = Files.createDirectory(root.resolve("cache"))
        assertThrows(IllegalArgumentException::class.java) {
            SelectionSnapshotBuilder(cache, 100, 10, Duration.ofSeconds(5)).build(DownloadSelection(listOf(DownloadSelectionContent("base", listOf(root)))))
        }
    }

    @Test
    fun `directory symbolic links are rejected without adopting their contents`(@TempDir root: Path) {
        assumeTrue(Files.newDirectoryStream(root).use { it is SecureDirectoryStream<*> })
        val source = Files.createDirectory(root.resolve("source"))
        val outside = Files.writeString(root.resolve("outside.bin"), "not selected")
        Files.createSymbolicLink(source.resolve("alias.bin"), outside)
        val cache = Files.createDirectory(root.resolve("cache"))
        assertThrows(IllegalArgumentException::class.java) {
            SelectionSnapshotBuilder(cache, 100, 10, Duration.ofSeconds(5)).build(
                DownloadSelection(listOf(DownloadSelectionContent("base", listOf(source)))))
        }
        assertEquals("not selected", Files.readString(outside))
        Files.list(cache).use { assertEquals(0L, it.count()) }
    }

    @Test
    fun `cancellation cleans only the owned staging leaf`(@TempDir root: Path) {
        val source = Files.writeString(root.resolve("source.bin"), "unchanged")
        val cache = Files.createDirectory(root.resolve("cache"))
        val unrelated = Files.writeString(cache.resolve("unrelated.bin"), "preserve")
        Thread.currentThread().interrupt()
        try {
            assertThrows(IllegalStateException::class.java) {
                SelectionSnapshotBuilder(cache, 100, 10, Duration.ofSeconds(5)).build(
                    DownloadSelection(listOf(DownloadSelectionContent("base", listOf(source)))))
            }
        } finally {
            Thread.interrupted()
        }
        assertEquals("preserve", Files.readString(unrelated))
        assertEquals("unchanged", Files.readString(source))
        Files.list(cache).use { assertEquals(1L, it.count()) }
    }
}
