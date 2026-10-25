package org.gameyfin.app.core.download.files

import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.games.entities.GameVariant
import org.gameyfin.app.games.entities.VariantContent
import org.gameyfin.app.games.entities.VariantRetirementState
import org.gameyfin.app.games.entities.effectivePaths
import org.springframework.stereotype.Component
import java.nio.file.Path

/** Shared host membership gate; never an arbitrary-path or descriptor identity capability. */
@Component
class DownloadSelectionResolver {
    fun resolveSelection(game: Game, variantId: Long?, contentIds: List<Long>?): Pair<GameVariant, List<VariantContent>> {
        val variant = selectVariant(game, variantId)
        return variant to selectContents(variant, contentIds)
    }

    private fun selectVariant(game: Game, variantId: Long?): GameVariant {
        val availableVariants = game.variants.filter {
            it.retirementState == VariantRetirementState.ACTIVE
        }
        if (availableVariants.isEmpty()) {
            throw IllegalStateException("Game '${game.id}' has no downloadable variants")
        }

        return if (variantId != null) {
            availableVariants.firstOrNull { it.id == variantId }
                ?: throw IllegalArgumentException("Variant $variantId not found for game ${game.id}")
        } else {
            availableVariants.firstOrNull { it.defaultLocked }
                ?: availableVariants.firstOrNull { it.name.equals("Normal", ignoreCase = true) && it.isLatestForVariant }
                ?: availableVariants.firstOrNull { it.isDefault }
                ?: availableVariants.firstOrNull { it.isLatestForVariant }
                ?: availableVariants.first()
        }
    }

    private fun selectContents(variant: GameVariant, contentIds: List<Long>?): List<VariantContent> {
        val selected = if (contentIds == null) {
            variant.contents.filter { it.required || it.defaultSelected }
        } else {
            val selectedIds = contentIds.toSet()
            val availableIds = variant.contents.mapNotNull { it.id }.toSet()
            require(availableIds.containsAll(selectedIds)) {
                "Selected content does not belong to variant ${variant.id}"
            }
            variant.contents.filter { it.id in selectedIds || it.required }
        }

        val contents = selected.ifEmpty {
            listOf(
                VariantContent(
                    variant = variant,
                    name = "Base game",
                    path = variant.path,
                    fileSize = variant.fileSize,
                    required = true,
                    defaultSelected = true
                )
            )
        }

        validateContentPaths(variant, contents)
        return contents
    }

    private fun validateContentPaths(variant: GameVariant, contents: List<VariantContent>) {
        val variantPath = Path.of(variant.path).toRealPath()
        // Variants can be folders below a game root, or files linked into a managed mirror.
        // Their parent is the smallest stable root that permits shared, sibling content without
        // allowing a selection to reach outside the game/mirror tree.
        val contentRoot = variantPath.parent
            ?: throw IllegalArgumentException("Variant ${variant.id} has no content root")

        contents.forEach { content ->
            val contentPaths = content.effectivePaths()
            require(contentPaths.isNotEmpty()) { "Content '${content.name}' has no download paths" }

            contentPaths.forEach { contentPath ->
                val resolvedPath = Path.of(contentPath).toRealPath()
                require(resolvedPath.startsWith(contentRoot)) {
                    "Content path '$contentPath' is outside variant ${variant.id} content root '$contentRoot'"
                }
            }
        }
    }

}
