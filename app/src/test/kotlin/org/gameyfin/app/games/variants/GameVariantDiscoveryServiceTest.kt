package org.gameyfin.app.games.variants

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class GameVariantDiscoveryServiceTest {
    private val discoveryService = GameVariantDiscoveryService(VariantMetadataParser())

    @Test
    fun `discover should keep legacy folder as one normal variant`(@TempDir tempDir: Path) {
        val gamePath = tempDir.resolve("GameName").createDirectory()
        gamePath.resolve("data").createDirectory()

        val result = discoveryService.discover(gamePath)

        assertEquals(gamePath, result.gamePath)
        assertEquals(1, result.variants.size)
        assertEquals("Normal", result.variants.first().name)
        assertEquals("0", result.variants.first().version)
        assertEquals(gamePath, result.variants.first().path)
    }

    @Test
    fun `discover should group versioned child folders as variants`(@TempDir tempDir: Path) {
        val gamePath = tempDir.resolve("GameName").createDirectory()
        gamePath.resolve("Normal1.0").createDirectory()
        gamePath.resolve("Multiplayer1.0").createDirectory()
            .resolve("metadata.txt")
            .writeText("tags=multiplayer")

        val result = discoveryService.discover(gamePath)

        assertEquals(gamePath, result.gamePath)
        assertEquals(listOf("Multiplayer", "Normal"), result.variants.map { it.name }.sorted())
        assertEquals(setOf("multiplayer"), result.variants.first { it.name == "Multiplayer" }.tags)
    }

    @Test
    fun `discover should treat files as one normal variant`(@TempDir tempDir: Path) {
        val gamePath = tempDir.resolve("GameName.zip").createFile()

        val result = discoveryService.discover(gamePath)

        assertEquals(gamePath, result.gamePath)
        assertEquals("Normal", result.variants.single().name)
        assertEquals(gamePath, result.variants.single().contents.single().path)
    }
}
