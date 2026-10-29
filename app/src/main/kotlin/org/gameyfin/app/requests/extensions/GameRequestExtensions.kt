package org.gameyfin.app.requests.extensions

import org.gameyfin.app.requests.dto.GameRequestDto
import org.gameyfin.app.requests.entities.GameRequest
import org.gameyfin.app.users.extensions.toUserInfoDto
import org.gameyfin.app.users.avatar.AvatarLedger

fun GameRequest.toDto(avatars: Map<Long, AvatarLedger.Current> = emptyMap()): GameRequestDto {
    return GameRequestDto(
        id = this.id!!,
        title = this.title,
        release = this.release,
        platform = this.platform,
        status = this.status,
        requester = this.requester?.let { it.toUserInfoDto(avatars[it.id]) },
        voters = this.voters.map { it.toUserInfoDto(avatars[it.id]) },
        createdAt = this.createdAt!!,
        updatedAt = this.updatedAt!!
    )
}

fun Collection<GameRequest>.toDtos(avatars: Map<Long, AvatarLedger.Current> = emptyMap()): List<GameRequestDto> {
    return this.map { it.toDto(avatars) }
}

/** IDs only, deduplicated once for a metadata-only list/event projection. */
fun Collection<GameRequest>.avatarUserIds(): Set<Long> = buildSet {
    for (request in this@avatarUserIds) {
        request.requester?.id?.let(::add)
        request.voters.forEach { user -> user.id?.let(::add) }
    }
}
