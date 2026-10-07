package org.gameyfin.app.games.variants

import org.gameyfin.app.core.filesystem.FilesystemService
import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.games.entities.GameVariant
import org.gameyfin.app.games.entities.VariantContent
import org.gameyfin.app.games.entities.VariantLinkStatus
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.libraries.entities.Library
import org.gameyfin.app.libraries.entities.LibraryStorageMode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Path

@Service
class GameVariantService(
    private val gameRepository: GameRepository,
    private val filesystemService: FilesystemService,
    private val hardlinkMirrorService: HardlinkMirrorService
) {
    @Transactional
    fun syncVariants(game: Game, discovery: DiscoveredGameVariants, library: Library): Game {
        val desiredKeys = discovery.variants.map { VariantKey(it.name, it.version) }.toSet()
        game.variants.removeIf { it.scanManaged && VariantKey(it.name, it.version) !in desiredKeys }

        val pinnedDefault = game.variants.firstOrNull { it.defaultLocked }

        discovery.variants.forEach { parsed ->
            val key = VariantKey(parsed.name, parsed.version)
            // An administrator-owned variant may have attached content outside the
            // discovery root. A matching scanner key must not replace that content.
            val unmanagedVariant = game.variants.firstOrNull {
                !it.scanManaged && (it.path == parsed.path.toString() || VariantKey(it.name, it.version) == key)
            }
            if (unmanagedVariant != null) return@forEach

            val existing = game.variants.firstOrNull { VariantKey(it.name, it.version) == key }
                ?: GameVariant(game = game, path = parsed.path.toString()).also { game.variants.add(it) }

            val variantLink = resolvePath(parsed.path, library, discovery.gamePath, "${parsed.name}-${parsed.version}")
            val contentLinkResults = parsed.contents.associateWith { content ->
                if (content.path == parsed.path) variantLink
                else resolvePath(content.path, library, discovery.gamePath, "${parsed.name}-${parsed.version}-${content.key}")
            }
            val fallbackReasons = contentLinkResults.values.mapNotNull { it.fallbackReason }.distinct()

            existing.name = parsed.name
            existing.version = parsed.version
            existing.path = variantLink.path.toString()
            existing.fileSize = filesystemService.calculateFileSize(variantLink.path.toString())
            existing.tags.clear()
            existing.tags.addAll(parsed.tags)
            if (!existing.steamAppIdManualOverride) {
                existing.steamAppId = parsed.steamAppId
            }
            existing.launchArgs = parsed.launchArgs
            existing.patchInfo = parsed.patchInfo
            existing.scanManaged = true
            existing.linkStatus = if (variantLink.status == VariantLinkStatus.COPIED_FALLBACK) {
                VariantLinkStatus.COPIED_FALLBACK
            } else {
                variantLink.status
            }
            existing.linkFallbackReason = fallbackReasons.firstOrNull() ?: variantLink.fallbackReason

            syncContents(existing, parsed.contents, contentLinkResults)
        }

        // Include attached versions outside discovery when deriving latest/default state.
        game.variants.groupBy { it.name }.values.forEach { variants ->
            val latest = VariantVersionComparator.newest(variants.map { it.version })
            variants.forEach { it.isLatestForVariant = it.version == latest }
        }
        val normalVariants = game.variants.filter { it.name.equals("Normal", ignoreCase = true) }
        val selectedDefault = pinnedDefault ?: normalVariants.ifEmpty { game.variants }.maxWithOrNull { first, second ->
            VariantVersionComparator.compare(first.version, second.version)
        }
        game.variants.forEach { it.isDefault = it === selectedDefault }

        return gameRepository.save(game)
    }

    private fun syncContents(
        variant: GameVariant,
        parsedContents: List<ParsedVariantContent>,
        linkResults: Map<ParsedVariantContent, HardlinkMirrorService.LinkResult>
    ) {
        val desiredKeys = parsedContents.map { ContentKey(it.type, it.name) }.toSet()
        variant.contents.removeIf { ContentKey(it.type, it.name) !in desiredKeys }

        parsedContents.forEach { parsed ->
            val content = variant.contents.firstOrNull { ContentKey(it.type, it.name) == ContentKey(parsed.type, parsed.name) }
                ?: VariantContent(variant = variant, type = parsed.type, name = parsed.name, path = parsed.path.toString())
                    .also { variant.contents.add(it) }
            val linkResult = linkResults.getValue(parsed)

            content.type = parsed.type
            content.name = parsed.name
            content.path = linkResult.path.toString()
            content.fileSize = filesystemService.calculateFileSize(linkResult.path.toString())
            content.paths.clear()
            content.paths.add(linkResult.path.toString())
            content.required = parsed.required
            content.defaultSelected = parsed.defaultSelected
            content.tags.clear()
            content.tags.addAll(parsed.tags)
        }
    }

    private fun resolvePath(
        source: Path,
        library: Library,
        gamePath: Path,
        targetName: String
    ): HardlinkMirrorService.LinkResult {
        return when (library.storageMode) {
            LibraryStorageMode.DIRECT -> HardlinkMirrorService.LinkResult(source, VariantLinkStatus.DIRECT, null)
            LibraryStorageMode.HARDLINK_MIRROR -> hardlinkMirrorService.mirror(source, library, gamePath, targetName)
        }
    }

    private data class VariantKey(val name: String, val version: String)
    private data class ContentKey(val type: org.gameyfin.app.games.entities.VariantContentType, val name: String)
}
