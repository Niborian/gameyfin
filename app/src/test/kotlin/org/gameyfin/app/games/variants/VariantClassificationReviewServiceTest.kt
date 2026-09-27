package org.gameyfin.app.games.variants

import io.mockk.*
import org.gameyfin.app.games.dto.CreateVariantClassificationReviewRequestDto
import org.gameyfin.app.games.dto.UpdateVariantClassificationReviewStateRequestDto
import org.gameyfin.app.games.entities.*
import org.gameyfin.app.games.repositories.*
import org.gameyfin.app.libraries.entities.Library
import org.gameyfin.pluginapi.gamemetadata.Platform
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.repository.findByIdOrNull
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VariantClassificationReviewServiceTest {
    private lateinit var games: GameRepository
    private lateinit var reviews: VariantClassificationReviewRepository
    private lateinit var decisions: VariantClassificationReviewDecisionRepository
    private lateinit var service: VariantClassificationReviewService

    @BeforeEach fun setup() {
        games = mockk(); reviews = mockk(); decisions = mockk()
        service = VariantClassificationReviewService(games, reviews, decisions)
        mockkStatic("org.gameyfin.app.core.security.SecurityUtilsKt")
        every { org.gameyfin.app.core.security.getCurrentAuth() } returns null
    }
    @AfterEach fun cleanup() = unmockkAll()

    @Test fun `create records review intent without changing variant path or default`() {
        val (source, target, variant) = games()
        every { games.findByIdOrNull(1L) } returns source
        every { games.findByIdOrNull(2L) } returns target
        every { reviews.save(any()) } answers { firstArg<VariantClassificationReview>().also { it.id = 9L } }
        val originalPath = variant.path
        val result = service.create(CreateVariantClassificationReviewRequestDto(1, 10, 2, 72, "same title, version differs", "release notes"))
        assertEquals(ClassificationReviewState.OPEN, result.state)
        assertEquals(originalPath, variant.path)
        verify(exactly = 1) { reviews.save(any()) }
    }

    @Test fun `create rejects foreign library target and invalid confidence`() {
        val (source, target, _) = games(differentLibrary = true)
        every { games.findByIdOrNull(1L) } returns source
        every { games.findByIdOrNull(2L) } returns target
        assertFailsWith<IllegalArgumentException> { service.create(CreateVariantClassificationReviewRequestDto(1, 10, 2, 101, "e", "x")) }
        verify(exactly = 0) { reviews.save(any()) }
    }

    private fun games(differentLibrary: Boolean = false): Triple<Game, Game, GameVariant> {
        val library = Library(id = 1L, name = "Games")
        val source = Game(id = 1L, library = library, platforms = mutableListOf(Platform.PC_MICROSOFT_WINDOWS), metadata = GameMetadata(path = "C:/torrent/A"))
        val variant = GameVariant(id = 10L, game = source, path = "C:/torrent/A/1.0"); source.variants.add(variant)
        val target = Game(id = 2L, library = if (differentLibrary) Library(id = 2L, name = "Other") else library, platforms = mutableListOf(Platform.PC_MICROSOFT_WINDOWS), metadata = GameMetadata(path = "C:/torrent/B"))
        return Triple(source, target, variant)
    }
}
