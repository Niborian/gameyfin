package org.gameyfin.app.core.plugins

import io.mockk.*
import org.gameyfin.app.config.ConfigProperties
import org.gameyfin.app.config.ConfigService
import org.gameyfin.app.core.Role
import org.gameyfin.app.core.config.JacksonConfig
import org.gameyfin.app.core.plugins.dto.*
import org.gameyfin.app.core.plugins.management.PluginTrustLevel
import org.gameyfin.pluginapi.core.config.PluginConfigValidationResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.pf4j.PluginState
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.session.SessionRegistryImpl
import org.springframework.security.core.userdetails.User
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Synthetic values only. Real projection/Jackson/session/registry, mocked fresh-account boundary.
 * This is not an HTTP/WebSocket transport test nor proof of global account-session revocation. */
class PluginConfigBoundaryTest {
    private val secret = "SYNTHETIC-PLUGIN-SECRET-157"
    private val policy = mockk<PluginAccountPolicy>()
    private val registry = SessionRegistryImpl()
    @Volatile private var publicAccess = true
    private val config = mockk<ConfigService> {
        every { get(ConfigProperties.Security.AllowPublicAccess) } answers { publicAccess }
    }
    private val access = PluginEndpointAccess(policy, registry, config)
    private val service = mockk<PluginService>()
    private val endpoint = PluginEndpoint(service, access)
    @Volatile private var current = PluginAccountSnapshot(1L, "fixture", null, true, true)
    private lateinit var session: MockHttpSession
    private val json = JacksonConfig().jsonMapperFactory().build()
    private fun dto() = PluginDto("fixture", listOf("DownloadProvider"), "Fixture", "Public description",
        version = "1", author = "Fixture", hasLogo = false, state = PluginState.STARTED,
        config = mapOf("api-key" to secret), configMetadata = listOf(PluginConfigMetadataDto(
            "api-key", "String", "API key", "Fixture", secret, true, true, listOf(secret))),
        configValidation = PluginConfigValidationResult.INVALID(mapOf("api-key" to secret)),
        priority = 7, trustLevel = PluginTrustLevel.OFFICIAL)
    private fun login(admin: Boolean = true, oidc: Boolean = false) {
        val authorities = listOf(SimpleGrantedAuthority(if (admin) Role.Names.ADMIN else Role.Names.USER))
        val principal = if (oidc) mockk<OidcUser> {
            every { name } returns "subject"
            every { subject } returns "subject"
        } else User("fixture", "", authorities)
        val auth = UsernamePasswordAuthenticationToken(principal, null, authorities)
        SecurityContextHolder.getContext().authentication = auth
        session = MockHttpSession()
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, SecurityContextHolder.getContext())
        val request = MockHttpServletRequest().apply { setSession(this@PluginConfigBoundaryTest.session) }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
        registry.registerNewSession(session.id, principal)
        current = PluginAccountSnapshot(1L, "fixture", if (oidc) "subject" else null, true, admin)
        every { policy.resolve(auth) } answers { current }
        every { policy.current(1L) } answers { current }
    }
    @AfterEach fun cleanup() {
        SecurityContextHolder.clearContext()
        RequestContextHolder.resetRequestAttributes()
        unmockkAll()
    }
    private fun updates(source: Flux<List<PluginUpdateDto>>): Flux<List<PluginUpdateDto>> {
        mockkObject(PluginService.Companion)
        every { PluginService.subscribe() } returns source
        return endpoint.subscribe()
    }
    private fun secretUpdate() = PluginUpdateDto("fixture", PluginState.STARTED, config = mapOf("api-key" to secret),
        configValidation = PluginConfigValidationResult.INVALID(mapOf("api-key" to secret)), priority = 7)
    private fun assertSafe(value: Any) {
        val encoded = json.writeValueAsString(value)
        assertFalse(encoded.contains(secret))
        assertFalse(encoded.contains("api-key"))
        assertTrue(encoded.contains("fixture"))
    }
    @Test fun `anonymous and ordinary JSON contain public metadata but no config defaults or validation`() {
        every { service.getAll(false) } returns listOf(dto()) // defensive endpoint projection, even if service regresses
        assertSafe(endpoint.getAll())
        login(admin = false)
        assertSafe(endpoint.getAll())
        verify(exactly = 2) { service.getAll(false) }
        verify(exactly = 0) { service.getAll(true) }
    }
    @Test fun `current admin retains configuration JSON and exact session principal`() {
        login()
        every { service.getAll(true) } returns listOf(dto())
        assertTrue(json.writeValueAsString(endpoint.getAll()).contains(secret))
        assertSame(requireNotNull(SecurityContextHolder.getContext().authentication).principal,
            registry.getSessionInformation(session.id)!!.principal)
    }
    @Test fun `OIDC original principal identity remains registry compatible`() {
        login(oidc = true)
        every { service.getAll(true) } returns listOf(dto())
        assertTrue(json.writeValueAsString(endpoint.getAll()).contains(secret))
    }
    @Test fun `administrator without actual servlet session is denied rather than empty config`() {
        login()
        RequestContextHolder.resetRequestAttributes()
        assertFailsWith<AccessDeniedException> { endpoint.getAll() }
        verify { service wasNot Called }
    }
    @Test fun `anonymous and ordinary updates strip config regardless of emission thread auth`() {
        val source = Flux.just(listOf(secretUpdate())).publishOn(Schedulers.boundedElastic())
        StepVerifier.create(updates(source)).assertNext(::assertSafe).expectComplete().verify(Duration.ofSeconds(3))
        login(admin = false)
        StepVerifier.create(updates(source)).assertNext(::assertSafe).expectComplete().verify(Duration.ofSeconds(3))
    }
    @Test fun `ordinary captured stream never upgrades when account later becomes administrator`() {
        login(admin = false)
        val stream = updates(Flux.just(listOf(secretUpdate())))
        current = PluginAccountSnapshot(1L, "fixture", null, true, true)
        StepVerifier.create(stream).assertNext(::assertSafe).expectComplete().verify(Duration.ofSeconds(3))
    }
    @Test fun `demotion before deferred attachment denies without attaching source`() {
        login()
        val attached = AtomicBoolean(false)
        val stream = updates(Flux.defer { attached.set(true); Flux.just(listOf(secretUpdate())) })
        current = PluginAccountSnapshot(1L, "fixture", null, true, false)
        StepVerifier.create(stream).expectError(AccessDeniedException::class.java).verify(Duration.ofSeconds(3))
        assertFalse(attached.get())
    }
    @Test fun `queued admin batch after demotion is not emitted and upstream is cancelled`() {
        login()
        val cancelled = AtomicBoolean(false)
        val source = Sinks.many().unicast().onBackpressureBuffer<List<PluginUpdateDto>>()
        val stream = updates(source.asFlux().publishOn(Schedulers.boundedElastic()).doOnCancel { cancelled.set(true) })
        StepVerifier.create(stream).then {
            current = PluginAccountSnapshot(1L, "fixture", null, true, false)
            source.tryEmitNext(listOf(secretUpdate(), secretUpdate())).orThrow()
        }.expectError(AccessDeniedException::class.java).verify(Duration.ofSeconds(3))
        assertTrue(cancelled.get())
    }
    @Test fun `disabled account rejects initial read before service and terminates captured stream`() {
        login()
        val stream = updates(Flux.just(listOf(secretUpdate())))
        current = PluginAccountSnapshot(1L, "fixture", null, false, true)
        assertFailsWith<AccessDeniedException> { endpoint.getAll() }
        StepVerifier.create(stream).expectError(AccessDeniedException::class.java).verify(Duration.ofSeconds(3))
        verify { service wasNot Called }
    }
    @Test fun `logout replacement authentication and registry expiration each deny captured admin`() {
        login()
        val original = access.capture()
        val replacement = SecurityContextHolder.createEmptyContext()
        replacement.authentication = UsernamePasswordAuthenticationToken("different", null, emptyList())
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, replacement)
        assertFailsWith<AccessDeniedException> { access.requireCurrent(original) }
        login()
        val expired = access.capture()
        registry.getSessionInformation(session.id)!!.expireNow()
        assertFailsWith<AccessDeniedException> { access.requireCurrent(expired) }
        login()
        val loggedOut = access.capture()
        session.invalidate()
        assertFailsWith<AccessDeniedException> { access.requireCurrent(loggedOut) }
    }
    @Test fun `public access disabled after capture terminates anonymous stream`() {
        val stream = updates(Flux.just(listOf(secretUpdate())))
        publicAccess = false
        StepVerifier.create(stream).expectError(AccessDeniedException::class.java).verify(Duration.ofSeconds(3))
    }
    @Test fun `stale admin cannot invoke configuration validation or mutation`() {
        login()
        current = PluginAccountSnapshot(1L, "fixture", null, true, false)
        assertFailsWith<AccessDeniedException> { endpoint.validatePluginConfig("fixture") }
        assertFailsWith<AccessDeniedException> { endpoint.validateNewConfig("fixture", mapOf("x" to secret)) }
        assertFailsWith<AccessDeniedException> { endpoint.updateConfig("fixture", mapOf("x" to secret)) }
        assertFailsWith<AccessDeniedException> { endpoint.enablePlugin("fixture") }
        assertFailsWith<AccessDeniedException> { endpoint.disablePlugin("fixture") }
        assertFailsWith<AccessDeniedException> { endpoint.setPluginPriorities(mapOf("fixture" to 1)) }
        verify { service wasNot Called }
    }
}
