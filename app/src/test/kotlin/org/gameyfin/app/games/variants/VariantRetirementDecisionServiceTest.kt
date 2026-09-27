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
        service = VariantRetirementDecisionService(gameRepository, decisionRepository, HardlinkMirrorService(tempDir.toString()))
        mockkStatic("org.gameyfin.app.core.security.SecurityUtilsKt")
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `archive records metadata decision without filesystem service`() {
        val game = gameWithVariant(mirrorRoot.resolve("library-hardlinks/library-1/Test/1.0"))
        val variant = game.variants.single()
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
        val variant = game.variants.single().also { it.retirementState = VariantRetirementState.ARCHIVED }
        every { gameRepository.findByIdOrNull(1L) } returns game
        every { gameRepository.save(game) } returns game
        every { decisionRepository.save(any()) } answers { firstArg<VariantRetirementDecision>().also { it.id = 8L } }
        every { org.gameyfin.app.core.security.getCurrentAuth() } returns null

        service.setState(1L, 10L, SetVariantRetirementStateRequestDto(VariantRetirementState.ACTIVE))

        assertEquals(VariantRetirementState.ACTIVE, variant.retirementState)
        verify(exactly = 1) { decisionRepository.save(any()) }
    }

    @Test
    fun `archive rejects default direct or selected-content variants`() {
        val game = gameWithVariant(Path.of("C:/torrent-source/Test/1.0"))
        val variant = game.variants.single()
        every { gameRepository.findByIdOrNull(1L) } returns game

        assertFailsWith<IllegalArgumentException> {
            service.setState(1L, 10L, SetVariantRetirementStateRequestDto(VariantRetirementState.ARCHIVED))
        }
        verify(exactly = 0) { gameRepository.save(any()) }
        verify(exactly = 0) { decisionRepository.save(any()) }
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
        return game
    }
}
