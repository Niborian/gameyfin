package org.gameyfin.app.libraries

import io.mockk.*
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.gameyfin.app.config.ConfigProperties
import org.gameyfin.app.config.ConfigService
import org.gameyfin.app.core.filesystem.FilesystemScanResult
import org.gameyfin.app.core.filesystem.FilesystemService
import org.gameyfin.app.core.metrics.ScanMetrics
import org.gameyfin.app.core.plugins.PluginService
import org.gameyfin.app.core.plugins.dto.PluginDto
import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.games.entities.GameMetadata
import org.gameyfin.app.games.entities.GameVariant
import org.gameyfin.app.games.entities.VariantContent
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.games.variants.GameVariantGroupingService
import org.gameyfin.app.games.variants.VariantLibraryFixture
import org.gameyfin.app.libraries.entities.DirectoryMapping
import org.gameyfin.app.libraries.entities.IgnoredPath
import org.gameyfin.app.libraries.entities.IgnoredPathGroupedVariantSource
import org.gameyfin.app.libraries.entities.IgnoredPathUserSource
import org.gameyfin.app.libraries.entities.Library
import org.gameyfin.app.libraries.enums.ScanType
import org.gameyfin.app.libraries.scan.LibraryGameProcessor
import org.gameyfin.pluginapi.gamemetadata.GameMetadataProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.pf4j.PluginState
import java.nio.file.Path
import java.sql.SQLException
import java.time.Instant
import kotlin.io.path.Path

class LibraryScanServiceTest {

    private lateinit var libraryRepository: LibraryRepository
    private lateinit var filesystemService: FilesystemService
    private lateinit var libraryCoreService: LibraryCoreService
    private lateinit var libraryGameProcessor: LibraryGameProcessor
    private lateinit var gameRepository: GameRepository
    private lateinit var gameVariantGroupingService: GameVariantGroupingService
    private lateinit var libraryScanService: LibraryScanService
    private lateinit var ignoredPathRepository: IgnoredPathRepository
    private lateinit var pluginService: PluginService
    private lateinit var configService: ConfigService
    private lateinit var meterRegistry: SimpleMeterRegistry

    @BeforeEach
    fun setup() {
        libraryRepository = mockk()
        filesystemService = mockk()
        libraryCoreService = mockk()
        libraryGameProcessor = mockk()
        gameRepository = mockk()
        gameVariantGroupingService = mockk()
        ignoredPathRepository = mockk()
        pluginService = mockk()
        configService = mockk()

        // By default, at least one GameMetadataProvider is started so scans are allowed
        every { pluginService.getAllByTypeAndState(GameMetadataProvider::class, PluginState.STARTED) } returns listOf(
            mockk<PluginDto>()
        )

        // Return default max-concurrency value
        every { configService.get(ConfigProperties.Libraries.Scan.MaxConcurrency) } returns ConfigProperties.Libraries.Scan.MaxConcurrency.default

        meterRegistry = SimpleMeterRegistry()
        libraryScanService = LibraryScanService(
            libraryRepository,
            filesystemService,
            libraryCoreService,
            libraryGameProcessor,
            gameRepository,
            gameVariantGroupingService,
            ignoredPathRepository,
            pluginService,
            configService,
            ScanMetrics(meterRegistry)
        )
        every { gameVariantGroupingService.autoGroupExactMatches(any()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        clearAllMocks()
    }

    @Test
    fun `triggerScan should scan all libraries when libraryIds is null`() {
        val library1 = createTestLibrary(1L)
        val library2 = createTestLibrary(2L)

        every { libraryRepository.findAll() } returns listOf(library1, library2)
        setupSuccessfulQuickScan(library1)
        setupSuccessfulQuickScan(library2)

        libraryScanService.triggerScan(ScanType.QUICK, null)

        Thread.sleep(100)
        verify(atLeast = 1) { filesystemService.scanLibraryForGamefiles(any()) }
    }

    @Test
    fun `triggerScan should scan only specified libraries`() {
        val library1 = createTestLibrary(1L)
        val library2 = createTestLibrary(2L)

        every { libraryRepository.findAllById(listOf(1L)) } returns listOf(library1)
        setupSuccessfulQuickScan(library1)

        libraryScanService.triggerScan(ScanType.QUICK, listOf(1L))

        Thread.sleep(100)
        verify(atLeast = 1) { filesystemService.scanLibraryForGamefiles(library1) }
        verify(exactly = 0) { filesystemService.scanLibraryForGamefiles(library2) }
    }

    @Test
    fun `triggerScan should not start duplicate scan for same library`() {
        val library = createTestLibrary(1L)

        every { libraryRepository.findAllById(listOf(1L)) } returns listOf(library)
        setupDelayedQuickScan(library)

        libraryScanService.triggerScan(ScanType.QUICK, listOf(1L))
        Thread.sleep(50)
        libraryScanService.triggerScan(ScanType.QUICK, listOf(1L))

        Thread.sleep(200)
        verify(exactly = 1) { filesystemService.scanLibraryForGamefiles(library) }
    }

    @Test
    fun `triggerScan should handle quick scan type`() {
        val library = createTestLibrary(1L)

        every { libraryRepository.findAllById(listOf(1L)) } returns listOf(library)
        setupSuccessfulQuickScan(library)

        libraryScanService.triggerScan(ScanType.QUICK, listOf(1L))

        Thread.sleep(100)
        verify(atLeast = 1) { filesystemService.scanLibraryForGamefiles(library) }
    }

    @Test
    fun `triggerScan should handle full scan type`() {
        val library = createTestLibrary(1L)

        every { libraryRepository.findAllById(listOf(1L)) } returns listOf(library)
        setupSuccessfulFullScan(library)

        libraryScanService.triggerScan(ScanType.FULL, listOf(1L))

        Thread.sleep(100)
        verify(atLeast = 1) { filesystemService.scanLibraryForGamefiles(library) }
    }

    @Test
    fun `triggerScan should handle scheduled scan type`() {
        val library = createTestLibrary(1L)

        every { libraryRepository.findAllById(listOf(1L)) } returns listOf(library)
        setupSuccessfulFullScan(library)

        libraryScanService.triggerScan(ScanType.SCHEDULED, listOf(1L))

        Thread.sleep(100)
        verify(atLeast = 1) { filesystemService.scanLibraryForGamefiles(library) }
    }

    @Test
    fun `triggerScan should handle empty library list`() {
        every { libraryRepository.findAll() } returns emptyList()

        libraryScanService.triggerScan(ScanType.QUICK, null)

        Thread.sleep(50)
        verify(exactly = 0) { filesystemService.scanLibraryForGamefiles(any()) }
    }

    @Test
    fun `triggerScan should throw when no GameMetadataProvider plugin is started`() {
        every {
            pluginService.getAllByTypeAndState(
                GameMetadataProvider::class,
                PluginState.STARTED
            )
        } returns emptyList()

        assertThrows<IllegalStateException> {
            libraryScanService.triggerScan(ScanType.QUICK, null)
        }

        verify(exactly = 0) { libraryRepository.findAll() }
        verify(exactly = 0) { filesystemService.scanLibraryForGamefiles(any()) }
    }

    @Test
    fun `triggerScan should handle filesystem scan errors`() {
        val library = createTestLibrary(1L)

        every { libraryRepository.findAllById(listOf(1L)) } returns listOf(library)
        every { filesystemService.scanLibraryForGamefiles(library) } throws RuntimeException("Scan error")

        libraryScanService.triggerScan(ScanType.QUICK, listOf(1L))

        Thread.sleep(100)
        verify(atLeast = 1) { filesystemService.scanLibraryForGamefiles(library) }
    }

    @Test
    fun `quick scan should process new games`() {
        val library = createTestLibrary(1L)
        val newPath = Path("/path/newgame")
        val newGame = createTestGame(1L, newPath.toString())

        every { libraryRepository.findAllById(listOf(1L)) } returns listOf(library)
        setupQuickScanWithNewGames(library, listOf(newPath), newGame)

        libraryScanService.triggerScan(ScanType.QUICK, listOf(1L))

        Thread.sleep(200)
        verify(atLeast = 1) { libraryGameProcessor.processNewGame(newPath, library) }
    }

    @Test
    fun `quick scan should handle unmatched games`() {
        val library = createTestLibrary(1L)
        val unmatchedPath = Path("/path/unmatched")

        every { libraryRepository.findAllById(listOf(1L)) } returns listOf(library)
        setupQuickScanWithUnmatchedGames(library, listOf(unmatchedPath))

        libraryScanService.triggerScan(ScanType.QUICK, listOf(1L))

        Thread.sleep(200)
        verify(atLeast = 1) { libraryGameProcessor.processNewGame(unmatchedPath, library) }
    }

    @Test
    fun `database error in an individual game fails the entire scan`() {
        val library = createTestLibrary(1L)
        val sourcePath = Path("/private/library/game")
        every { libraryRepository.findAllById(listOf(1L)) } returns listOf(library)
        every { filesystemService.scanLibraryForGamefiles(library) } returns FilesystemScanResult(
            newPaths = listOf(sourcePath),
            removedGamePaths = emptyList(),
            removedIgnoredPaths = emptyList()
        )
        every { libraryGameProcessor.processNewGame(sourcePath, library) } throws
            IllegalStateException("private path", SQLException("database unavailable"))

        libraryScanService.triggerScan(ScanType.QUICK, listOf(1L))

        val failures = meterRegistry.find("gameyfin.scans.failures.by.kind")
            .tags("type", "quick", "kind", "database").counter()!!
        var attempts = 0
        while (failures.count() == 0.0 && attempts++ < 100) {
            Thread.sleep(20)
        }
        assertEquals(1.0, failures.count())
        verify(exactly = 0) { libraryRepository.save(any()) }
    }

    @Test
    fun `full scan should update existing games`() {
        val existingGame = createTestGame(1L, "/path/game1")
        val library = createTestLibrary(1L, games = mutableListOf(existingGame))

        every { libraryRepository.findAllById(listOf(1L)) } returns listOf(library)
        setupFullScanWithExistingGames(library, existingGame)

        libraryScanService.triggerScan(ScanType.FULL, listOf(1L))

        Thread.sleep(200)
        verify(atLeast = 1) { libraryGameProcessor.processExistingGame(existingGame) }
    }

    @Test
    fun `repeated quick and full scans preserve attached fixture sources`(@TempDir tempDir: java.nio.file.Path) {
        val fixture = VariantLibraryFixture.create(tempDir)
        val library = Library(id = 1L, name = "Fixture", directories = mutableListOf(
            DirectoryMapping(internalPath = fixture.root.toString())
        ))
        val game = Game(id = 1L, library = library, metadata = GameMetadata(path = fixture.gamePath.toString()))
        listOf("Normal 1.0", "Normal 1.1", "Multiplayer Fix 1.1").forEachIndexed { index, name ->
            val variantPath = fixture.gamePath.resolve(name).toString()
            val variant = GameVariant(
                id = index.toLong() + 1,
                game = game,
                name = name,
                version = if (index == 0) "1.0" else "1.1",
                path = variantPath,
                isDefault = index == 0,
                defaultLocked = index == 0
            )
            variant.contents.add(VariantContent(
                id = index.toLong() + 1,
                variant = variant,
                name = "Base game",
                path = fixture.gamePath.resolve(name).resolve("game.bin").toString(),
                required = true
            ))
            game.variants.add(variant)
        }
        library.games.add(game)
        library.ignoredPaths.add(IgnoredPath(
            path = fixture.ignoredAttachedSourcePath.toString(),
            source = IgnoredPathGroupedVariantSource()
        ))
        library.ignoredPaths.add(IgnoredPath(
            path = fixture.root.resolve("hardlinks").toString(),
            source = IgnoredPathUserSource(mockk())
        ))

        every { configService.get(ConfigProperties.Libraries.Scan.GameFileExtensions) } returns arrayOf("rar", "zip")
        every { configService.get(ConfigProperties.Libraries.Scan.ScanEmptyDirectories) } returns false
        every { libraryRepository.findAllById(listOf(1L)) } returns listOf(library)
        every { libraryGameProcessor.processExistingGame(game) } returns game
        every { gameRepository.findAllById(emptyList<Long>()) } returns emptyList()
        every { libraryCoreService.addGamesToLibrary(emptyList(), library, false) } returns library
        every { libraryRepository.save(library) } returns library

        val scanner = LibraryScanService(
            libraryRepository, FilesystemService(configService), libraryCoreService,
            libraryGameProcessor, gameRepository, gameVariantGroupingService,
            ignoredPathRepository, pluginService, configService, ScanMetrics(meterRegistry)
        )
        val sourceSnapshot = fixture.snapshot()
        val relationshipCounts = Triple(library.games.size, game.variants.size, game.variants.sumOf { it.contents.size })
        val expectedCompletions = mutableMapOf<ScanType, Double>()

        listOf(ScanType.QUICK, ScanType.FULL, ScanType.QUICK, ScanType.FULL).forEach { type ->
            val expected = expectedCompletions.getOrDefault(type, 0.0) + 1.0
            expectedCompletions[type] = expected
            scanner.triggerScan(type, listOf(1L))
            val completed = meterRegistry.find("gameyfin.scans.completed")
                .tag("type", type.name.lowercase()).counter()!!
            var attempts = 0
            while (completed.count() < expected && attempts++ < 100) {
                Thread.sleep(20)
            }
            assertEquals(expected, completed.count())
            Thread.sleep(20) // Let the scan's in-progress marker clear after completion.
        }

        assertEquals(relationshipCounts, Triple(library.games.size, game.variants.size, game.variants.sumOf { it.contents.size }))
        assertEquals(2, library.ignoredPaths.size)
        assertTrue(game.variants.single { it.isDefault }.defaultLocked)
        assertEquals(sourceSnapshot, fixture.snapshot())
        assertEquals(0.0, meterRegistry.find("gameyfin.scans.failed").tag("type", "quick").counter()!!.count())
        assertEquals(0.0, meterRegistry.find("gameyfin.scans.failed").tag("type", "full").counter()!!.count())
        verify(exactly = 0) { libraryGameProcessor.processNewGame(any(), any()) }
        verify(exactly = 2) { libraryGameProcessor.processExistingGame(game) }
        verify(exactly = 4) { libraryRepository.save(library) }
    }

    @Test
    fun `scan should remove deleted games from library`() {
        val removedPath = "/path/removed"
        val removedGame = createTestGame(1L, removedPath)
        val library = createTestLibrary(1L, games = mutableListOf(removedGame))

        every { libraryRepository.findAllById(listOf(1L)) } returns listOf(library)
        setupQuickScanWithRemovedGames(library, listOf(Path(removedPath)))

        libraryScanService.triggerScan(ScanType.QUICK, listOf(1L))

        Thread.sleep(200)
        verify(atLeast = 1) { filesystemService.scanLibraryForGamefiles(library) }
    }

    @Test
    fun `scan should update library timestamp after completion`() {
        val library = createTestLibrary(1L)

        every { libraryRepository.findAllById(listOf(1L)) } returns listOf(library)
        setupSuccessfulQuickScan(library)

        libraryScanService.triggerScan(ScanType.QUICK, listOf(1L))

        Thread.sleep(200)
        verify(atLeast = 1) { libraryRepository.save(library) }
    }

    @Test
    fun `scan should add new games to library after processing`() {
        val library = createTestLibrary(1L)
        val newPath = Path("/path/newgame")
        val newGame = createTestGame(1L, newPath.toString())

        every { libraryRepository.findAllById(listOf(1L)) } returns listOf(library)
        setupQuickScanWithNewGames(library, listOf(newPath), newGame)
        every { gameRepository.findAllById(listOf(1L)) } returns listOf(newGame)

        libraryScanService.triggerScan(ScanType.QUICK, listOf(1L))

        Thread.sleep(200)
        verify(atLeast = 1) { libraryCoreService.addGamesToLibrary(any(), library, false) }
    }

    private fun createTestLibrary(
        id: Long,
        games: MutableList<Game> = mutableListOf(),
        ignoredPaths: MutableList<IgnoredPath> = mutableListOf()
    ): Library {
        var updatedAtTime = Instant.now()
        return mockk<Library>(relaxed = true) {
            every { this@mockk.id } returns id
            every { this@mockk.games } returns games
            every { this@mockk.ignoredPaths } returns ignoredPaths
            every { this@mockk.updatedAt } answers { updatedAtTime }
            every { this@mockk.updatedAt = any() } propertyType Instant::class answers {
                updatedAtTime = value
            }
        }
    }

    private fun createTestGame(id: Long, path: String): Game {
        val metadata = GameMetadata(path = path)
        return mockk<Game>(relaxed = true) {
            every { this@mockk.id } returns id
            every { this@mockk.metadata } returns metadata
        }
    }

    private fun setupSuccessfulQuickScan(library: Library) {
        every { filesystemService.scanLibraryForGamefiles(library) } returns FilesystemScanResult(
            newPaths = emptyList(),
            removedGamePaths = emptyList(),
            removedIgnoredPaths = emptyList()
        )
        every { libraryRepository.save(library) } returns library
        every { gameRepository.findAllById(emptyList<Long>()) } returns emptyList()
        every { libraryCoreService.addGamesToLibrary(any(), library, false) } returns library
    }

    private fun setupDelayedQuickScan(library: Library) {
        every { filesystemService.scanLibraryForGamefiles(library) } answers {
            Thread.sleep(150)
            FilesystemScanResult(
                newPaths = emptyList(),
                removedGamePaths = emptyList(),
                removedIgnoredPaths = emptyList()
            )
        }
        every { libraryRepository.save(library) } returns library
        every { gameRepository.findAllById(emptyList<Long>()) } returns emptyList()
        every { libraryCoreService.addGamesToLibrary(any(), library, false) } returns library
    }

    private fun setupSuccessfulFullScan(library: Library) {
        every { filesystemService.scanLibraryForGamefiles(library) } returns FilesystemScanResult(
            newPaths = emptyList(),
            removedGamePaths = emptyList(),
            removedIgnoredPaths = emptyList()
        )
        every { libraryGameProcessor.processExistingGame(any()) } returns null
        every { libraryRepository.save(library) } returns library
        every { gameRepository.findAllById(emptyList<Long>()) } returns emptyList()
        every { libraryCoreService.addGamesToLibrary(any(), library, false) } returns library
    }

    private fun setupQuickScanWithNewGames(library: Library, newPaths: List<Path>, newGame: Game) {
        every { filesystemService.scanLibraryForGamefiles(library) } returns FilesystemScanResult(
            newPaths = newPaths,
            removedGamePaths = emptyList(),
            removedIgnoredPaths = emptyList()
        )
        every { libraryGameProcessor.processNewGame(any(), library) } returns newGame
        every { gameRepository.findAllById(listOf(newGame.id!!)) } returns listOf(newGame)
        every { libraryCoreService.addGamesToLibrary(listOf(newGame), library, false) } returns library
        every { libraryRepository.save(library) } returns library
    }

    private fun setupQuickScanWithUnmatchedGames(library: Library, unmatchedPaths: List<Path>) {
        every { filesystemService.scanLibraryForGamefiles(library) } returns FilesystemScanResult(
            newPaths = unmatchedPaths,
            removedGamePaths = emptyList(),
            removedIgnoredPaths = emptyList()
        )
        every { libraryGameProcessor.processNewGame(any(), library) } throws IllegalStateException("Could not match")
        every { gameRepository.findAllById(emptyList<Long>()) } returns emptyList()
        every { libraryCoreService.addGamesToLibrary(emptyList(), library, false) } returns library
        every { libraryRepository.save(library) } returns library
    }

    private fun setupFullScanWithExistingGames(library: Library, existingGame: Game) {
        every { filesystemService.scanLibraryForGamefiles(library) } returns FilesystemScanResult(
            newPaths = emptyList(),
            removedGamePaths = emptyList(),
            removedIgnoredPaths = emptyList()
        )
        every { libraryGameProcessor.processExistingGame(existingGame) } returns existingGame
        every { gameRepository.findAllById(emptyList<Long>()) } returns emptyList()
        every { libraryCoreService.addGamesToLibrary(emptyList(), library, false) } returns library
        every { libraryRepository.save(library) } returns library
    }

    private fun setupQuickScanWithRemovedGames(library: Library, removedPaths: List<Path>) {
        every { filesystemService.scanLibraryForGamefiles(library) } returns FilesystemScanResult(
            newPaths = emptyList(),
            removedGamePaths = removedPaths,
            removedIgnoredPaths = emptyList()
        )
        every { gameRepository.findAllById(emptyList<Long>()) } returns emptyList()
        every { libraryCoreService.addGamesToLibrary(emptyList(), library, false) } returns library
        every { libraryRepository.save(library) } returns library
    }
}


