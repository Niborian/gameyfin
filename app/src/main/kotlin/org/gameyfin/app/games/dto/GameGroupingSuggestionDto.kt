package org.gameyfin.app.games.dto

data class GameGroupingSuggestionDto(
    val targetGameId: Long,
    val targetTitle: String?,
    val targetPath: String,
    val sourceGameId: Long,
    val sourceTitle: String?,
    val sourcePath: String,
    val confidence: Int,
    val autoGroup: Boolean,
    val reason: String,
    val suggestedVariantName: String,
    val suggestedVariantVersion: String
)
