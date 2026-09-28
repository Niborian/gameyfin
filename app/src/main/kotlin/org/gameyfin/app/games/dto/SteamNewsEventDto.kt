package org.gameyfin.app.games.dto

import org.gameyfin.app.games.variants.SteamNewsClassification
import java.time.Instant

/** Evidence returned by Steam's official public news API; it never represents a download action. */
data class SteamNewsEventDto(
    val variantId: Long,
    val eventId: String,
    val title: String,
    val url: String,
    val publishedAt: Instant,
    val tags: Set<String>,
    val classification: SteamNewsClassification,
    val classificationReason: String
)
