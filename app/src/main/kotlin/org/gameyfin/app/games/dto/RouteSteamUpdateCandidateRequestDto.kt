package org.gameyfin.app.games.dto

/** An explicit administrator request to add an observed marker to the existing review queue. */
data class RouteSteamUpdateCandidateRequestDto(val requestId: Long, val marker: String)
