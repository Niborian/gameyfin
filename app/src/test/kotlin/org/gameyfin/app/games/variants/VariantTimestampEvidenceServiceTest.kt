package org.gameyfin.app.games.variants

import io.mockk.*
import org.gameyfin.app.games.dto.RecordVariantTimestampEvidenceRequestDto
import org.gameyfin.app.games.entities.*
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.games.repositories.VariantTimestampEvidenceRepository
import org.gameyfin.app.libraries.entities.Library
import org.gameyfin.pluginapi.gamemetadata.Platform
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.repository.findByIdOrNull
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VariantTimestampEvidenceServiceTest {
    private lateinit var gameRepository: GameRepository
    private lateinit var evidenceRepository: VariantTimestampEvidenceRepository
    private lateinit var service: VariantTimestampEvidenceService

    @BeforeEach
    fun setup() {
        gameRepository = mockk()
        evidenceRepository = mockk()
        service = VariantTimestampEvidenceService(gameRepository, evidenceRepository)
        mockkStatic("org.gameyfin.app.core.security.SecurityUtilsKt")
        every { org.gameyfin.app.core.security.getCurrentAuth() } returns null
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `records each timestamp kind independently without changing variant selection or path`() {
        val variant = variant()
        val game = variant.game
        every { gameRepository.findByIdOrNull(1L) } returns game
        every { evidenceRepository.save(any()) } answers { firstArg<VariantTimestampEvidence>().also { it.id = 3L } }
        val originalPath = variant.path
        val originalDefault = variant.isDefault

        val release = service.record(1L, 10L, request(VariantTimestampKind.RELEASE_METADATA, "release notes"))
        val imported = service.record(1L, 10L, request(VariantTimestampKind.QBITTORRENT_COMPLETED, "qBittorrent history"))

        assertEquals(VariantTimestampKind.RELEASE_METADATA, release.kind)
        assertEquals(VariantTimestampKind.QBITTORRENT_COMPLETED, imported.kind)
        assertEquals(originalPath, variant.path)
        assertEquals(originalDefault, variant.isDefault)
        verify(exactly = 2) { evidenceRepository.save(any()) }
    }

    @Test
    fun `rejects future dates and blank provenance`() {
        val variant = variant()
        every { gameRepository.findByIdOrNull(1L) } returns variant.game

        assertFailsWith<IllegalArgumentException> {
            service.record(1L, 10L, RecordVariantTimestampEvidenceRequestDto(VariantTimestampKind.TORRENT_ADDED, Instant.now().plusSeconds(60), "client"))
        }
        assertFailsWith<IllegalArgumentException> {
            service.record(1L, 10L, RecordVariantTimestampEvidenceRequestDto(VariantTimestampKind.FILESYSTEM_OBSERVED, Instant.now(), " "))
        }
        verify(exactly = 0) { evidenceRepository.save(any()) }
    }

    private fun request(kind: VariantTimestampKind, provenance: String) =
        RecordVariantTimestampEvidenceRequestDto(kind, Instant.parse("2026-09-26T12:00:00Z"), provenance)

    private fun variant(): GameVariant {
        val game = Game(
            id = 1L,
            library = Library(id = 1L, name = "Games"),
            platforms = mutableListOf(Platform.PC_MICROSOFT_WINDOWS),
            metadata = GameMetadata(path = "C:/torrents/Test")
        )
        return GameVariant(id = 10L, game = game, path = "C:/torrents/Test/1.0", isDefault = true).also { game.variants.add(it) }
    }
}
