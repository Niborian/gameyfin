package org.gameyfin.app.games.dto

import java.time.Instant

/** Owner-verified metadata only; this request never triggers a Steam or download-client call. */
data class RecordVariantSteamMetadataRequestDto(
    val localBuildVersion: String,
    val steamUpdateMarker: String,
    val observedAt: Instant,
    val source: String
)
