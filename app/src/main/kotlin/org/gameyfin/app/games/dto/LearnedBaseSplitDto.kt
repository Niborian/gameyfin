package org.gameyfin.app.games.dto

import org.gameyfin.app.games.entities.VariantContentType

data class LearnedBaseSplitItemDto(
    val name: String,
    val sourceName: String,
    val type: VariantContentType,
    val required: Boolean,
    val defaultSelected: Boolean,
    val tags: List<String>
)

data class LearnedBaseSplitPreviewDto(
    val revision: String,
    val version: String,
    val items: List<LearnedBaseSplitItemDto>,
    val confirmations: Int
)

data class LearnedBaseSplitRuleDto(
    val id: String,
    val libraryName: String,
    val layout: String,
    val confirmations: Int,
    val automatic: Boolean,
    val muted: Boolean,
    val offer: Boolean
)

data class LearnedBaseSplitReviewDto(
    val preview: LearnedBaseSplitPreviewDto?,
    val rule: LearnedBaseSplitRuleDto?
)
