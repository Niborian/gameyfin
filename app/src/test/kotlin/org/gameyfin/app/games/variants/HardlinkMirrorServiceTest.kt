package org.gameyfin.app.games.variants


import org.gameyfin.app.games.entities.VariantLinkStatus
import org.gameyfin.app.libraries.entities.Library
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.FileSystems
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HardlinkMirrorServiceTest {
    @Test
    fun `mirror should create a hardlinked managed mirror without changing source files`(@TempDir tempDir: Path) {
        val sourceRoot = tempDir.resolve("source").createDirectory()
        val sourceFile = sourceRoot.resolve("game.bin")
        sourceFile.writeText("game data")
        val storageRoot = tempDir.resolve("data").createDirectory()
        val service = HardlinkMirrorService(storageRoot.toString(), org.gameyfin.app.core.download.files.DownloadPathLeases())
        val library = Library(id = 7L, name = "Library")

        val result = service.mirror(sourceRoot, library, sourceRoot, "Normal-1.0")
        val mirroredFile = result.path.resolve("game.bin")
        service.mirror(sourceRoot, library, sourceRoot, "Normal-1.0")

        assertTrue(Files.exists(sourceFile))
        assertEquals("game data", mirroredFile.readText())
        assertTrue(Files.isSameFile(sourceFile, mirroredFile))
        assertEquals(VariantLinkStatus.HARDLINKED, result.status)

        Files.delete(mirroredFile)
        Files.delete(result.path)
        assertTrue(Files.exists(sourceFile))
        assertEquals("game data", sourceFile.readText())
    }

    @Test
    fun `mirror should explain when hardlink storage is unavailable`(@TempDir tempDir: Path) {
        val storageRoot = tempDir.resolve("data").createDirectory()
        val service = HardlinkMirrorService(storageRoot.toString(), org.gameyfin.app.core.download.files.DownloadPathLeases())
        val library = Library(id = 7L, name = "Library")

        val exception = assertFailsWith<IllegalArgumentException> {
            service.mirror(tempDir.resolve("missing-source"), library, tempDir, "Normal-1.0")
        }

        assertTrue(exception.message!!.contains("does not exist"))
    }

    @Test
    fun `cross filesystem mirror falls back to direct source without copying`(@TempDir tempDir: Path) {
        val otherStore = Path.of("/dev/shm")
        assumeTrue(Files.isDirectory(otherStore) && Files.isWritable(otherStore))
        assumeTrue(Files.getFileStore(otherStore) != Files.getFileStore(tempDir))
        val sourceRoot = Files.createTempDirectory(otherStore, "gameyfin-hardlink-test-")
        try {
            val source = sourceRoot.resolve("game.bin")
            source.writeText("torrent data")
            val storageRoot = tempDir.resolve("data").createDirectory()
            val service = HardlinkMirrorService(storageRoot.toString(), org.gameyfin.app.core.download.files.DownloadPathLeases())
            val library = Library(id = 7L, name = "Library")

            val result = service.mirror(source, library, sourceRoot, "Normal-1.0")

            assertEquals(source, result.path)
            assertEquals(VariantLinkStatus.DIRECT, result.status)
            assertTrue(result.fallbackReason!!.contains("same filesystem"))
            assertEquals("torrent data", source.readText())
            assertTrue(Files.notExists(storageRoot.resolve("library-hardlinks/library-7")))
        } finally {
            Files.deleteIfExists(sourceRoot.resolve("game.bin"))
            Files.deleteIfExists(sourceRoot)
        }
    }

    @Test
    fun `unavailable mirror storage retains original source without copying`(@TempDir tempDir: Path) {
        val source = tempDir.resolve("source.bin").also { it.writeText("torrent payload") }
        val storage = tempDir.resolve("storage").also { it.writeText("existing storage file") }
        val service = HardlinkMirrorService(storage.toString())
        val result = service.mirror(source, Library(id = 7L, name = "Library"), source, "Normal-1.0")
        assertEquals(source, result.path)
        assertEquals(VariantLinkStatus.DIRECT, result.status)
        assertTrue(result.fallbackReason!!.contains("Hardlink unavailable"))
        assertEquals("torrent payload", source.readText())
        assertEquals("existing storage file", storage.readText())
    }

    @Test
    fun `unsupported cross provider hardlinks use the direct source`(@TempDir tempDir: Path) {
        val archive = tempDir.resolve("fixture.zip")
        FileSystems.newFileSystem(URI.create("jar:${archive.toUri()}"), mapOf("create" to "true")).use { zip ->
            val source = zip.getPath("/source.bin").also { it.writeText("separate filesystem payload") }
            val storage = tempDir.resolve("storage").createDirectory()
            val result = HardlinkMirrorService(storage.toString()).mirror(
                source, Library(id = 7L, name = "Library"), source, "Normal-1.0"
            )
            assertEquals(source, result.path)
            assertEquals(VariantLinkStatus.DIRECT, result.status)
            assertTrue(result.fallbackReason!!.contains("same filesystem"))
            assertEquals("separate filesystem payload", source.readText())
            assertTrue(Files.notExists(storage.resolve("library-hardlinks/library-7")))
        }
    }

    @Test
    fun `mirror storage inside source falls back before creating source directories`(@TempDir tempDir: Path) {
        val source = tempDir.resolve("source").createDirectory()
        source.resolve("payload.bin").writeText("torrent payload")
        val result = HardlinkMirrorService(source.resolve("data").toString()).mirror(
            source, Library(id = 7L, name = "Library"), source, "Normal-1.0"
        )
        assertEquals(VariantLinkStatus.DIRECT, result.status)
        assertEquals(source, result.path)
        assertTrue(result.fallbackReason!!.contains("separate from source"))
        assertTrue(Files.notExists(source.resolve("data")))
        assertEquals("torrent payload", source.resolve("payload.bin").readText())
    }

    @Test
    fun `a managed mirror used as source cannot be deleted during rescan`(@TempDir tempDir: Path) {
        val storage = tempDir.resolve("storage").createDirectory()
        val source = storage.resolve("library-hardlinks/library-7/source/Normal-1.0")
        Files.createDirectories(source)
        source.resolve("payload.bin").writeText("existing source payload")
        val result = HardlinkMirrorService(storage.toString()).mirror(
            source, Library(id = 7L, name = "Library"), source.parent, "Normal-1.0"
        )
        assertEquals(VariantLinkStatus.DIRECT, result.status)
        assertEquals(source, result.path)
        assertEquals("existing source payload", source.resolve("payload.bin").readText())
    }
}
