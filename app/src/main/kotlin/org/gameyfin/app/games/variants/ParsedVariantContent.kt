package org.gameyfin.app.games.variants

import org.gameyfin.app.games.entities.VariantContentType
import java.nio.file.Path

data class ParsedVariantContent(
    val key: String,
    val type: VariantContentType,
    val name: String,
    val path: Path,
    val required: Boolean,
    val defaultSelected: Boolean,
    val tags: Set<String>
)
