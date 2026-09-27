package org.gameyfin.app.games.dto

import org.gameyfin.app.games.entities.VariantTimestampKind
import java.time.Instant

data class VariantTimestampEvidenceDto(
    val id: Long,
    val kind: VariantTimestampKind,
    val observedAt: Instant,
    val provenance: String,
    val recordedAt: Instant,
    val actor: String
)
