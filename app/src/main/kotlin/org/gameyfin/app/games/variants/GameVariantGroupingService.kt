package org.gameyfin.app.games.variants

import org.gameyfin.app.core.filesystem.FilesystemService
import org.gameyfin.app.games.dto.GameGroupingSuggestionDto
import org.gameyfin.app.games.dto.GroupGameAsVariantRequestDto
import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.games.entities.GameVariant
import org.gameyfin.app.games.entities.VariantContent
import org.gameyfin.app.games.entities.VariantContentType
import org.gameyfin.app.games.entities.VariantLinkStatus
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.libraries.IgnoredPathRepository
import org.gameyfin.app.libraries.entities.IgnoredPath
import org.gameyfin.app.libraries.entities.IgnoredPathGroupedVariantSource
import org.gameyfin.app.libraries.entities.Library
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Path
import java.time.ZoneOffset
import kotlin.io.path.isDirectory

@Service
class GameVariantGroupingService(
    private val gameRepository: GameRepository,
    private val ignoredPathRepository: IgnoredPathRepository,
    private val filesystemService: FilesystemService
) {
    private val trailingVersionPattern =
        Regex("""(?i)(?:^|[\s._-])v?(\d+(?:\.\d+)*(?:[-+_][a-z0-9._-]+)?)$""")

    fun getGroupingSuggestions(libraryId: Long): List<GameGroupingSuggestionDto> {
        val games = gameRepository.findAllByLibraryId(libraryId)
        return buildSuggestions(games, includeAutoGroups = true)
    }

    @Transactional
    fun tryAutoGroup(candidate: Game, discovery: DiscoveredGameVariants, library: Library): Game? {
        val exactTargets = library.games
            .filter { it.id != candidate.id && it.metadata.path != candidate.metadata.path }
            .filter { confidence(candidate, it).confidence == 100 }

        if (exactTargets.size != 1) return null

        val target = exactTargets.single()
        val variantMetadata = variantMetadataFromCandidate(candidate, target, discovery)
        addOrUpdateExternalVariant(target, variantMetadata)
        addGroupedIgnoredPath(library, variantMetadata.path)

        return gameRepository.save(target)
    }

    @Transactional
    fun autoGroupExactMatches(library: Library): Int {
        val libraryId = library.id ?: return 0
        var grouped = 0
        val groupedSources = mutableSetOf<Long>()

        buildSuggestions(gameRepository.findAllByLibraryId(libraryId), includeAutoGroups = true)
            .filter { it.confidence == 100 }
            .forEach { suggestion ->
                if (!groupedSources.add(suggestion.sourceGameId)) return@forEach
                val target = gameRepository.findByIdOrNull(suggestion.targetGameId) ?: return@forEach
                val source = gameRepository.findByIdOrNull(suggestion.sourceGameId) ?: return@forEach
                if (target.library.id != source.library.id) return@forEach

                groupManagedSourceIntoTarget(
                    target = target,
                    source = source,
                    variantName = suggestion.suggestedVariantName,
                    version = suggestion.suggestedVariantVersion,
                    contentName = "Base game",
                    contentType = VariantContentType.BASE,
                    required = true,
                    defaultSelected = true,
                    tags = emptySet(),
                    steamAppId = null,
                    launchArgs = null,
                    patchInfo = null
                )
                grouped++
            }

        return grouped
    }

    @Transactional
    fun groupGameAsVariant(targetGameId: Long, request: GroupGameAsVariantRequestDto): Game {
        val target = gameRepository.findByIdOrNull(targetGameId)
            ?: throw IllegalArgumentException("Target game $targetGameId not found")
        val source = gameRepository.findByIdOrNull(request.sourceGameId)
            ?: throw IllegalArgumentException("Source game ${request.sourceGameId} not found")

        require(target.id != source.id) { "Source and target game must be different" }
        require(target.library.id == source.library.id) { "Source and target game must be in the same library" }

        val defaults = variantMetadataFromCandidate(source, target, DiscoveredGameVariants(Path.of(source.metadata.path), emptyList()))
        return groupManagedSourceIntoTarget(
            target = target,
            source = source,
            variantName = request.variantName?.ifBlank { null } ?: defaults.name,
            version = request.version?.ifBlank { null } ?: defaults.version,
            contentName = request.contentName?.ifBlank { null } ?: "Base game",
            contentType = request.contentType ?: VariantContentType.BASE,
            required = request.required ?: true,
            defaultSelected = request.defaultSelected ?: true,
            tags = request.tags ?: emptySet(),
            steamAppId = request.steamAppId?.ifBlank { null },
            launchArgs = request.launchArgs?.ifBlank { null },
            patchInfo = request.patchInfo?.ifBlank { null }
        )
    }

    private fun groupManagedSourceIntoTarget(
        target: Game,
        source: Game,
        variantName: String,
        version: String,
        contentName: String,
        contentType: VariantContentType,
        required: Boolean,
        defaultSelected: Boolean,
        tags: Set<String>,
        steamAppId: String?,
        launchArgs: String?,
        patchInfo: String?
    ): Game {
        val sourcePath = source.metadata.path
        val sourceSize = filesystemService.calculateFileSize(sourcePath)
        val library = target.library
        val normalizedTags = tags.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()

        library.games.removeIf { it.id == source.id }
        addGroupedIgnoredPath(library, sourcePath)
        gameRepository.delete(source)
        gameRepository.flush()

        val metadata = ExternalVariantMetadata(
            name = variantName,
            version = version,
            path = sourcePath,
            fileSize = sourceSize,
            tags = normalizedTags,
            steamAppId = steamAppId,
            launchArgs = launchArgs,
            patchInfo = patchInfo,
            contentName = contentName,
            contentType = contentType,
            required = required,
            defaultSelected = defaultSelected
        )

        if (contentType == VariantContentType.BASE) {
            addOrUpdateExternalVariant(
                target,
                metadata.copy(version = uniqueVersion(target, variantName, version, sourcePath))
            )
        } else {
            addExternalContentToVariant(target, metadata)
        }
        refreshVariantFlags(target)

        return gameRepository.save(target)
    }

    private fun addOrUpdateExternalVariant(target: Game, metadata: ExternalVariantMetadata) {
        val variant = target.variants.firstOrNull { it.path == metadata.path }
            ?: GameVariant(game = target, path = metadata.path).also { target.variants.add(it) }

        variant.name = metadata.name
        variant.version = uniqueVersion(target, metadata.name, metadata.version, metadata.path)
        variant.path = metadata.path
        variant.fileSize = metadata.fileSize
        variant.tags.clear()
        variant.tags.addAll(metadata.tags)
        variant.steamAppId = metadata.steamAppId
        variant.launchArgs = metadata.launchArgs
        variant.patchInfo = metadata.patchInfo
        variant.isDefault = target.variants.none { it !== variant && it.isDefault }
        variant.isLatestForVariant = true
        variant.scanManaged = false
        variant.linkStatus = VariantLinkStatus.DIRECT
        variant.linkFallbackReason = null

        variant.contents.clear()
        variant.contents.add(
            VariantContent(
                variant = variant,
                type = metadata.contentType,
                name = metadata.contentName,
                path = metadata.path,
                fileSize = metadata.fileSize,
                required = metadata.required,
                defaultSelected = metadata.defaultSelected
            )
        )
    }

    private fun addExternalContentToVariant(target: Game, metadata: ExternalVariantMetadata) {
        val variant = target.variants.firstOrNull { it.name == metadata.name && it.version == metadata.version }
            ?: target.variants.firstOrNull { it.isDefault && it.version == "0" }
            ?: target.variants.firstOrNull { it.version == "0" }
            ?: target.variants.firstOrNull { it.isDefault }
            ?: target.variants.firstOrNull()
            ?: createBaseVariant(target, metadata)

        variant.name = metadata.name
        variant.version = metadata.version
        variant.tags.addAll(metadata.tags)
        metadata.steamAppId?.let { variant.steamAppId = it }
        metadata.launchArgs?.let { variant.launchArgs = it }
        metadata.patchInfo?.let { variant.patchInfo = it }
        variant.isDefault = true
        variant.isLatestForVariant = true
        variant.scanManaged = false
        target.variants.filter { it !== variant }.forEach { it.isDefault = false }

        val content = variant.contents.firstOrNull { it.path == metadata.path }
            ?: variant.contents.firstOrNull { it.type == metadata.contentType && it.name == metadata.contentName }
            ?: VariantContent(variant = variant, type = metadata.contentType, name = metadata.contentName, path = metadata.path)
                .also { variant.contents.add(it) }

        content.type = metadata.contentType
        content.name = metadata.contentName
        content.path = metadata.path
        content.fileSize = metadata.fileSize
        content.required = metadata.required
        content.defaultSelected = metadata.defaultSelected
        content.tags.clear()
        content.tags.addAll(metadata.tags)
    }

    private fun createBaseVariant(target: Game, metadata: ExternalVariantMetadata): GameVariant {
        val basePath = target.metadata.path
        val baseSize = filesystemService.calculateFileSize(basePath)
        return GameVariant(
            game = target,
            name = metadata.name,
            version = metadata.version,
            path = basePath,
            fileSize = baseSize,
            isDefault = true,
            isLatestForVariant = true,
            scanManaged = false,
            linkStatus = VariantLinkStatus.DIRECT
        ).also { variant ->
            variant.contents.add(
                VariantContent(
                    variant = variant,
                    type = VariantContentType.BASE,
                    name = "Base game",
                    path = basePath,
                    fileSize = baseSize,
                    required = true,
                    defaultSelected = true
                )
            )
            target.variants.add(variant)
        }
    }

    private fun addGroupedIgnoredPath(library: Library, path: String) {
        val ignoredPath = ignoredPathRepository.findByPath(path)
            ?: ignoredPathRepository.save(IgnoredPath(path = path, source = IgnoredPathGroupedVariantSource()))

        if (ignoredPath.source !is IgnoredPathGroupedVariantSource) {
            ignoredPath.source = IgnoredPathGroupedVariantSource()
            ignoredPathRepository.save(ignoredPath)
        }

        if (!library.ignoredPaths.any { it.path == path }) {
            library.ignoredPaths.add(ignoredPath)
        }
    }

    private fun buildSuggestions(games: List<Game>, includeAutoGroups: Boolean): List<GameGroupingSuggestionDto> {
        return games
            .flatMapIndexed { index, first ->
                games.drop(index + 1).mapNotNull { second ->
                    val match = confidence(first, second)
                    if (match.confidence < 85) return@mapNotNull null
                    if (!includeAutoGroups && match.confidence == 100) return@mapNotNull null

                    val (target, source) = chooseTargetAndSource(first, second)
                    val variantMetadata = variantMetadataFromCandidate(
                        candidate = source,
                        target = target,
                        discovery = DiscoveredGameVariants(Path.of(source.metadata.path), emptyList())
                    )

                    GameGroupingSuggestionDto(
                        targetGameId = target.id!!,
                        targetTitle = target.title,
                        targetPath = target.metadata.path,
                        sourceGameId = source.id!!,
                        sourceTitle = source.title,
                        sourcePath = source.metadata.path,
                        confidence = match.confidence,
                        autoGroup = match.confidence == 100,
                        reason = match.reason,
                        suggestedVariantName = variantMetadata.name,
                        suggestedVariantVersion = variantMetadata.version
                    )
                }
            }
            .sortedWith(compareByDescending<GameGroupingSuggestionDto> { it.confidence }.thenBy { it.sourcePath })
    }

    private fun chooseTargetAndSource(first: Game, second: Game): Pair<Game, Game> {
        val firstPath = Path.of(first.metadata.path)
        val secondPath = Path.of(second.metadata.path)

        if (firstPath.isDirectory() && !secondPath.isDirectory()) return first to second
        if (!firstPath.isDirectory() && secondPath.isDirectory()) return second to first

        val firstCreated = first.createdAt
        val secondCreated = second.createdAt
        if (firstCreated != null && secondCreated != null && firstCreated != secondCreated) {
            return if (firstCreated.isBefore(secondCreated)) first to second else second to first
        }

        return if ((first.id ?: Long.MAX_VALUE) <= (second.id ?: Long.MAX_VALUE)) first to second else second to first
    }

    private fun confidence(first: Game, second: Game): MatchConfidence {
        val sharedIds = sharedOriginalIds(first, second)
        if (sharedIds.isNotEmpty()) {
            if (hasMixedFileAndDirectory(first, second)) {
                return MatchConfidence(
                    95,
                    "Exact metadata provider id match, but file/folder direction needs review: ${sharedIds.joinToString()}"
                )
            }
            return MatchConfidence(100, "Exact metadata provider id match: ${sharedIds.joinToString()}")
        }

        val firstTitle = normalizeTitle(first.title)
        val secondTitle = normalizeTitle(second.title)
        if (firstTitle.isBlank() || firstTitle != secondTitle) {
            return MatchConfidence(0, "Different titles")
        }

        val sameReleaseYear = releaseYear(first) != null && releaseYear(first) == releaseYear(second)
        val overlappingPlatforms = first.platforms.isNotEmpty() && first.platforms.any { it in second.platforms }

        return when {
            sameReleaseYear && overlappingPlatforms -> MatchConfidence(92, "Same normalized title, release year, and platform")
            sameReleaseYear -> MatchConfidence(88, "Same normalized title and release year")
            overlappingPlatforms -> MatchConfidence(85, "Same normalized title and platform")
            else -> MatchConfidence(0, "Same title only")
        }
    }

    private fun sharedOriginalIds(first: Game, second: Game): List<String> {
        val secondIds = second.metadata.originalIds
            .mapKeys { it.key.pluginId }

        return first.metadata.originalIds.mapNotNull { (plugin, originalId) ->
            if (secondIds[plugin.pluginId] == originalId) "${plugin.pluginId}:$originalId" else null
        }
    }

    private fun hasMixedFileAndDirectory(first: Game, second: Game): Boolean {
        return Path.of(first.metadata.path).isDirectory() != Path.of(second.metadata.path).isDirectory()
    }

    private fun variantMetadataFromCandidate(
        candidate: Game,
        target: Game?,
        discovery: DiscoveredGameVariants
    ): ExternalVariantMetadata {
        val discoveredVariant = discovery.variants.singleOrNull()
        val sourcePath = candidate.metadata.path
        val derivedVersion = extractVersion(sourcePath) ?: discoveredVariant?.version?.takeIf { it != "0" } ?: "0"

        return ExternalVariantMetadata(
            name = if (target != null && normalizeTitle(candidate.title) == normalizeTitle(target.title)) "Normal" else "Custom",
            version = derivedVersion,
            path = sourcePath,
            fileSize = filesystemService.calculateFileSize(sourcePath),
            tags = discoveredVariant?.tags ?: emptySet(),
            steamAppId = discoveredVariant?.steamAppId,
            launchArgs = discoveredVariant?.launchArgs,
            patchInfo = discoveredVariant?.patchInfo,
            contentName = "Base game",
            contentType = VariantContentType.BASE,
            required = true,
            defaultSelected = true
        )
    }

    private fun extractVersion(path: String): String? {
        val name = Path.of(path).fileName.toString().substringBeforeLast('.')
        return trailingVersionPattern.find(name)?.groupValues?.getOrNull(1)
    }

    private fun normalizeTitle(title: String?): String {
        return title
            .orEmpty()
            .replace(Regex("""\(\d{4}\)"""), "")
            .lowercase()
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    private fun releaseYear(game: Game): Int? {
        return game.release?.atZone(ZoneOffset.UTC)?.year
    }

    private fun uniqueVersion(target: Game, variantName: String, requestedVersion: String, path: String): String {
        if (target.variants.none { it.path != path && it.name == variantName && it.version == requestedVersion }) {
            return requestedVersion
        }

        var index = 2
        while (target.variants.any { it.path != path && it.name == variantName && it.version == "$requestedVersion-$index" }) {
            index++
        }
        return "$requestedVersion-$index"
    }

    private fun refreshVariantFlags(target: Game) {
        val newestVersionByName = target.variants
            .groupBy { it.name }
            .mapValues { (_, variants) -> VariantVersionComparator.newest(variants.map { it.version }) }

        target.variants.forEach { variant ->
            variant.isLatestForVariant = newestVersionByName[variant.name] == variant.version
        }

        if (target.variants.none { it.isDefault }) {
            target.variants.firstOrNull()?.isDefault = true
        }
    }

    private data class MatchConfidence(
        val confidence: Int,
        val reason: String
    )

    private data class ExternalVariantMetadata(
        val name: String,
        val version: String,
        val path: String,
        val fileSize: Long,
        val tags: Set<String>,
        val steamAppId: String?,
        val launchArgs: String?,
        val patchInfo: String?,
        val contentName: String,
        val contentType: VariantContentType,
        val required: Boolean,
        val defaultSelected: Boolean
    )
}
