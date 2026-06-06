package org.gameyfin.app.games.variants

import org.gameyfin.app.games.entities.VariantContentType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VariantMetadataParserTest {
    private val parser = VariantMetadataParser()

    @Test
    fun `parseFolderName should split variant name and version`() {
        val normal = parser.parseFolderName("Normal1.3")
        val multiplayer = parser.parseFolderName("Multiplayer1.0")

        assertEquals("Normal", normal.name)
        assertEquals("1.3", normal.version)
        assertEquals("Multiplayer", multiplayer.name)
        assertEquals("1.0", multiplayer.version)
    }

    @Test
    fun `parse should use metadata overrides and content declarations`(@TempDir tempDir: Path) {
        val variantPath = tempDir.resolve("Normal1.0").createDirectory()
        variantPath.resolve("metadata.txt").writeText(
            """
            variant=Speedrun
            version=1.5
            tags=multiplayer, modded
            steamAppId=12345
            launchArgs=--fast
            patchInfo=Patch notes
            content.server.type=DEDICATED_SERVER
            content.server.name=Dedicated server
            content.server.path=server
            content.server.required=false
            content.server.defaultSelected=false
            content.server.tags=multiplayer
            """.trimIndent()
        )

        val result = parser.parse(variantPath)

        assertEquals("Speedrun", result.name)
        assertEquals("1.5", result.version)
        assertEquals(setOf("multiplayer", "modded"), result.tags)
        assertEquals("12345", result.steamAppId)
        assertEquals("--fast", result.launchArgs)
        assertEquals("Patch notes", result.patchInfo)
        assertEquals(1, result.contents.size)
        assertEquals(VariantContentType.DEDICATED_SERVER, result.contents.first().type)
        assertEquals(variantPath.resolve("server").normalize(), result.contents.first().path)
    }

    @Test
    fun `parse should fall back when metadata is absent`(@TempDir tempDir: Path) {
        val variantPath = tempDir.resolve("Modpack1.2").createDirectory()

        val result = parser.parse(variantPath)

        assertEquals("Modpack", result.name)
        assertEquals("1.2", result.version)
        assertTrue(result.tags.isEmpty())
        assertNull(result.steamAppId)
        assertTrue(result.contents.isEmpty())
    }

    @Test
    fun `version comparator should order numeric versions`() {
        val versions = listOf("1.0", "1.3", "1.2", "1.10")

        assertEquals("1.10", VariantVersionComparator.newest(versions))
    }
}
