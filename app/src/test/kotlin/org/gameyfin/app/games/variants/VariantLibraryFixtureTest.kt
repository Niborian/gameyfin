package org.gameyfin.app.games.variants

import org.gameyfin.app.games.entities.VariantContentType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VariantLibraryFixtureTest {
    private val discovery = GameVariantDiscoveryService(VariantMetadataParser())

    @Test
    fun `fixture represents managed variants without changing its source files`(@TempDir tempDir: Path) {
        val fixture = VariantLibraryFixture.create(tempDir)
        val before = fixture.snapshot()

        val variants = discovery.discover(fixture.gamePath).variants

        assertEquals(listOf("Multiplayer Fix", "Normal", "Normal"), variants.map { it.name })
        assertTrue(variants.flatMap { it.contents }.map { it.type }.containsAll(VariantContentType.entries))
        assertTrue(Files.isSameFile(fixture.hardlinkSource, fixture.hardlinkMirror))
        assertTrue(Files.exists(fixture.ignoredAttachedSourcePath))
        assertEquals(before, fixture.snapshot(), "fixture scans must not modify torrent-managed source paths")
    }
}
