package org.gameyfin.app.games.dto

import java.time.Instant

/** Applies only to the exact, already-observed marker. It cannot start an acquisition. */
data class ReviewSteamUpdateCandidateRequestDto(
    val marker: String,
    val snoozedUntil: Instant? = null
)
