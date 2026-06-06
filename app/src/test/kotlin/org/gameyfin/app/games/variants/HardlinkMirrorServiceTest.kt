package org.gameyfin.app.games.variants

import org.gameyfin.app.games.entities.VariantLinkStatus
import org.gameyfin.app.libraries.entities.Library
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HardlinkMirrorServiceTest {
    @Test
    fun `mirror should create managed copy without moving source`(@TempDir tempDir: Path) {
        val sourceRoot = tempDir.resolve("source").createDirectory()
        val sourceFile = sourceRoot.resolve("game.bin")
        sourceFile.writeText("game data")
        val storageRoot = tempDir.resolve("data").createDirectory()
        val service = HardlinkMirrorService(storageRoot.toString())
        val library = Library(id = 7L, name = "Library")

        val result = service.mirror(sourceRoot, library, sourceRoot, "Normal-1.0")

        assertTrue(Files.exists(sourceFile))
        assertEquals("game data", result.path.resolve("game.bin").readText())
        assertTrue(result.status == VariantLinkStatus.HARDLINKED || result.status == VariantLinkStatus.COPIED_FALLBACK)
    }
}
