package org.gameyfin.app.games.dto

/** Player-visible labels only. Never include paths, audit evidence or operator fields. */
data class VariantDisplayDto(val variantId: Long, val libraryVersion: String?, val edition: String?)
