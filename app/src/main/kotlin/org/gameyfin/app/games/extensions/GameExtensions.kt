package org.gameyfin.app.games.extensions

import org.gameyfin.app.core.security.isCurrentUserAdmin
import org.gameyfin.app.games.dto.*
import org.gameyfin.app.games.entities.*
import org.gameyfin.app.media.toDto
import java.time.ZoneOffset


fun Game.toDto(): GameDto {
    return if (isCurrentUserAdmin()) {
        this.toAdminDto()
    } else {
        this.toUserDto()
    }
}

fun Collection<Game>.toDtos(): List<GameDto> {
    return if (isCurrentUserAdmin()) {
        this.map { it.toAdminDto() }
    } else {
        this.map { it.toUserDto() }
    }
}

fun Game.toAdminDto(): GameAdminDto {
    return GameAdminDto(
        id = id!!,
        createdAt = createdAt!!,
        updatedAt = updatedAt!!,
        libraryId = this.library.id!!,
        collectionIds = this.collections.mapNotNull { it.id },
        title = title!!,
        platforms = this.platforms,
        cover = this.coverImage?.toDto(),
        header = this.headerImage?.toDto(),
        comment = this.comment,
        summary = this.summary,
        release = this.release?.atZone(ZoneOffset.UTC)?.toLocalDate(),
        userRating = this.userRating,
        criticRating = this.criticRating,
        publishers = this.publishers.map { it.name },
        developers = this.developers.map { it.name },
        genres = this.genres,
        themes = this.themes,
        keywords = this.keywords.toList(),
        features = this.features,
        perspectives = this.perspectives,
        images = this.images.map { it.toDto() },
        videoUrls = this.videoUrls.map { it.toString() },
        variants = this.variants.map { it.toDto(includeAdminFields = true) },
        metadata = this.metadata.toAdminDto()
    )
}

fun Game.toUserDto(): GameUserDto {
    return GameUserDto(
        id = id!!,
        createdAt = createdAt!!,
        updatedAt = updatedAt!!,
        libraryId = this.library.id!!,
        collectionIds = this.collections.mapNotNull { it.id },
        title = title!!,
        platforms = this.platforms,
        cover = this.coverImage?.toDto(),
        header = this.headerImage?.toDto(),
        comment = this.comment,
        summary = this.summary,
        release = this.release?.atZone(ZoneOffset.UTC)?.toLocalDate(),
        userRating = this.userRating,
        criticRating = this.criticRating,
        publishers = this.publishers.map { it.name },
        developers = this.developers.map { it.name },
        genres = this.genres,
        themes = this.themes,
        keywords = this.keywords.toList(),
        features = this.features,
        perspectives = this.perspectives,
        images = this.images.map { it.toDto() },
        videoUrls = this.videoUrls.map { it.toString() },
        variants = this.variants.map { it.toDto(includeAdminFields = false) },
        metadata = this.metadata.toUserDto()
    )
}

fun GameVariant.toDto(includeAdminFields: Boolean): GameVariantDto {
    return GameVariantDto(
        id = id!!,
        name = name,
        version = version,
        path = path.takeIf { includeAdminFields },
        fileSize = fileSize ?: 0L,
        tags = tags,
        steamAppId = steamAppId,
        launchArgs = launchArgs,
        patchInfo = patchInfo,
        isDefault = isDefault,
        isLatestForVariant = isLatestForVariant,
        linkStatus = linkStatus,
        linkFallbackReason = linkFallbackReason.takeIf { includeAdminFields },
        contents = contents.map { it.toDto(includeAdminFields) }
    )
}

fun VariantContent.toDto(includeAdminFields: Boolean): VariantContentDto {
    val effectivePaths = effectivePaths()
    return VariantContentDto(
        id = id!!,
        type = type,
        name = name,
        path = path.takeIf { includeAdminFields },
        paths = effectivePaths.takeIf { includeAdminFields },
        pathCount = effectivePaths.size,
        fileSize = fileSize ?: 0L,
        required = required,
        defaultSelected = defaultSelected,
        tags = tags
    )
}

fun GameMetadata.toAdminDto(): GameMetadataAdminDto {
    return GameMetadataAdminDto(
        fileSize = this.fileSize ?: 0L,
        downloadCount = this.downloadCount,
        path = this.path,
        fields = this.fields.mapValues { it.value.toDto() },
        originalIds = this.originalIds.mapKeys { it.key.pluginId },
        matchConfirmed = this.matchConfirmed
    )
}

fun GameMetadata.toUserDto(): GameMetadataUserDto {
    return GameMetadataUserDto(
        fileSize = this.fileSize ?: 0L
    )
}

private fun GameFieldMetadata.toDto(): GameFieldMetadataDto {
    return when (val source = this.source) {
        is GameFieldPluginSource -> {
            GameFieldMetadataDto(
                type = GameFieldMetadataType.PLUGIN,
                source = source.plugin.pluginId,
                updatedAt = this.updatedAt!!
            )
        }

        is GameFieldUserSource -> {
            GameFieldMetadataDto(
                type = GameFieldMetadataType.USER,
                source = source.user.username,
                updatedAt = this.updatedAt!!
            )
        }

        else -> {
            GameFieldMetadataDto(
                type = GameFieldMetadataType.UNKNOWN,
                source = "unknown source",
                updatedAt = this.updatedAt!!
            )
        }
    }
}
