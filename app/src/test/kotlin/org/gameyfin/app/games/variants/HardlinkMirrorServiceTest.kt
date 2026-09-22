package org.gameyfin.app.games.variants

import org.gameyfin.app.games.entities.VariantLinkStatus
import org.gameyfin.app.libraries.entities.Library
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
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
        val service = HardlinkMirrorService(storageRoot.toString())
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
        val service = HardlinkMirrorService(storageRoot.toString())
        val library = Library(id = 7L, name = "Library")

        val exception = assertFailsWith<IllegalArgumentException> {
            service.mirror(tempDir.resolve("missing-source"), library, tempDir, "Normal-1.0")
        }

        assertTrue(exception.message!!.contains("does not exist"))
    }

    @Test
    fun `cross filesystem mirror fails without copying source`(@TempDir tempDir: Path) {
        val otherStore = Path.of("/dev/shm")
        assumeTrue(Files.isDirectory(otherStore) && Files.isWritable(otherStore))
        assumeTrue(Files.getFileStore(otherStore) != Files.getFileStore(tempDir))
        val sourceRoot = Files.createTempDirectory(otherStore, "gameyfin-hardlink-test-")
        try {
            val source = sourceRoot.resolve("game.bin")
            source.writeText("torrent data")
            val storageRoot = tempDir.resolve("data").createDirectory()
            val service = HardlinkMirrorService(storageRoot.toString())
            val library = Library(id = 7L, name = "Library")

            val exception = assertFailsWith<IllegalArgumentException> {
                service.mirror(source, library, sourceRoot, "Normal-1.0")
            }

            assertTrue(exception.message!!.contains("same filesystem"))
            assertEquals("torrent data", source.readText())
            assertTrue(Files.notExists(storageRoot.resolve("library-hardlinks/library-7")))
        } finally {
            Files.deleteIfExists(sourceRoot.resolve("game.bin"))
            Files.deleteIfExists(sourceRoot)
        }
    }
}
