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
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameVariantServiceTest {

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

    private fun discoveredVariants(gamePath: Path): DiscoveredGameVariants = DiscoveredGameVariants(
        gamePath,
        listOf("1.0", "1.1").map { version ->
            ParsedVariantMetadata("Normal", version, gamePath.resolve("Normal $version"), emptySet(), null, null, null, emptyList())
        } + ParsedVariantMetadata("Multiplayer Fix", "1.1", gamePath.resolve("Multiplayer Fix 1.1"), setOf("multiplayer"), null, null, null, emptyList())
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
