package org.gameyfin.app.users.extensions

import org.gameyfin.app.core.Role
import org.gameyfin.app.media.Image
import org.gameyfin.app.media.ImageType
import org.gameyfin.app.users.dto.ExtendedUserInfoDto
import org.gameyfin.app.users.dto.UserInfoDto
import org.gameyfin.app.users.entities.User
import org.gameyfin.app.users.avatar.AvatarLedger
import org.junit.jupiter.api.Test
import org.springframework.security.core.authority.SimpleGrantedAuthority
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserExtensionsTest {

    @Test
    fun `toUserInfoDto should map fields correctly`() {
        val user = User(id = 10L, username = "alice", email = "a@example.com")
        val dto: UserInfoDto = user.toUserInfoDto()
        assertEquals(10L, dto.id)
        assertEquals("alice", dto.username)
        assertFalse(dto.hasAvatar)
        assertEquals(null, dto.avatarId)
    }

    @Test
    fun `toExtendedUserInfoDto should map extended fields`() {
        val user = User(
            id = 11L,
            username = "bob",
            email = "b@example.com",
            emailConfirmed = true,
            enabled = true,
            roles = listOf(Role.ADMIN)
        )
        val dto: ExtendedUserInfoDto = user.toExtendedUserInfoDto()
        assertEquals(11L, dto.id)
        assertEquals("bob", dto.username)
        assertEquals("b@example.com", dto.email)
        assertTrue(dto.emailConfirmed)
        assertTrue(dto.enabled)
        assertTrue(dto.roles.contains(Role.ADMIN))
    }

    @Test
    fun `collection of roles toAuthorities should convert role names`() {
        val authorities = listOf(Role.USER, Role.ADMIN).toAuthorities()
        assertEquals(2, authorities.size)
        assertTrue(authorities.any { it.authority == Role.Names.USER })
        assertTrue(authorities.any { it.authority == Role.Names.ADMIN })
    }

    @Test
    fun `toExtendedUserInfoDto should set managedBySso when oidcProviderId present`() {
        val user = User(
            id = 12L,
            username = "charlie",
            email = "c@example.com",
            oidcProviderId = "oidc",
            roles = listOf(Role.USER)
        )
        val dto = user.toExtendedUserInfoDto()
        assertTrue(dto.managedBySso)
    }

    @Test
    fun `toUserInfoDto should reflect avatar presence`() {
        val user = User(id = 13L, username = "dana", email = "d@example.com")
        user.avatar = Image(type = ImageType.AVATAR)
        val dto = user.toUserInfoDto()
        assertTrue(dto.hasAvatar)
    }

    @Test
    fun `toExtendedUserInfoDto should include avatar id when present`() {
        val user = User(id = 14L, username = "eve", email = "e@example.com")
        user.avatar = Image(id = 99L, type = ImageType.AVATAR)
        val dto = user.toExtendedUserInfoDto()
        assertEquals(99L, dto.avatarId)
        assertEquals(null, dto.avatarVersion)
        assertEquals(null, user.toUserInfoDto().avatarVersion)
    }

    @Test
    fun `authoritative absent snapshot hides stale legacy pointer in both projections`() {
        val user = User(id = 15L, username = "snapshot", email = "snapshot@example.com",
            avatar = Image(id = 99L, type = ImageType.AVATAR))
        val state = AvatarLedger.Current(4, AvatarLedger.Kind.ABSENT, null, null)
        val normal = user.toUserInfoDto(state); val extended = user.toExtendedUserInfoDto(state)
        assertFalse(normal.hasAvatar); assertFalse(extended.hasAvatar)
        assertEquals(null, normal.avatarId); assertEquals(null, extended.avatarId)
        assertEquals(4L, normal.avatarVersion); assertEquals(4L, extended.avatarVersion)
        assertEquals(99L, user.avatar?.id) // Projection does not mutate legacy custody.
    }

    @Test
    fun `canonical PNG snapshot has avatar without fabricating legacy image id`() {
        val user = User(id = 16L, username = "snapshot", email = "snapshot@example.com",
            avatar = Image(id = 99L, type = ImageType.AVATAR))
        val state = AvatarLedger.Current(5, AvatarLedger.Kind.PNG, "a".repeat(64), null)
        val normal = user.toUserInfoDto(state); val extended = user.toExtendedUserInfoDto(state)
        assertTrue(normal.hasAvatar); assertTrue(extended.hasAvatar)
        assertEquals(null, normal.avatarId); assertEquals(null, extended.avatarId)
        assertEquals(5L, normal.avatarVersion); assertEquals(5L, extended.avatarVersion)
    }

    @Test
    fun `legacy snapshot uses authoritative image id rather than stale User pointer`() {
        val user = User(id = 17L, username = "snapshot", email = "snapshot@example.com",
            avatar = Image(id = 99L, type = ImageType.AVATAR))
        val state = AvatarLedger.Current(6, AvatarLedger.Kind.LEGACY, null, 123)
        val normal = user.toUserInfoDto(state); val extended = user.toExtendedUserInfoDto(state)
        assertTrue(normal.hasAvatar); assertTrue(extended.hasAvatar)
        assertEquals(123L, normal.avatarId); assertEquals(123L, extended.avatarId)
        assertEquals(6L, normal.avatarVersion); assertEquals(6L, extended.avatarVersion)
    }

    @Test
    fun `toAuthorities should handle empty roles`() {
        val authorities = emptyList<Role>().toAuthorities()
        assertTrue(authorities.isEmpty())
    }

    @Test
    fun `toAuthorities should produce SimpleGrantedAuthority instances`() {
        val authorities = listOf(Role.USER).toAuthorities()
        assertTrue(authorities.first() is SimpleGrantedAuthority)
    }
}

