package org.gameyfin.app.games.variants

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import org.gameyfin.app.core.filesystem.FilesystemService
import org.gameyfin.app.core.plugins.management.PluginManagementEntry
import org.gameyfin.app.games.dto.AttachVariantContentEntryDto
import org.gameyfin.app.games.dto.AttachVariantContentRequestDto
import org.gameyfin.app.games.dto.GroupGameAsVariantRequestDto
import org.gameyfin.app.games.dto.UpdateVariantContentRequestDto
import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.games.entities.GameMetadata
import org.gameyfin.app.games.entities.GameVariant
import org.gameyfin.app.games.entities.VariantContent
import org.gameyfin.app.games.entities.VariantContentType
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.libraries.IgnoredPathRepository
import org.gameyfin.app.libraries.entities.IgnoredPath
import org.gameyfin.app.libraries.entities.Library
import org.gameyfin.pluginapi.gamemetadata.Platform
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.Optional
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
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
    fun `getGroupingSuggestions should mark same path type shared provider ids as auto group`(@TempDir tempDir: Path) {
        val library = createLibrary()
        val plugin = PluginManagementEntry("igdb")
        val targetPath = tempDir.resolve("Craftopia.v2020.01.01.rar").createFile()
        val sourcePath = tempDir.resolve("Craftopia.v2025.07.25.rar").createFile()
        val target = createGame(1L, library, targetPath.toString(), plugin, "123")
        val source = createGame(2L, library, sourcePath.toString(), plugin, "123")
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
    fun `getGroupingSuggestions should require review for mixed file and folder matches`(@TempDir tempDir: Path) {
        val library = createLibrary()
        val plugin = PluginManagementEntry("igdb")
        val targetPath = tempDir.resolve("Craftopia").createDirectory()
        val sourcePath = tempDir.resolve("Craftopia.v2025.07.25.rar").createFile()
        val target = createGame(1L, library, targetPath.toString(), plugin, "123")
        val source = createGame(2L, library, sourcePath.toString(), plugin, "123")
        library.games.addAll(listOf(target, source))

        every { gameRepository.findAllByLibraryId(1L) } returns listOf(target, source)
        every { filesystemService.calculateFileSize(any()) } returns 2048L

        val suggestions = service.getGroupingSuggestions(1L)

        assertEquals(1, suggestions.size)
        assertEquals(95, suggestions.single().confidence)
        assertFalse(suggestions.single().autoGroup)
        assertTrue(suggestions.single().reason.contains("direction needs review"))
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

    @Test
    fun `groupGameAsVariant should add non base source as content on target variant`() {
        val library = createLibrary()
        val plugin = PluginManagementEntry("igdb")
        val targetPath = "/mnt/Games/Craftopia.v2025.07.25.rar"
        val sourcePath = "/mnt/Games/Craftopia"
        val target = createGame(1L, library, targetPath, plugin, "123")
        val source = createGame(2L, library, sourcePath, plugin, "123")
        library.games.addAll(listOf(target, source))

        every { gameRepository.findById(1L) } returns Optional.of(target)
        every { gameRepository.findById(2L) } returns Optional.of(source)
        every { filesystemService.calculateFileSize(sourcePath) } returns 4096L
        every { filesystemService.calculateFileSize(targetPath) } returns 8192L
        every { ignoredPathRepository.findByPath(sourcePath) } returns null
        every { ignoredPathRepository.save(any()) } answers { firstArg<IgnoredPath>() }
        every { gameRepository.delete(source) } just Runs
        every { gameRepository.flush() } just Runs
        every { gameRepository.save(target) } returns target

        val result = service.groupGameAsVariant(
            targetGameId = 1L,
            request = GroupGameAsVariantRequestDto(
                sourceGameId = 2L,
                variantName = "Normal",
                version = "2025.07.25",
                contentName = "Patch",
                contentType = VariantContentType.PATCH,
                required = false,
                defaultSelected = false,
                tags = setOf("patch"),
                steamAppId = null,
                launchArgs = null,
                patchInfo = null
            )
        )

        assertEquals(target, result)
        assertFalse(library.games.any { it.id == source.id })
        assertEquals(sourcePath, library.ignoredPaths.single().path)
        assertEquals(1, target.variants.size)
        assertEquals(targetPath, target.variants.single().path)
        assertEquals("Normal", target.variants.single().name)
        assertEquals("2025.07.25", target.variants.single().version)
        assertFalse(target.variants.single().scanManaged)
        assertEquals(2, target.variants.single().contents.size)
        assertTrue(target.variants.single().contents.any { it.type == VariantContentType.BASE && it.path == targetPath })
        val patchContent = target.variants.single().contents.single { it.type == VariantContentType.PATCH }
        assertEquals(sourcePath, patchContent.path)
        assertEquals("Patch", patchContent.name)
        assertFalse(patchContent.required)
        assertFalse(patchContent.defaultSelected)
    }

    @Test
    fun `attachVariantContent should add one optional dlc folder without moving files`() {
        val library = createLibrary()
        val plugin = PluginManagementEntry("igdb")
        val targetPath = "/mnt/Games/Craftopia.v2025.07.25.rar"
        val dlcPath = "/mnt/Games/CraftopiaDLC"
        val target = createGame(1L, library, targetPath, plugin, "123")
        val variant = createBaseVariant(target, 10L, targetPath)
        target.variants.add(variant)
        library.games.add(target)

        every { gameRepository.findById(1L) } returns Optional.of(target)
        every { gameRepository.findAllByLibraryId(1L) } returns listOf(target)
        every { ignoredPathRepository.findByPath(dlcPath) } returns null
        every { ignoredPathRepository.save(any()) } answers { firstArg<IgnoredPath>() }
        every { filesystemService.calculateFileSize(dlcPath) } returns 2048L
        every { gameRepository.save(target) } returns target

        val result = service.attachVariantContent(
            targetGameId = 1L,
            request = AttachVariantContentRequestDto(
                sourceGameId = null,
                sourceRootPath = dlcPath,
                targetVariantId = 10L,
                variantName = null,
                version = null,
                entries = listOf(
                    AttachVariantContentEntryDto(
                        path = dlcPath,
                        contentName = "DLC pack",
                        contentType = VariantContentType.DLC,
                        required = false,
                        defaultSelected = false,
                        tags = setOf("dlc")
                    )
                )
            )
        )

        assertEquals(target, result)
        assertEquals(dlcPath, library.ignoredPaths.single().path)
        assertEquals(2, variant.contents.size)
        val dlc = variant.contents.single { it.type == VariantContentType.DLC }
        assertEquals("DLC pack", dlc.name)
        assertEquals(dlcPath, dlc.path)
        assertEquals(2048L, dlc.fileSize)
        assertFalse(dlc.required)
        assertFalse(dlc.defaultSelected)
        verify(exactly = 0) { gameRepository.delete(any()) }
    }

    @Test
    fun `attachVariantContent should split direct children into separate entries`() {
        val library = createLibrary()
        val plugin = PluginManagementEntry("igdb")
        val targetPath = "/mnt/Games/Craftopia.v2025.07.25.rar"
        val firstDlc = "/mnt/Games/CraftopiaDLC/DLC1"
        val secondDlc = "/mnt/Games/CraftopiaDLC/DLC2"
        val target = createGame(1L, library, targetPath, plugin, "123")
        val variant = createBaseVariant(target, 10L, targetPath)
        target.variants.add(variant)
        library.games.add(target)

        every { gameRepository.findById(1L) } returns Optional.of(target)
        every { gameRepository.findAllByLibraryId(1L) } returns listOf(target)
        every { ignoredPathRepository.findByPath("/mnt/Games/CraftopiaDLC") } returns null
        every { ignoredPathRepository.save(any()) } answers { firstArg<IgnoredPath>() }
        every { filesystemService.calculateFileSize(firstDlc) } returns 100L
        every { filesystemService.calculateFileSize(secondDlc) } returns 200L
        every { gameRepository.save(target) } returns target

        service.attachVariantContent(
            targetGameId = 1L,
            request = AttachVariantContentRequestDto(
                sourceGameId = null,
                sourceRootPath = "/mnt/Games/CraftopiaDLC",
                targetVariantId = 10L,
                variantName = null,
                version = null,
                entries = listOf(
                    AttachVariantContentEntryDto(firstDlc, "DLC 1", VariantContentType.DLC, false, true, emptySet()),
                    AttachVariantContentEntryDto(secondDlc, "DLC 2", VariantContentType.DLC, false, false, emptySet())
                )
            )
        )

        assertEquals(3, variant.contents.size)
        assertEquals(100L, variant.contents.single { it.name == "DLC 1" }.fileSize)
        assertTrue(variant.contents.single { it.name == "DLC 1" }.defaultSelected)
        assertEquals(200L, variant.contents.single { it.name == "DLC 2" }.fileSize)
        assertFalse(variant.contents.single { it.name == "DLC 2" }.defaultSelected)
    }

    @Test
    fun `attachVariantContent should delete duplicate source game and ignore its path`() {
        val library = createLibrary()
        val plugin = PluginManagementEntry("igdb")
        val targetPath = "/mnt/Games/Craftopia.v2025.07.25.rar"
        val patchPath = "/mnt/Games/Craftopia"
        val target = createGame(1L, library, targetPath, plugin, "123")
        val source = createGame(2L, library, patchPath, plugin, "123")
        val variant = createBaseVariant(target, 10L, targetPath)
        target.variants.add(variant)
        library.games.addAll(listOf(target, source))

        every { gameRepository.findById(1L) } returns Optional.of(target)
        every { gameRepository.findById(2L) } returns Optional.of(source)
        every { ignoredPathRepository.findByPath(patchPath) } returns null
        every { ignoredPathRepository.save(any()) } answers { firstArg<IgnoredPath>() }
        every { gameRepository.delete(source) } just Runs
        every { gameRepository.flush() } just Runs
        every { filesystemService.calculateFileSize(patchPath) } returns 4096L
        every { gameRepository.save(target) } returns target

        service.attachVariantContent(
            targetGameId = 1L,
            request = AttachVariantContentRequestDto(
                sourceGameId = 2L,
                sourceRootPath = patchPath,
                targetVariantId = 10L,
                variantName = null,
                version = null,
                entries = listOf(
                    AttachVariantContentEntryDto(
                        path = patchPath,
                        contentName = "Patch",
                        contentType = VariantContentType.PATCH,
                        required = false,
                        defaultSelected = false,
                        tags = setOf("patch")
                    )
                )
            )
        )

        assertFalse(library.games.any { it.id == source.id })
        assertEquals(patchPath, library.ignoredPaths.single().path)
        assertEquals(2, variant.contents.size)
        assertEquals(patchPath, variant.contents.single { it.type == VariantContentType.PATCH }.path)
        verify(exactly = 1) { gameRepository.delete(source) }
    }

    @Test
    fun `attachVariantContent should delete duplicate source when selected content is under source path`() {
        val library = createLibrary()
        val plugin = PluginManagementEntry("igdb")
        val targetPath = "/mnt/Games/Factorio.v2.0.60.rar"
        val sourcePath = "/mnt/Games/Factorio"
        val patchPath = "/mnt/Games/Factorio/Onlinefix/Factorio Fix Repair Steam Generic.rar"
        val target = createGame(1L, library, targetPath, plugin, "10052")
        val source = createGame(2L, library, sourcePath, plugin, "10052")
        val variant = createBaseVariant(target, 10L, targetPath)
        target.variants.add(variant)
        library.games.addAll(listOf(target, source))

        every { gameRepository.findById(1L) } returns Optional.of(target)
        every { gameRepository.findAllByLibraryId(1L) } returns listOf(target, source)
        every { ignoredPathRepository.findByPath(sourcePath) } returns null
        every { ignoredPathRepository.save(any()) } answers { firstArg<IgnoredPath>() }
        every { gameRepository.delete(source) } just Runs
        every { gameRepository.flush() } just Runs
        every { filesystemService.calculateFileSize(patchPath) } returns 22L
        every { gameRepository.save(target) } returns target

        service.attachVariantContent(
            targetGameId = 1L,
            request = AttachVariantContentRequestDto(
                sourceGameId = null,
                sourceRootPath = patchPath,
                targetVariantId = 10L,
                variantName = null,
                version = null,
                entries = listOf(
                    AttachVariantContentEntryDto(patchPath, "Multiplayer patch", VariantContentType.PATCH, false, true, emptySet())
                )
            )
        )

        assertFalse(library.games.any { it.id == source.id })
        assertEquals(sourcePath, library.ignoredPaths.single().path)
        assertEquals(patchPath, variant.contents.single { it.type == VariantContentType.PATCH }.path)
        verify(exactly = 1) { gameRepository.delete(source) }
    }

    @Test
    fun `updateVariantContent should rename content and set variant primary path`() {
        val library = createLibrary()
        val plugin = PluginManagementEntry("igdb")
        val oldBasePath = "/mnt/Games/Farming.Simulator.25.rar"
        val newBasePath = "/mnt/Games/FarmingSimulator25Parts/part01.rar"
        val secondBasePath = "/mnt/Games/FarmingSimulator25Parts/part02.rar"
        val target = createGame(1L, library, oldBasePath, plugin, "123")
        val variant = createBaseVariant(target, 10L, oldBasePath)
        target.variants.add(variant)
        library.games.add(target)

        every { gameRepository.findById(1L) } returns Optional.of(target)
        every { filesystemService.calculateFileSize(newBasePath) } returns 512L
        every { filesystemService.calculateFileSize(secondBasePath) } returns 256L
        every { gameRepository.save(target) } returns target

        service.updateVariantContent(
            targetGameId = 1L,
            variantId = 10L,
            contentId = 110L,
            request = UpdateVariantContentRequestDto(
                path = newBasePath,
                paths = listOf(newBasePath, secondBasePath),
                contentName = "Base archive part 1",
                contentType = VariantContentType.BASE,
                required = true,
                defaultSelected = true,
                tags = setOf("base"),
                setAsVariantPath = true
            )
        )

        assertEquals(newBasePath, variant.path)
        assertEquals(768L, variant.fileSize)
        val content = variant.contents.single()
        assertEquals("Base archive part 1", content.name)
        assertEquals(newBasePath, content.path)
        assertEquals(listOf(newBasePath, secondBasePath), content.paths)
        assertEquals(768L, content.fileSize)
        assertTrue(content.required)
        assertTrue(content.defaultSelected)
    }

    @Test
    fun `deleteVariantContent should remove content and keep another base item`() {
        val library = createLibrary()
        val plugin = PluginManagementEntry("igdb")
        val firstBasePath = "/mnt/Games/FarmingSimulator25/part01.rar"
        val secondBasePath = "/mnt/Games/FarmingSimulator25/part02.rar"
        val target = createGame(1L, library, firstBasePath, plugin, "123")
        val variant = createBaseVariant(target, 10L, firstBasePath)
        variant.contents.add(
            VariantContent(
                id = 111L,
                variant = variant,
                type = VariantContentType.BASE,
                name = "Part 2",
                path = secondBasePath,
                fileSize = 200L,
                required = true,
                defaultSelected = true
            )
        )
        target.variants.add(variant)
        library.games.add(target)

        every { gameRepository.findById(1L) } returns Optional.of(target)
        every { filesystemService.calculateFileSize(secondBasePath) } returns 200L
        every { gameRepository.save(target) } returns target

        service.deleteVariantContent(1L, 10L, 110L)

        assertEquals(1, variant.contents.size)
        assertEquals(secondBasePath, variant.path)
        assertEquals(200L, variant.fileSize)
    }

    @Test
    fun `removeDuplicateVariantSource should delete source game and ignore source path`() {
        val library = createLibrary()
        val plugin = PluginManagementEntry("igdb")
        val target = createGame(1L, library, "/mnt/Games/Factorio.v2.0.60.rar", plugin, "10052")
        val source = createGame(2L, library, "/mnt/Games/Factorio", plugin, "10052")
        library.games.addAll(listOf(target, source))

        every { gameRepository.findById(1L) } returns Optional.of(target)
        every { gameRepository.findById(2L) } returns Optional.of(source)
        every { ignoredPathRepository.findByPath(source.metadata.path) } returns null
        every { ignoredPathRepository.save(any()) } answers { firstArg<IgnoredPath>() }
        every { gameRepository.delete(source) } just Runs
        every { gameRepository.flush() } just Runs
        every { gameRepository.save(target) } returns target

        service.removeDuplicateVariantSource(1L, 2L)

        assertFalse(library.games.any { it.id == source.id })
        assertEquals(source.metadata.path, library.ignoredPaths.single().path)
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

    private fun createBaseVariant(game: Game, id: Long, path: String): GameVariant {
        return GameVariant(
            id = id,
            game = game,
            name = "Normal",
            version = "2025.07.25",
            path = path,
            fileSize = 8192L,
            isDefault = true,
            isLatestForVariant = true,
            scanManaged = false
        ).also { variant ->
            variant.contents.add(
                VariantContent(
                    id = id + 100,
                    variant = variant,
                    type = VariantContentType.BASE,
                    name = "Base game",
                    path = path,
                    fileSize = 8192L,
                    required = true,
                    defaultSelected = true
                )
            )
        }
    }
}
