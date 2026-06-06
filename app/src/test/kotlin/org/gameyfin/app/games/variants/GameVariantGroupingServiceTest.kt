package org.gameyfin.app.games.variants

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import org.gameyfin.app.core.filesystem.FilesystemService
import org.gameyfin.app.core.plugins.management.PluginManagementEntry
import org.gameyfin.app.games.dto.GroupGameAsVariantRequestDto
import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.games.entities.GameMetadata
import org.gameyfin.app.games.entities.VariantContentType
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.libraries.IgnoredPathRepository
import org.gameyfin.app.libraries.entities.IgnoredPath
import org.gameyfin.app.libraries.entities.Library
import org.gameyfin.pluginapi.gamemetadata.Platform
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameVariantGroupingServiceTest {

    private lateinit var gameRepository: GameRepository
    private lateinit var ignoredPathRepository: IgnoredPathRepository
    private lateinit var filesystemService: FilesystemService
    private lateinit var service: GameVariantGroupingService

    @BeforeEach
    fun setup() {
        gameRepository = mockk()
        ignoredPathRepository = mockk()
        filesystemService = mockk()
        service = GameVariantGroupingService(gameRepository, ignoredPathRepository, filesystemService)
    }

    @Test
    fun `getGroupingSuggestions should mark shared provider ids as auto group`() {
        val library = createLibrary()
        val plugin = PluginManagementEntry("igdb")
        val target = createGame(1L, library, "/mnt/Games/Craftopia", plugin, "123")
        val source = createGame(2L, library, "/mnt/Games/Craftopia.v2025.07.25.rar", plugin, "123")
        library.games.addAll(listOf(target, source))

        every { gameRepository.findAllByLibraryId(1L) } returns listOf(target, source)
        every { filesystemService.calculateFileSize(any()) } returns 2048L

        val suggestions = service.getGroupingSuggestions(1L)

        assertEquals(1, suggestions.size)
        assertEquals(100, suggestions.single().confidence)
        assertTrue(suggestions.single().autoGroup)
        assertEquals(1L, suggestions.single().targetGameId)
        assertEquals(2L, suggestions.single().sourceGameId)
        assertEquals("Normal", suggestions.single().suggestedVariantName)
        assertEquals("2025.07.25", suggestions.single().suggestedVariantVersion)
    }

    @Test
    fun `groupGameAsVariant should move source game into external variant without moving files`() {
        val library = createLibrary()
        val plugin = PluginManagementEntry("igdb")
        val target = createGame(1L, library, "/mnt/Games/Craftopia", plugin, "123")
        val sourcePath = "/mnt/Games/Craftopia.Multiplayer.v2025.07.25.rar"
        val source = createGame(2L, library, sourcePath, plugin, "123")
        library.games.addAll(listOf(target, source))

        every { gameRepository.findById(1L) } returns Optional.of(target)
        every { gameRepository.findById(2L) } returns Optional.of(source)
        every { filesystemService.calculateFileSize(sourcePath) } returns 4096L
        every { ignoredPathRepository.findByPath(sourcePath) } returns null
        every { ignoredPathRepository.save(any()) } answers { firstArg<IgnoredPath>() }
        every { gameRepository.delete(source) } just Runs
        every { gameRepository.flush() } just Runs
        every { gameRepository.save(target) } returns target

        val result = service.groupGameAsVariant(
            targetGameId = 1L,
            request = GroupGameAsVariantRequestDto(
                sourceGameId = 2L,
                variantName = "Multiplayer",
                version = "2025.07.25",
                contentName = "Multiplayer patched game",
                contentType = VariantContentType.BASE,
                required = true,
                defaultSelected = true,
                tags = setOf("multiplayer"),
                steamAppId = null,
                launchArgs = null,
                patchInfo = null
            )
        )

        assertEquals(target, result)
        assertFalse(library.games.any { it.id == source.id })
        assertEquals(sourcePath, library.ignoredPaths.single().path)
        assertEquals(1, target.variants.size)
        assertEquals(sourcePath, target.variants.single().path)
        assertEquals("Multiplayer", target.variants.single().name)
        assertEquals("2025.07.25", target.variants.single().version)
        assertFalse(target.variants.single().scanManaged)
        assertEquals(VariantContentType.BASE, target.variants.single().contents.single().type)
        assertEquals("Multiplayer patched game", target.variants.single().contents.single().name)
        verify(exactly = 1) { gameRepository.delete(source) }
    }

    private fun createLibrary(): Library {
        return Library(
            id = 1L,
            name = "Games"
        )
    }

    private fun createGame(
        id: Long,
        library: Library,
        path: String,
        plugin: PluginManagementEntry,
        originalId: String
    ): Game {
        return Game(
            id = id,
            createdAt = Instant.parse("2026-01-0$id" + "T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-0$id" + "T00:00:00Z"),
            library = library,
            title = "Craftopia",
            platforms = mutableListOf(Platform.PC_MICROSOFT_WINDOWS),
            metadata = GameMetadata(
                path = path,
                originalIds = mapOf(plugin to originalId)
            )
        )
    }
}
