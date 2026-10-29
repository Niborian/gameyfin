package org.gameyfin.app.users.avatar

import io.mockk.*
import org.gameyfin.app.core.Role
import org.gameyfin.app.users.UserAvatarEndpoint
import org.gameyfin.app.core.plugins.PluginAccountPolicy
import org.gameyfin.app.core.plugins.PluginAccountSnapshot
import org.gameyfin.app.core.plugins.PluginEndpointAccess
import org.gameyfin.app.config.ConfigService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import reactor.test.StepVerifier
import java.time.Duration
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.session.SessionRegistryImpl
import org.springframework.security.core.userdetails.User
import org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AvatarInvalidationsTest {
    @BeforeEach fun ownedAdmission() = AvatarInvalidations.startOwnedDelivery()
    private val policy = mockk<PluginAccountPolicy>()
    private val registry = SessionRegistryImpl()
    private val access = PluginEndpointAccess(policy, registry, mockk<ConfigService>())
    @Volatile private var account = PluginAccountSnapshot(101, "fixture", null, true, false)
    private lateinit var session: MockHttpSession
    private fun login(admin: Boolean = false) {
        val authorities = listOf(SimpleGrantedAuthority(if (admin) Role.Names.ADMIN else Role.Names.USER))
        val principal = User("fixture", "", authorities)
        val auth = UsernamePasswordAuthenticationToken(principal, null, authorities)
        SecurityContextHolder.getContext().authentication = auth
        session = MockHttpSession()
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, SecurityContextHolder.getContext())
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest().apply { setSession(this@AvatarInvalidationsTest.session) }))
        registry.registerNewSession(session.id, principal)
        account = PluginAccountSnapshot(101, "fixture", null, true, admin)
        every { policy.resolve(auth) } answers { account }
        every { policy.current(101) } answers { account }
    }
    @AfterEach fun cleanup() {
        AvatarInvalidations.close()
        SecurityContextHolder.clearContext(); RequestContextHolder.resetRequestAttributes(); unmockkAll()
    }
    @Test fun `own subscription is native identity scoped and disabled overlay has no stream`() {
        login()
        val endpoint = UserAvatarEndpoint(access, mockk())
        StepVerifier.create(endpoint.subscribeOwn()).then {
            AvatarInvalidations.changed(102); AvatarInvalidations.changed(101)
        }.expectNext(101L).thenCancel().verify(Duration.ofSeconds(3))
        StepVerifier.create(UserAvatarEndpoint(access).subscribeOwn()).verifyComplete()
        assertNotNull(UserAvatarEndpoint::class.java.getMethod("subscribeOwn").getAnnotation(PermitAll::class.java))
        assertEquals(listOf(Role.Names.ADMIN), UserAvatarEndpoint::class.java.getMethod("subscribeAll")
            .getAnnotation(RolesAllowed::class.java).value.toList())
    }
    @Test fun `open streams stop delivery after session account or administrator authority loss`() {
        for (reason in listOf("session", "disabled", "downgrade")) {
            AvatarInvalidations.startOwnedDelivery()
            login(admin = true)
            val stream = UserAvatarEndpoint(access, mockk()).subscribeAll()
            StepVerifier.create(stream).then { AvatarInvalidations.changed(101) }.expectNext(101L).then {
                when (reason) {
                    "session" -> requireNotNull(registry.getSessionInformation(session.id)).expireNow()
                    "disabled" -> account = PluginAccountSnapshot(101, "fixture", null, false, true)
                    else -> account = PluginAccountSnapshot(101, "fixture", null, true, false)
                }
                AvatarInvalidations.changed(102)
            }.expectError(AccessDeniedException::class.java).verify(Duration.ofSeconds(3))
            AvatarInvalidations.close()
        }
    }
    @Test fun `blocking subscriber never blocks committed writer invalidation`() {
        val entered = CountDownLatch(1); val allow = CountDownLatch(1)
        val subscription = AvatarInvalidations.updates().subscribe {
            entered.countDown(); check(allow.await(3, TimeUnit.SECONDS))
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            AvatarInvalidations.changed(101)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            executor.submit { AvatarInvalidations.changed(102) }.get(500, TimeUnit.MILLISECONDS)
        } finally {
            allow.countDown(); subscription.dispose(); executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }
    @Test fun `late subscribers cannot resurrect delivery after owner shutdown`() {
        AvatarInvalidations.close()
        StepVerifier.create(AvatarInvalidations.updates()).verifyComplete()
        AvatarInvalidations.changed(101)
        StepVerifier.create(AvatarInvalidations.updates()).verifyComplete()
    }
}
