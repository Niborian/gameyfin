package org.gameyfin.app.libraries

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.*
import org.gameyfin.app.config.ConfigProperties
import org.gameyfin.app.config.ConfigService
import org.gameyfin.app.core.filesystem.FilesystemService
import org.gameyfin.app.core.metrics.ScanMetrics
import org.gameyfin.app.core.plugins.PluginService
import org.gameyfin.app.games.GameService
import org.gameyfin.app.games.entities.*
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.games.variants.*
import org.gameyfin.app.libraries.entities.*
import org.gameyfin.app.libraries.scan.LibraryGameProcessor
import org.gameyfin.app.media.ImageService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

/** Real filesystem discovery and variant synchronization; external metadata/DB boundaries are stubbed. */
class VariantScanIntegrityFixtureTest {
    @Test
    fun `full and incremental scans retain canonical game attachments and source files`(@TempDir root: Path) {
        val fixture = VariantLibraryFixture.create(root)
        val config = mockk<ConfigService>()
        every { config.get(ConfigProperties.Libraries.Scan.GameFileExtensions) } returns arrayOf("rar", "zip")
        every { config.get(ConfigProperties.Libraries.Scan.ScanEmptyDirectories) } returns false
        val filesystem = FilesystemService(config)
        val repository = mockk<GameRepository>()
        val libraryRepository = mockk<LibraryRepository>()
        val core = mockk<LibraryCoreService>()
        val metadata = mockk<GameService>()
        val images = mockk<ImageService>(relaxed = true)
        val grouping = mockk<GameVariantGroupingService>()
        val ignored = mockk<IgnoredPathRepository>()
        val plugins = mockk<PluginService>()
        val library = Library(id = 71, name = "Integrity fixture", directories = mutableListOf(
            DirectoryMapping(internalPath = root.toString())
        ))
        val game = Game(id = 1, library = library, metadata = GameMetadata(path = fixture.gamePath.toString()))
        library.games.add(game)
        library.ignoredPaths.add(IgnoredPath(path = fixture.ignoredAttachedSourcePath.toString(), source = IgnoredPathGroupedVariantSource()))
        library.ignoredPaths.add(IgnoredPath(path = root.resolve("hardlinks").toString(), source = IgnoredPathUserSource(mockk())))
        every { repository.save(game) } returns game
        every { metadata.updateMetadata(game) } returns game
        every { grouping.autoGroupExactMatches(library) } returns 0
        every { core.addGamesToLibrary(emptyList(), library, false) } returns library
        every { libraryRepository.save(library) } returns library
        val discovery = GameVariantDiscoveryService(VariantMetadataParser())
        val variants = GameVariantService(repository, filesystem, HardlinkMirrorService(root.resolve("storage").toString()))
        variants.syncVariants(game, discovery.discover(fixture.gamePath), library)
        assertEquals("1.1", game.variants.single { it.isDefault }.version)
        val pinned = game.variants.single { it.name == "Normal" && it.version == "1.0" }
        pinned.defaultLocked = true
        pinned.scanManaged = false
        val attached = VariantContent(
            variant = pinned, type = VariantContentType.DLC, name = "Attached source",
            path = fixture.ignoredAttachedSourcePath.toString(), required = false
        )
        pinned.contents.add(attached)
        val processor = LibraryGameProcessor(metadata, images, filesystem, discovery, grouping, variants)
        val metrics = SimpleMeterRegistry()
        val scanner = LibraryScanService(libraryRepository, filesystem, core, processor, repository, grouping,
            ignored, plugins, config, ScanMetrics(metrics))
        val before = fixture.snapshot()
        val expectedContent = game.variants.associate { it.name + it.version to it.contents.map { content -> content.path }.sorted() }

        // Invoke synchronous scan entry points so assertions cannot race completion/in-progress cleanup.
        val quick = LibraryScanService::class.java.getDeclaredMethod("quickScan", Library::class.java).apply { isAccessible = true }
        val full = LibraryScanService::class.java.getDeclaredMethod("fullScan", Library::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        repeat(2) {
            quick.invoke(scanner, library)
            full.invoke(scanner, library, false)
        }

        assertEquals(1, library.games.size)
        assertEquals(3, game.variants.size)
        assertEquals(expectedContent, game.variants.associate { it.name + it.version to it.contents.map { content -> content.path }.sorted() })
        assertTrue(game.variants.single { it.isDefault } === pinned)
        assertTrue(pinned.contents.any { it === attached })
        assertEquals(2, library.ignoredPaths.size)
        assertEquals(before, fixture.snapshot())
        assertEquals(2.0, metrics.find("gameyfin.scans.completed").tag("type", "quick").counter()!!.count())
        assertEquals(2.0, metrics.find("gameyfin.scans.completed").tag("type", "full").counter()!!.count())
        verify(exactly = 0) { metadata.matchFromFile(any(), any()) }
        verify(exactly = 0) { metadata.create(any()) }
    }
}
