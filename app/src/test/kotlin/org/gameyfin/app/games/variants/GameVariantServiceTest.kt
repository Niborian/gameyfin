package org.gameyfin.app.games.variants

import io.mockk.every
import io.mockk.mockk
import org.gameyfin.app.core.filesystem.FilesystemService
import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.games.entities.GameMetadata
import org.gameyfin.app.games.entities.GameVariant
import org.gameyfin.app.games.entities.VariantContent
import org.gameyfin.app.games.entities.VariantContentType
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.libraries.entities.Library
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameVariantServiceTest {

    @Test
    fun `scan never recreates or removes a quarantined mirror record`() {
        val (service, repository, filesystemService, library, game, gamePath) = variantTestContext()
        val quarantined = GameVariant(game = game, name = "Normal", version = "1.0", path = "/managed/old",
            retirementState = org.gameyfin.app.games.entities.VariantRetirementState.ARCHIVED,
            quarantinePath = "/managed/quarantine/payload")
        game.variants.add(quarantined)
        every { repository.save(game) } returns game
        every { filesystemService.calculateFileSize(any()) } returns 0L
        service.syncVariants(game, discoveredVariants(gamePath), library)
        assertEquals("/managed/old", quarantined.path)
        assertEquals("/managed/quarantine/payload", quarantined.quarantinePath)
        io.mockk.verify(exactly = 0) { filesystemService.calculateFileSize("/managed/old") }
        service.syncVariants(game, DiscoveredGameVariants(gamePath, emptyList()), library)
        assertEquals(listOf(quarantined), game.variants)
    }

    @Test
    fun `syncVariants should select the newest Normal version by default`() {
        val (service, repository, filesystemService, library, game, gamePath) = variantTestContext()
        every { repository.save(game) } returns game
        every { filesystemService.calculateFileSize(any()) } returns 0L

        service.syncVariants(game, discoveredVariants(gamePath), library)

        assertEquals("1.1", game.variants.single { it.isDefault }.version)
        assertTrue(game.variants.single { it.name == "Normal" && it.version == "1.1" }.isLatestForVariant)
    }

    @Test
    fun `syncVariants should preserve an administrator pinned default`() {
        val (service, repository, filesystemService, library, game, gamePath) = variantTestContext()
        val pinned = GameVariant(game = game, name = "Normal", version = "1.0", path = gamePath.resolve("Normal 1.0").toString())
        pinned.isDefault = true
        pinned.defaultLocked = true
        game.variants.add(pinned)
        every { repository.save(game) } returns game
        every { filesystemService.calculateFileSize(any()) } returns 0L

        service.syncVariants(game, discoveredVariants(gamePath), library)

        assertEquals("1.0", game.variants.single { it.isDefault }.version)
        assertTrue(game.variants.single { it.version == "1.0" }.defaultLocked)
    }

    @Test
    fun `syncVariants should preserve an administrator Steam app ID override`() {
        val (service, repository, filesystemService, library, game, gamePath) = variantTestContext()
        every { repository.save(game) } returns game
        every { filesystemService.calculateFileSize(any()) } returns 0L

        service.syncVariants(game, discoveredVariants(gamePath), library)
        val manuallyLinked = game.variants.single { it.name == "Normal" && it.version == "1.0" }
        manuallyLinked.steamAppId = "1307550"
        manuallyLinked.steamAppIdManualOverride = true

        val scannedWithDifferentId = DiscoveredGameVariants(
            gamePath,
            discoveredVariants(gamePath).variants.map { it.copy(steamAppId = "999999") }
        )
        service.syncVariants(game, scannedWithDifferentId, library)

        assertEquals("1307550", manuallyLinked.steamAppId)
    }

    @Test
    fun `syncVariants should remain stable across a repeated scan of attached variants`() {
        val (service, repository, filesystemService, library, game, gamePath) = variantTestContext()
        val discovery = discoveredVariantsWithContent(gamePath)
        every { repository.save(game) } returns game
        every { filesystemService.calculateFileSize(any()) } returns 0L

        service.syncVariants(game, discovery, library)
        val pinnedVariant = game.variants.single { it.name == "Normal" && it.version == "1.0" }
        pinnedVariant.defaultLocked = true
        val firstScan = game.variants.associate { variant ->
            "${variant.name}:${variant.version}" to variant.contents.map { "${it.type}:${it.name}:${it.path}" }.sorted()
        }

        service.syncVariants(game, discovery, library)
        val secondScan = game.variants.associate { variant ->
            "${variant.name}:${variant.version}" to variant.contents.map { "${it.type}:${it.name}:${it.path}" }.sorted()
        }

        assertEquals(firstScan, secondScan)
        assertEquals(3, game.variants.size)
        assertEquals(6, game.variants.sumOf { it.contents.size })
        assertEquals("1.0", game.variants.single { it.isDefault }.version)
        assertTrue(game.variants.single { it.version == "1.0" }.defaultLocked)
    }

    @Test
    fun `syncVariants should not overwrite unmanaged variant for same scanner path`() {
        val gameRepository = mockk<GameRepository>()
        val filesystemService = mockk<FilesystemService>()
        val hardlinkMirrorService = mockk<HardlinkMirrorService>()
        val service = GameVariantService(gameRepository, filesystemService, hardlinkMirrorService)
        val library = Library(id = 1L, name = "Games")
        val gamePath = Path.of("/mnt/Games/Craftopia.v2025.07.25.rar")
        val game = Game(
            id = 1L,
            library = library,
            metadata = GameMetadata(path = gamePath.toString())
        )
        val manualVariant = GameVariant(
            game = game,
            name = "Normal",
            version = "2025.07.25",
            path = gamePath.toString(),
            scanManaged = false,
            isDefault = true,
            isLatestForVariant = true
        )
        manualVariant.contents.add(
            VariantContent(
                variant = manualVariant,
                type = VariantContentType.PATCH,
                name = "Patch",
                path = "/mnt/Games/Craftopia",
                required = false,
                defaultSelected = false
            )
        )
        game.variants.add(manualVariant)

        every { gameRepository.save(game) } returns game

        val result = service.syncVariants(
            game,
            DiscoveredGameVariants(
                gamePath = gamePath,
                variants = listOf(
                    ParsedVariantMetadata(
                        name = "Normal",
                        version = "0",
                        path = gamePath,
                        tags = emptySet(),
                        steamAppId = null,
                        launchArgs = null,
                        patchInfo = null,
                        contents = emptyList()
                    )
                )
            ),
            library
        )

        assertEquals(1, result.variants.size)
        assertEquals("2025.07.25", result.variants.single().version)
        assertFalse(result.variants.single().scanManaged)
        assertEquals(VariantContentType.PATCH, result.variants.single().contents.single().type)
    }
    private fun variantTestContext(): VariantTestContext {
        val repository = mockk<GameRepository>()
        val filesystemService = mockk<FilesystemService>()
        val service = GameVariantService(repository, filesystemService, mockk())
        val library = Library(id = 1L, name = "Games")
        val gamePath = Path.of("/mnt/Games/Example Game")
        val game = Game(id = 1L, library = library, metadata = GameMetadata(path = gamePath.toString()))
        return VariantTestContext(service, repository, filesystemService, library, game, gamePath)
    }

    @Test
    fun `rescan preserves attached content when discovered variant has the same key`(@TempDir fixture: Path) {
        val (service, repository, filesystem, library, game, gamePath) = variantTestContext()
        val source = Files.writeString(fixture.resolve("normal.zip"), "torrent managed normal version")
        val dlc = Files.writeString(fixture.resolve("dlc.zip"), "shared DLC payload")
        val patch = Files.writeString(fixture.resolve("patch.zip"), "optional patch payload")
        val originalFiles = listOf(source, dlc, patch).associateWith {
            Files.readString(it) to Files.getLastModifiedTime(it)
        }
        val attached = GameVariant(
            game = game, name = "Normal", version = "1.0", path = source.toString(),
            scanManaged = false, isDefault = true, defaultLocked = true
        )
        val content = VariantContent(
            variant = attached, type = VariantContentType.DLC, name = "Shared DLC",
            path = dlc.toString(), required = false, defaultSelected = false
        )
        attached.contents.add(content)
        attached.contents.add(VariantContent(
            variant = attached, type = VariantContentType.PATCH, name = "Patch",
            path = patch.toString(), required = false, defaultSelected = false
        ))
        game.variants.add(attached)
        every { repository.save(game) } returns game
        every { filesystem.calculateFileSize(any()) } returns 0L

        repeat(2) { service.syncVariants(game, discoveredVariantsWithContent(gamePath), library) }

        assertEquals(3, game.variants.size)
        assertTrue(game.variants.single { it.version == "1.0" } === attached)
        assertEquals(source.toString(), attached.path)
        assertFalse(attached.scanManaged)
        assertTrue(attached.contents.single { it.type == VariantContentType.DLC } === content)
        assertEquals(dlc.toString(), content.path)
        assertEquals(patch.toString(), attached.contents.single { it.type == VariantContentType.PATCH }.path)
        assertTrue(game.variants.single { it.isDefault } === attached)
        originalFiles.forEach { (path, snapshot) ->
            assertTrue(Files.exists(path))
            assertEquals(snapshot.first, Files.readString(path))
            assertEquals(snapshot.second, Files.getLastModifiedTime(path))
        }
    }

    @Test
    fun `rescan keeps an attached pinned default absent from discovery`() {
        val (service, repository, filesystem, library, game, gamePath) = variantTestContext()
        val attached = GameVariant(
            game = game, name = "Normal", version = "0.9", path = "/fixture/torrents/older.zip",
            scanManaged = false, isDefault = true, defaultLocked = true
        )
        game.variants.add(attached)
        every { repository.save(game) } returns game
        every { filesystem.calculateFileSize(any()) } returns 0L

        repeat(2) { service.syncVariants(game, discoveredVariants(gamePath), library) }

        assertEquals(4, game.variants.size)
        assertTrue(game.variants.single { it.isDefault } === attached)
        assertTrue(game.variants.single { it.name == "Normal" && it.version == "1.1" }.isLatestForVariant)
    }

    @Test
    fun `latest Normal default includes a manually attached version outside discovery`() {
        val (service, repository, filesystem, library, game, gamePath) = variantTestContext()
        val attached = GameVariant(game = game, name = "Normal", version = "2.0",
            path = "/fixture/torrents/newer.zip", scanManaged = false)
        game.variants.add(attached)
        every { repository.save(game) } returns game
        every { filesystem.calculateFileSize(any()) } returns 0L

        repeat(2) { service.syncVariants(game, discoveredVariants(gamePath), library) }

        assertTrue(game.variants.single { it.isDefault } === attached)
        assertTrue(game.variants.single { it.name == "Normal" && it.isLatestForVariant } === attached)
        assertFalse(game.variants.single { it.name == "Normal" && it.version == "1.1" }.isLatestForVariant)
    }

    private fun discoveredVariants(gamePath: Path): DiscoveredGameVariants = DiscoveredGameVariants(
        gamePath,
        listOf("1.0", "1.1").map { version ->
            ParsedVariantMetadata("Normal", version, gamePath.resolve("Normal $version"), emptySet(), null, null, null, emptyList())
        } + ParsedVariantMetadata("Multiplayer Fix", "1.1", gamePath.resolve("Multiplayer Fix 1.1"), setOf("multiplayer"), null, null, null, emptyList())
    )

    private fun discoveredVariantsWithContent(gamePath: Path): DiscoveredGameVariants = DiscoveredGameVariants(
        gamePath,
        listOf("1.0", "1.1").map { version ->
            val variantPath = gamePath.resolve("Normal $version")
            ParsedVariantMetadata(
                "Normal", version, variantPath, emptySet(), null, null, null,
                listOf(
                    ParsedVariantContent("base", VariantContentType.BASE, "Base game", variantPath, true, true, emptySet()),
                    ParsedVariantContent("dlc", VariantContentType.DLC, "DLC", variantPath.resolve("dlc"), false, false, emptySet())
                )
            )
        } + ParsedVariantMetadata(
            "Multiplayer Fix", "1.1", gamePath.resolve("Multiplayer Fix 1.1"), setOf("multiplayer"), null, null, null,
            listOf(
                ParsedVariantContent("base", VariantContentType.BASE, "Base game", gamePath.resolve("Multiplayer Fix 1.1"), true, true, emptySet()),
                ParsedVariantContent("archive", VariantContentType.EXTRA, "Archives", gamePath.resolve("Multiplayer Fix 1.1/archives"), false, false, emptySet())
            )
        )
    )

    private data class VariantTestContext(
        val service: GameVariantService,
        val repository: GameRepository,
        val filesystemService: FilesystemService,
        val library: Library,
        val game: Game,
        val gamePath: Path
    )
}
