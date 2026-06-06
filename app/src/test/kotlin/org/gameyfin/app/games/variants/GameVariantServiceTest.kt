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

class GameVariantServiceTest {

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
}
