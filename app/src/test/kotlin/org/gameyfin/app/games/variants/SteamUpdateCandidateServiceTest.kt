package org.gameyfin.app.games.variants

import io.mockk.every
import io.mockk.mockk
import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.games.entities.GameMetadata
import org.gameyfin.app.games.entities.GameVariant
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.libraries.entities.Library
import org.gameyfin.pluginapi.gamemetadata.Platform
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SteamUpdateCandidateServiceTest {
    private val repository = mockk<GameRepository>()
    private val service = SteamUpdateCandidateService(repository)

    @Test
    fun `returns explainable marker difference for a verified Steam app`() {
        val variant = variant().apply {
            steamAppId = "1307550"
            steamAppIdVerifiedAt = Instant.parse("2026-09-20T00:00:00Z")
            localBuildVersion = "1.4.2"
            steamUpdateMarker = "build-2026-09-25"
            steamMetadataObservedAt = Instant.parse("2026-09-25T12:00:00Z")
            steamMetadataSource = "Steam public app metadata"
        }
        val game = game(variant)
        every { repository.findById(1L) } returns Optional.of(game)

        val candidate = service.candidates(1L).single()

        assertEquals("1.4.2", candidate.localBuildVersion)
        assertEquals("build-2026-09-25", candidate.steamUpdateMarker)
        assertEquals("Steam public app metadata", candidate.source)
        assertFalse(candidate.ignored)
    }

    @Test
    fun `does not create a candidate when public and local markers agree`() {
        val variant = variant().apply {
            steamAppId = "1307550"
            steamAppIdVerifiedAt = Instant.parse("2026-09-20T00:00:00Z")
            localBuildVersion = "1.4.2"
            steamUpdateMarker = "1.4.2"
            steamMetadataObservedAt = Instant.parse("2026-09-25T12:00:00Z")
            steamMetadataSource = "Steam public app metadata"
        }
        every { repository.findById(1L) } returns Optional.of(game(variant))

        assertTrue(service.candidates(1L).isEmpty())
    }

    private fun game(variant: GameVariant): Game = Game(
        id = 1L,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        library = Library(id = 1L, name = "Fixtures"),
        title = "Example",
        platforms = mutableListOf(Platform.PC_MICROSOFT_WINDOWS),
        metadata = GameMetadata(path = "/fixtures/example")
    ).also { game ->
        variant.game = game
        game.variants.add(variant)
    }

    private fun variant() = GameVariant(id = 10L, game = mockk(), path = "/fixtures/example")
}
