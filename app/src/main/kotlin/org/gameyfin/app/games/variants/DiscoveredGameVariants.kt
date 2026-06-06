package org.gameyfin.app.games.variants

import java.nio.file.Path

data class DiscoveredGameVariants(
    val gamePath: Path,
    val variants: List<ParsedVariantMetadata>
)
