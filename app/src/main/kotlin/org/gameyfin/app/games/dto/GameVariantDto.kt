package org.gameyfin.app.games.dto

import com.fasterxml.jackson.annotation.JsonInclude
import org.gameyfin.app.games.entities.VariantContentType
import org.gameyfin.app.games.entities.VariantLinkStatus

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GameVariantDto(
    val id: Long,
    val name: String,
    val version: String,
    val path: String?,
    val fileSize: Long,
    val tags: Set<String>,
    val steamAppId: String?,
    val launchArgs: String?,
    val patchInfo: String?,
    val isDefault: Boolean,
    val defaultLocked: Boolean,
    val isLatestForVariant: Boolean,
    val linkStatus: VariantLinkStatus,
    val linkFallbackReason: String?,
    val contents: List<VariantContentDto>
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class VariantContentDto(
    val id: Long,
    val type: VariantContentType,
    val name: String,
    val path: String?,
    val paths: List<String>?,
    val pathCount: Int,
    val fileSize: Long,
    val required: Boolean,
    val defaultSelected: Boolean,
    val tags: Set<String>
)
