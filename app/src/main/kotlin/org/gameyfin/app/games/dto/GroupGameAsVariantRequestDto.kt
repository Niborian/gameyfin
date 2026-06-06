package org.gameyfin.app.games.dto

import org.gameyfin.app.games.entities.VariantContentType

data class GroupGameAsVariantRequestDto(
    val sourceGameId: Long,
    val variantName: String?,
    val version: String?,
    val contentName: String?,
    val contentType: VariantContentType?,
    val required: Boolean?,
    val defaultSelected: Boolean?,
    val tags: Set<String>?,
    val steamAppId: String?,
    val launchArgs: String?,
    val patchInfo: String?
)
