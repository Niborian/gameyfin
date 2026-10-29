package org.gameyfin.app.users

import com.vaadin.hilla.Endpoint
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import org.gameyfin.app.core.Role
import org.gameyfin.app.users.avatar.AvatarInvalidations
import org.gameyfin.app.users.avatar.NativeAvatarState
import org.gameyfin.app.core.plugins.PluginEndpointAccess
import org.springframework.security.access.AccessDeniedException
import reactor.core.publisher.Flux

@Endpoint
class UserAvatarEndpoint(private val access: PluginEndpointAccess, private val nativeAvatars: NativeAvatarState? = null) {
    @PermitAll
    fun subscribeOwn(): Flux<Long> {
        if (nativeAvatars == null) return Flux.empty()
        val actor = access.capture()
        if (actor.authentication == null || actor.session == null) throw AccessDeniedException("Current avatar session required")
        val ownId = actor.account?.id ?: throw AccessDeniedException("Current avatar account required")
        return access.stream(actor, { AvatarInvalidations.updates().filter { it == ownId }.map { listOf(it) } }) { it }
            .flatMapIterable { it }
    }
    @RolesAllowed(Role.Names.ADMIN)
    fun subscribeAll(): Flux<Long> {
        if (nativeAvatars == null) return Flux.empty()
        val actor = access.captureAdministrator()
        return access.stream(actor, { AvatarInvalidations.updates().map { listOf(it) } }) { it }.flatMapIterable { it }
    }
}
