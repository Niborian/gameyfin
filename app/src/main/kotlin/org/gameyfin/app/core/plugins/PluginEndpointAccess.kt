package org.gameyfin.app.core.plugins

import jakarta.servlet.http.HttpSession
import org.gameyfin.app.config.ConfigProperties
import org.gameyfin.app.config.ConfigService
import org.gameyfin.app.core.Role
import org.gameyfin.app.core.security.getCurrentAuth
import org.gameyfin.app.core.security.isAdmin
import org.gameyfin.app.users.persistence.UserRepository
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.session.SessionRegistry
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import reactor.core.publisher.Flux

/** Server-only captured authority. Never serialized and never resolved from a Reactor thread local. */
class PluginAudience internal constructor(
    internal val authentication: Authentication?, internal val session: HttpSession?,
    internal val sessionId: String?, internal val account: PluginAccountSnapshot?,
    val administrator: Boolean,
) {
    override fun toString() = "PluginAudience(administrator=$administrator)"
}

class PluginAccountSnapshot(val id: Long, val username: String, val oidcSubject: String?, val enabled: Boolean, val administrator: Boolean)

/** A fresh transaction avoids retaining an outer persistence-context role snapshot. */
@Service
class PluginAccountPolicy(private val users: UserRepository) {
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    fun resolve(authentication: Authentication): PluginAccountSnapshot? {
        val principal = authentication.principal
        val user = if (principal is OidcUser) users.findByOidcProviderId(principal.subject)
        else users.findByUsername(authentication.name)
        return user?.let { snapshot(it) }
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    fun current(id: Long): PluginAccountSnapshot? = users.findById(id).orElse(null)?.let { snapshot(it) }

    private fun snapshot(user: org.gameyfin.app.users.entities.User) = PluginAccountSnapshot(
        requireNotNull(user.id), user.username, user.oidcProviderId, user.enabled,
        user.roles.any { it == Role.ADMIN || it == Role.SUPERADMIN })
}

@Service
class PluginEndpointAccess(
    private val accounts: PluginAccountPolicy,
    private val registry: SessionRegistry,
    private val config: ConfigService,
) {
    fun capture(): PluginAudience {
        val auth = getCurrentAuth()?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
        if (auth == null) return PluginAudience(null, null, null, null, false).also(::requireCurrent)
        val administrator = auth.isAdmin()
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
        val session = request?.getSession(false)
        val account = accounts.resolve(auth) ?: denied()
        return PluginAudience(auth, session, session?.id, account, administrator).also(::requireCurrent)
    }

    fun captureAdministrator(): PluginAudience = capture().also { if (!it.administrator) denied() }

    fun requireCurrent(actor: PluginAudience) {
        val auth = actor.authentication
        if (auth == null) {
            if (config.get(ConfigProperties.Security.AllowPublicAccess) != true) denied()
            return
        }
        // No fallback to worker-thread authentication, nor to a newly looked-up session owner.
        if (!auth.isAuthenticated) denied()
        val captured = actor.account ?: denied()
        val current = accounts.current(captured.id) ?: denied()
        if (!current.enabled || current.username != captured.username || current.oidcSubject != captured.oidcSubject) denied()
        if (actor.administrator && (!auth.isAdmin() || !current.administrator)) denied()
        val session = actor.session
        // An administrator must prove the actual persisted browser session. No session means denial,
        // not a metadata-only response that the admin UI could mistake for configuration defaults.
        if (session == null) {
            if (actor.administrator) denied()
            return
        }
        try {
            if (session.id != actor.sessionId) denied()
            val context = session.getAttribute(SPRING_SECURITY_CONTEXT_KEY) as? SecurityContext ?: denied()
            if (context.authentication !== auth) denied()
            val information = registry.getSessionInformation(requireNotNull(actor.sessionId)) ?: denied()
            if (information.isExpired || information.principal !== auth.principal) denied()
        } catch (_: IllegalStateException) {
            denied()
        }
    }

    fun <T : Any> stream(actor: PluginAudience, source: () -> Flux<List<T>>, project: (T) -> T): Flux<List<T>> =
        Flux.defer {
            requireCurrent(actor)
            source().handle<List<T>> { batch, sink ->
                try {
                    requireCurrent(actor)
                    val output = batch.map(project)
                    requireCurrent(actor)
                    sink.next(output)
                } catch (failure: Exception) {
                    sink.error(failure)
                }
            }
        }

    private fun denied(): Nothing = throw AccessDeniedException("Plugin access requires a current authorized session")
}
