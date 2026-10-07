package org.gameyfin.app.games.dto

data class MarkVariantSupersededRequestDto(val replacementVariantId: Long, val reason: String? = null)
