package org.gameyfin.app.users.extensions

import org.gameyfin.app.core.Role
import org.gameyfin.app.users.dto.ExtendedUserInfoDto
import org.gameyfin.app.users.dto.UserInfoDto
import org.gameyfin.app.users.entities.User
import org.gameyfin.app.users.avatar.AvatarLedger
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority

fun User.toUserInfoDto(avatarState: AvatarLedger.Current? = null): UserInfoDto {
    return UserInfoDto(
        id = this.id!!,
        username = this.username,
        hasAvatar = avatarState?.let { it.kind != AvatarLedger.Kind.ABSENT } ?: (this.avatar != null),
        avatarId = when (avatarState?.kind) {
            null -> this.avatar?.id
            AvatarLedger.Kind.LEGACY -> avatarState.legacyImageId
            else -> null
        },
        avatarVersion = avatarState?.version
    )
}

fun User.toExtendedUserInfoDto(avatarState: AvatarLedger.Current? = null): ExtendedUserInfoDto {
    return ExtendedUserInfoDto(
        id = this.id!!,
        username = this.username,
        email = this.email,
        emailConfirmed = this.emailConfirmed,
        enabled = this.enabled,
        hasAvatar = avatarState?.let { it.kind != AvatarLedger.Kind.ABSENT } ?: (this.avatar != null),
        avatarId = when (avatarState?.kind) {
            null -> this.avatar?.id
            AvatarLedger.Kind.LEGACY -> avatarState.legacyImageId
            else -> null
        },
        avatarVersion = avatarState?.version,
        managedBySso = this.oidcProviderId != null,
        roles = this.roles
    )
}

fun Collection<Role>.toAuthorities(): List<GrantedAuthority> {
    return this.map { r -> SimpleGrantedAuthority(r.roleName) }
}
