package org.gameyfin.app.games.dto

import org.gameyfin.app.games.entities.VariantTimestampKind
import java.time.Instant

data class RecordVariantTimestampEvidenceRequestDto(
    val kind: VariantTimestampKind,
    val observedAt: Instant,
    val provenance: String
)
