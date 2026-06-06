package org.gameyfin.app.games.variants

import java.nio.file.Path

data class ParsedVariantMetadata(
    val name: String,
    val version: String,
    val path: Path,
    val tags: Set<String>,
    val steamAppId: String?,
    val launchArgs: String?,
    val patchInfo: String?,
    val contents: List<ParsedVariantContent>
)
