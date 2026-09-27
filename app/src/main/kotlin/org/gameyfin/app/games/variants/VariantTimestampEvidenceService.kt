package org.gameyfin.app.games.variants

import org.gameyfin.app.core.security.getCurrentAuth
import org.gameyfin.app.games.dto.RecordVariantTimestampEvidenceRequestDto
import org.gameyfin.app.games.dto.VariantTimestampEvidenceDto
import org.gameyfin.app.games.entities.GameVariant
import org.gameyfin.app.games.entities.VariantTimestampEvidence
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.games.repositories.VariantTimestampEvidenceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Stores timestamp evidence only. It does not scan, rank, select, move, or modify any path. */
@Service
class VariantTimestampEvidenceService(
    private val gameRepository: GameRepository,
    private val evidenceRepository: VariantTimestampEvidenceRepository
) {
    @Transactional
    fun record(gameId: Long, variantId: Long, request: RecordVariantTimestampEvidenceRequestDto): VariantTimestampEvidenceDto {
        val variant = findVariant(gameId, variantId)
        require(!request.observedAt.isAfter(Instant.now())) { "Observed timestamp cannot be in the future" }
        val provenance = request.provenance.trim()
        require(provenance.isNotEmpty()) { "Timestamp provenance is required" }
        require(provenance.length <= 2048) { "Timestamp provenance must not exceed 2048 characters" }

        return evidenceRepository.save(
            VariantTimestampEvidence(
                variant = variant,
                kind = request.kind,
                observedAt = request.observedAt,
                provenance = provenance,
                actor = getCurrentAuth()?.name?.takeIf { it.isNotBlank() } ?: "system"
            )
        ).toDto()
    }

    @Transactional(readOnly = true)
    fun history(gameId: Long, variantId: Long): List<VariantTimestampEvidenceDto> {
        findVariant(gameId, variantId)
        return evidenceRepository.findAllByVariantIdOrderByKindAscRecordedAtAsc(variantId).map { it.toDto() }
    }

    private fun findVariant(gameId: Long, variantId: Long): GameVariant {
        val game = gameRepository.findByIdOrNull(gameId) ?: throw IllegalArgumentException("Game $gameId not found")
        return game.variants.firstOrNull { it.id == variantId }
            ?: throw IllegalArgumentException("Variant $variantId does not belong to game $gameId")
    }

    private fun VariantTimestampEvidence.toDto() = VariantTimestampEvidenceDto(
        id = requireNotNull(id), kind = kind, observedAt = observedAt, provenance = provenance,
        recordedAt = recordedAt, actor = actor
    )
}
