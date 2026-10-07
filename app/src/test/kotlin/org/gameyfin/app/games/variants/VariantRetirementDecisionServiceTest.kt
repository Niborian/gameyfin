package org.gameyfin.app.games.variants

import io.mockk.*
import org.gameyfin.app.games.dto.SetVariantRetirementStateRequestDto
import org.gameyfin.app.games.entities.*
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.games.repositories.VariantRetirementDecisionRepository
import org.gameyfin.app.libraries.entities.Library
import org.gameyfin.pluginapi.gamemetadata.Platform
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.data.repository.findByIdOrNull
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VariantRetirementDecisionServiceTest {
    private lateinit var gameRepository: GameRepository
    private lateinit var decisionRepository: VariantRetirementDecisionRepository
    private lateinit var service: VariantRetirementDecisionService
    private lateinit var mirrorRoot: Path

    @BeforeEach
    fun setup(@TempDir tempDir: Path) {
        gameRepository = mockk()
        decisionRepository = mockk()
        mirrorRoot = tempDir
        service = VariantRetirementDecisionService(gameRepository, decisionRepository)
        mockkStatic("org.gameyfin.app.core.security.SecurityUtilsKt")
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `archive records metadata decision without filesystem service`() {
        val game = gameWithVariant(mirrorRoot.resolve("library-hardlinks/library-1/Test/1.0"))
        val variant = game.variants.first()
        every { gameRepository.findByIdOrNull(1L) } returns game
        every { gameRepository.save(game) } returns game
        every { decisionRepository.save(any()) } answers { firstArg<VariantRetirementDecision>().also { it.id = 7L } }
        every { org.gameyfin.app.core.security.getCurrentAuth() } returns null

        val result = service.setState(1L, 10L, SetVariantRetirementStateRequestDto(VariantRetirementState.ARCHIVED, "newer mirror retained"))

        assertEquals(VariantRetirementState.ARCHIVED, variant.retirementState)
        assertEquals(VariantRetirementState.ACTIVE, result.previousState)
        assertEquals(VariantRetirementState.ARCHIVED, result.newState)
        assertEquals("system", result.actor)
        verify(exactly = 1) { gameRepository.save(game) }
        verify(exactly = 1) { decisionRepository.save(any()) }
    }

    @Test
    fun `restore is reversible and does not require mirror eligibility`() {
        val game = gameWithVariant(Path.of("C:/torrent-source/Test/1.0"))
        val variant = game.variants.first().also { it.retirementState = VariantRetirementState.ARCHIVED }
        every { gameRepository.findByIdOrNull(1L) } returns game
        every { gameRepository.save(game) } returns game
        every { decisionRepository.save(any()) } answers { firstArg<VariantRetirementDecision>().also { it.id = 8L } }
        every { org.gameyfin.app.core.security.getCurrentAuth() } returns null

        service.setState(1L, 10L, SetVariantRetirementStateRequestDto(VariantRetirementState.ACTIVE))

        assertEquals(VariantRetirementState.ACTIVE, variant.retirementState)
        verify(exactly = 1) { decisionRepository.save(any()) }
    }

    @Test
    fun `archive rejects selected default variants`() {
        val game = gameWithVariant(Path.of("C:/torrent-source/Test/1.0"))
        val variant = game.variants.first().also { it.isDefault = true }
        every { gameRepository.findByIdOrNull(1L) } returns game

        assertFailsWith<IllegalArgumentException> {
            service.setState(1L, 10L, SetVariantRetirementStateRequestDto(VariantRetirementState.ARCHIVED))
        }
        verify(exactly = 0) { gameRepository.save(any()) }
        verify(exactly = 0) { decisionRepository.save(any()) }
    }

    @Test
    fun `metadata archive with required content preserves source and dependent hardlink`(@TempDir tempDir: Path) {
        val source = tempDir.resolve("torrent-source.bin")
        java.nio.file.Files.writeString(source, "original source content")
        val dependent = tempDir.resolve("dependent.bin")
        java.nio.file.Files.createLink(dependent, source)
        val game = gameWithVariant(source)
        val variant = game.variants.first().also { it.linkStatus = VariantLinkStatus.DIRECT }
        variant.contents.add(VariantContent(id = 100L, variant = variant, name = "Base", path = source.toString(), required = true, defaultSelected = true))
        every { gameRepository.findByIdOrNull(1L) } returns game
        every { gameRepository.save(game) } returns game
        every { decisionRepository.save(any()) } answers { firstArg<VariantRetirementDecision>().also { it.id = 9L } }
        every { org.gameyfin.app.core.security.getCurrentAuth() } returns null

        service.setState(1L, 10L, SetVariantRetirementStateRequestDto(VariantRetirementState.ARCHIVED))
        assertEquals("original source content", java.nio.file.Files.readString(source))
        assertEquals(true, java.nio.file.Files.isSameFile(source, dependent))
        assertEquals(1, variant.contents.size)

        service.setState(1L, 10L, SetVariantRetirementStateRequestDto(VariantRetirementState.ACTIVE))
        assertEquals("original source content", java.nio.file.Files.readString(dependent))
        assertEquals(VariantRetirementState.ACTIVE, variant.retirementState)
        verify(exactly = 2) { decisionRepository.save(any()) }
    }

    @Test
    fun `supersession requires a newer active sibling and preserves first observation`() {
        val game = gameWithVariant(mirrorRoot.resolve("old"))
        val old = game.variants.first()
        game.variants.removeIf { it.id != old.id }
        val newer = GameVariant(id = 11L, game = game, name = old.name, version = "1.10", path = mirrorRoot.resolve("new").toString())
        game.variants.add(newer)
        every { gameRepository.findByIdOrNull(1L) } returns game
        every { gameRepository.save(game) } returns game
        every { decisionRepository.save(any()) } answers { firstArg<VariantRetirementDecision>().also { it.id = 10L } }
        every { org.gameyfin.app.core.security.getCurrentAuth() } returns null
        val request = org.gameyfin.app.games.dto.MarkVariantSupersededRequestDto(11L)
        val decision = service.markSuperseded(1L, 10L, request)
        val firstObservation = old.supersededAt
        service.markSuperseded(1L, 10L, request)
        assertEquals(firstObservation, old.supersededAt)
        assertEquals(firstObservation, decision.supersededAt)
        assertEquals(11L, decision.supersededByVariantId)
        assertEquals(VariantRetirementState.ACTIVE, old.retirementState)
        newer.retirementState = VariantRetirementState.ARCHIVED
        assertFailsWith<IllegalArgumentException> { service.markSuperseded(1L, 10L, request) }
        newer.retirementState = VariantRetirementState.ACTIVE
        newer.name = "VR"
        assertFailsWith<IllegalArgumentException> { service.markSuperseded(1L, 10L, request) }
        newer.name = old.name
        newer.version = old.version
        assertFailsWith<IllegalArgumentException> { service.markSuperseded(1L, 10L, request) }
        verify(exactly = 2) { decisionRepository.save(any()) }
    }

    @Test
    fun `archive fails when replacement disappeared despite stale latest flag`() {
        val game = gameWithVariant(mirrorRoot.resolve("old"))
        game.variants.removeIf { it.id == 20L }
        every { gameRepository.findByIdOrNull(1L) } returns game
        assertFailsWith<IllegalArgumentException> {
            service.setState(1L, 10L, SetVariantRetirementStateRequestDto(VariantRetirementState.ARCHIVED))
        }
        assertEquals(VariantRetirementState.ACTIVE, game.variants.single().retirementState)
        verify(exactly = 0) { gameRepository.save(any()) }
        verify(exactly = 0) { decisionRepository.save(any()) }
    }

    @Test
    fun `metadata activation requires physical quarantine restoration first`() {
        val game = gameWithVariant(mirrorRoot.resolve("old"))
        game.variants.first().also { it.retirementState = VariantRetirementState.ARCHIVED; it.quarantinePath = "quarantine/payload" }
        every { gameRepository.findByIdOrNull(1L) } returns game
        assertFailsWith<IllegalArgumentException> { service.setState(1L, 10L, SetVariantRetirementStateRequestDto(VariantRetirementState.ACTIVE)) }
        verify(exactly = 0) { gameRepository.save(any()) }
    }

    private fun gameWithVariant(path: Path): Game {
        val game = Game(
            id = 1L,
            library = Library(id = 1L, name = "Games"),
            platforms = mutableListOf(Platform.PC_MICROSOFT_WINDOWS),
            metadata = GameMetadata(path = "C:/torrent-source/Test")
        )
        game.variants.add(GameVariant(
            id = 10L,
            game = game,
            name = "Normal",
            version = "1.0",
            path = path.toString(),
            linkStatus = VariantLinkStatus.HARDLINKED
        ))
        game.variants.add(GameVariant(id = 20L, game = game, name = "Normal", version = "2.0",
            path = path.resolveSibling("newer").toString(), isDefault = true, isLatestForVariant = true))
        return game
    }
}
