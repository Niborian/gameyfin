package org.gameyfin.app.games.dto

import java.time.Instant

/** Read-only comparison evidence. It never contacts Steam or a download client. */
data class SteamUpdateCandidateDto(
    val variantId: Long,
    val steamAppId: String,
    val localBuildVersion: String,
    val steamUpdateMarker: String,
    val observedAt: Instant,
    val source: String,
    val ignored: Boolean,
    val snoozedUntil: Instant?
)
