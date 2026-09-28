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
import java.time.temporal.ChronoUnit
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SteamUpdateCandidateServiceTest {
    private val repository = mockk<GameRepository>()
    private val newsClient = mockk<SteamNewsClient>()
    private val service = SteamUpdateCandidateService(repository, newsClient, SteamNewsContentUpdateClassifier())

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

    @Test
    fun `returns only update or review events from Steam news with explainable evidence`() {
        val variant = variant().apply {
            steamAppId = "1307550"
            steamAppIdVerifiedAt = Instant.parse("2026-09-20T00:00:00Z")
        }
        every { repository.findById(1L) } returns Optional.of(game(variant))
        every { newsClient.latest("1307550") } returns listOf(
            SteamNewsEvent("patch-1", "Patch 1.4.3", "https://example.test/patch", Instant.parse("2026-09-25T12:00:00Z"), setOf("patchnotes"), "Fixes"),
            SteamNewsEvent("live-1", "Developer livestream", "https://example.test/live", Instant.parse("2026-09-25T13:00:00Z"), emptySet(), "Update discussion"),
            SteamNewsEvent("misc-1", "Community round-up", "https://example.test/community", Instant.parse("2026-09-25T14:00:00Z"), emptySet(), "Highlights")
        )

        val events = service.newsEvents(1L)

        assertEquals(listOf("patch-1", "misc-1"), events.map { it.eventId })
        assertEquals(SteamNewsClassification.CONTENT_UPDATE, events.first().classification)
        assertEquals(SteamNewsClassification.REVIEW_NEEDED, events.last().classification)
    }

    @Test
    fun `ignoring an exact marker only changes review state`() {
        val variant = reviewableVariant()
        val game = game(variant)
        every { repository.findById(1L) } returns Optional.of(game)
        every { repository.save(game) } returns game

        val candidate = service.review(1L, 10L, ReviewSteamUpdateCandidateRequestDto("build-2026-09-25"), ignore = true)

        assertTrue(candidate.ignored)
        assertEquals("build-2026-09-25", variant.steamUpdateIgnoredMarker)
        assertEquals(null, variant.steamUpdateSnoozedUntil)
    }

    @Test
    fun `snoozing requires a future expiry and keeps the marker reviewable`() {
        val variant = reviewableVariant()
        val game = game(variant)
        every { repository.findById(1L) } returns Optional.of(game)
        every { repository.save(game) } returns game
        val until = Instant.now().plus(1, ChronoUnit.HOURS)

        val candidate = service.review(1L, 10L, ReviewSteamUpdateCandidateRequestDto("build-2026-09-25", until), ignore = false)

        assertFalse(candidate.ignored)
        assertEquals(until, candidate.snoozedUntil)
    }

    private fun reviewableVariant() = variant().apply {
        steamAppId = "1307550"
        steamAppIdVerifiedAt = Instant.parse("2026-09-20T00:00:00Z")
        localBuildVersion = "1.4.2"
        steamUpdateMarker = "build-2026-09-25"
        steamMetadataObservedAt = Instant.parse("2026-09-25T12:00:00Z")
        steamMetadataSource = "Steam public app metadata"
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
