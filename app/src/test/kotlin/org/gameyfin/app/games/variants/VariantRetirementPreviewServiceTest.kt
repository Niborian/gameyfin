package org.gameyfin.app.games.variants

import io.mockk.every
import io.mockk.mockk
import org.gameyfin.app.games.dto.VariantRetirementDisposition
import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.games.entities.GameMetadata
import org.gameyfin.app.games.entities.GameVariant
import org.gameyfin.app.games.entities.VariantContent
import org.gameyfin.app.games.entities.VariantContentType
import org.gameyfin.app.games.entities.VariantLinkStatus
import org.gameyfin.app.games.entities.VariantRetirementDecision
import org.gameyfin.app.games.entities.VariantRetirementState
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.games.repositories.VariantRetirementDecisionRepository
import org.gameyfin.app.libraries.entities.Library
import org.gameyfin.pluginapi.gamemetadata.Platform
import org.junit.jupiter.api.Test
import org.springframework.data.repository.findByIdOrNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.time.Instant

class VariantRetirementPreviewServiceTest {
    private val gameRepository = mockk<GameRepository>()
    private val decisionRepository = mockk<VariantRetirementDecisionRepository>()
    private val service = VariantRetirementPreviewService(gameRepository, decisionRepository)

    @Test
    fun `preview retains direct sources and marks only superseded hardlink mirrors for review`() {
        val library = Library(id = 1L, name = "Games")
        val game = Game(
            id = 1L,
            library = library,
            platforms = mutableListOf(Platform.PC_MICROSOFT_WINDOWS),
            metadata = GameMetadata(path = "/torrents/Craftopia")
        )
        val direct = variant(game, 10L, "1.0", "/torrents/Craftopia/1.0", VariantLinkStatus.DIRECT)
        val mirror = variant(game, 11L, "1.1", "/data/library-hardlinks/library-1/Craftopia/1.1", VariantLinkStatus.HARDLINKED)
        val latest = variant(game, 12L, "1.2", "/data/library-hardlinks/library-1/Craftopia/1.2", VariantLinkStatus.HARDLINKED)
            .also { it.isLatestForVariant = true; it.isDefault = true }
        game.variants.addAll(listOf(direct, mirror, latest))

        every { gameRepository.findByIdOrNull(1L) } returns game
        every { decisionRepository.findAllByVariantIdOrderByDecidedAtAsc(any()) } returns emptyList()

        val previews = service.preview(1L).associateBy { it.variantId }

        assertEquals(VariantRetirementDisposition.RETAIN, previews.getValue(10L).disposition)
        assertTrue(previews.getValue(10L).reason.contains("not proven", ignoreCase = true))
        assertEquals(VariantRetirementDisposition.REVIEW_MANAGED_MIRROR, previews.getValue(11L).disposition)
        assertEquals(VariantRetirementDisposition.RETAIN, previews.getValue(12L).disposition)
        assertEquals(listOf("Base game"), previews.getValue(11L).selectedContentNames)
        assertTrue(previews.getValue(11L).effectivePaths.all { it.startsWith("/data/library-hardlinks") })
        assertEquals(org.gameyfin.app.games.entities.VariantRetirementState.ACTIVE, previews.getValue(11L).retirementState)
    }

    @Test
    fun `preview exposes metadata-only archived state and latest decision without audit reasons`() {
        val library = Library(id = 1L, name = "Games")
        val game = Game(id = 1L, library = library, platforms = mutableListOf(Platform.PC_MICROSOFT_WINDOWS), metadata = GameMetadata(path = "/torrents/Test"))
        val variant = variant(game, 10L, "1.0", "/data/library-hardlinks/library-1/Test/1.0", VariantLinkStatus.HARDLINKED).also {
            it.retirementState = VariantRetirementState.ARCHIVED
            it.retirementReviewAt = Instant.parse("2026-10-01T00:00:00Z")
        }
        game.variants.add(variant)
        every { gameRepository.findByIdOrNull(1L) } returns game
        every { decisionRepository.findAllByVariantIdOrderByDecidedAtAsc(10L) } returns listOf(
            VariantRetirementDecision(
                id = 77L,
                variant = variant,
                previousState = VariantRetirementState.ACTIVE,
                newState = VariantRetirementState.ARCHIVED,
                decidedAt = Instant.parse("2026-09-26T00:00:00Z"),
                actor = "admin",
                reason = "not exposed by preview"
            )
        )

        val preview = service.preview(1L).single()

        assertEquals(VariantRetirementState.ARCHIVED, preview.retirementState)
        assertEquals(VariantRetirementState.ARCHIVED, preview.latestDecisionState)
        assertEquals(Instant.parse("2026-09-26T00:00:00Z"), preview.latestDecisionAt)
    }

    private fun variant(game: Game, id: Long, version: String, path: String, linkStatus: VariantLinkStatus): GameVariant {
        return GameVariant(
            id = id,
            game = game,
            name = "Normal",
            version = version,
            path = path,
            fileSize = 1024L,
            linkStatus = linkStatus
        ).also { variant ->
            variant.contents.add(
                VariantContent(
                    id = id + 100,
                    variant = variant,
                    type = VariantContentType.BASE,
                    name = "Base game",
                    path = path,
                    fileSize = 1024L,
                    required = true,
                    defaultSelected = true
                )
            )
        }
    }
}
