package org.gameyfin.app.core.plugins

import com.vaadin.hilla.EndpointInvoker
import com.vaadin.hilla.push.PushEndpoint
import com.vaadin.hilla.push.PushMessageHandler
import io.mockk.*
import org.atmosphere.cpr.AtmosphereRequestImpl
import org.atmosphere.cpr.AtmosphereResource
import org.atmosphere.cpr.AtmosphereResourceImpl
import org.atmosphere.cpr.AtmosphereServlet
import org.atmosphere.handler.AtmosphereHandlerAdapter
import org.atmosphere.interceptor.SuspendTrackerInterceptor
import org.gameyfin.app.config.ConfigService
import org.gameyfin.app.core.Role
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.web.servlet.ServletRegistrationBean
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
import reactor.core.publisher.Flux
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import kotlin.test.*

/** Actual Hilla message parser/PushEndpoint/PushMessageHandler and Atmosphere request/registration API.
 * Endpoint reflection dispatch and account repository are controlled collaborators. This is not a
 * browser/WebSocket handshake, a running servlet container, or real OIDC provider acceptance.
 */
class PluginPushSessionBridgeTest {
    private val registry = SessionRegistryImpl()
    private val accounts = mockk<PluginAccountPolicy>()
    private val access = PluginEndpointAccess(accounts, registry, mockk<ConfigService>())
    private val principal = User("fixture", "", listOf(SimpleGrantedAuthority(Role.Names.ADMIN)))
    private val auth = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
    private val session = MockHttpSession()

    init {
        val persisted = SecurityContextHolder.createEmptyContext().apply { authentication = auth }
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, persisted)
        registry.registerNewSession(session.id, principal)
        every { accounts.resolve(auth) } returns PluginAccountSnapshot(1L, "fixture", null, true, true)
        every { accounts.current(1L) } returns PluginAccountSnapshot(1L, "fixture", null, true, true)
    }
    private fun resource(body: String, output: MutableList<String> = mutableListOf(), sessionPresent: Boolean = true): AtmosphereResource {
        val servletRequest = MockHttpServletRequest().apply { if (sessionPresent) setSession(this@PluginPushSessionBridgeTest.session); userPrincipal = auth }
        val builder = AtmosphereRequestImpl.Builder().request(servletRequest).userPrincipal(auth)
        if (sessionPresent) builder.session(session)
        val request = builder.method("POST").contentType("application/json").body(body).build()
        // Hilla's installed IOUtils requires the concrete resource contract.
        val resource = mockk<AtmosphereResourceImpl>(relaxed = true)
        every { resource.request } returns request
        every { resource.uuid() } returns "fixture-connection"
        every { resource.write(any<String>()) } answers { output.add(firstArg()); resource }
        return resource
    }
    private fun push(invoker: EndpointInvoker): Pair<PushEndpoint, PushMessageHandler> {
        val handler = PushMessageHandler(invoker)
        // Hilla's package-private constructor is opened only in this dependency regression test.
        val constructor = PushEndpoint::class.java.getDeclaredConstructor(ObjectMapper::class.java, PushMessageHandler::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(JsonMapper.builder().build(), handler) to handler
    }
    private val subscribe = """{"@type":"subscribe","id":"fixture-subscription","endpointName":"PluginEndpoint","methodName":"subscribe","params":[]}"""

    @AfterEach fun clear() {
        SecurityContextHolder.clearContext()
        RequestContextHolder.resetRequestAttributes()
        assertNull(PluginPushSessionBridge.currentRequest())
        unmockkAll()
    }

    @Test fun `actual Hilla message invocation captures persisted admin session without Spring servlet attributes`() {
        val invoker = mockk<EndpointInvoker>()
        every { invoker.getReturnType("PluginEndpoint", "subscribe") } returns Flux::class.java
        every { invoker.invoke("PluginEndpoint", "subscribe", any(), any(), any()) } answers {
            assertNull(RequestContextHolder.getRequestAttributes())
            assertTrue(access.captureAdministrator().administrator)
            Flux.just("authorized-config-channel")
        }
        val (push, messages) = push(invoker)
        messages.handleBrowserConnect("fixture-connection")
        val output = mutableListOf<String>()
        // Same public framework registration sequence used by installed PushConfigurer, before servlet init.
        val servlet = AtmosphereServlet()
        servlet.framework().addAtmosphereHandler("/HILLA/push", push, listOf(SuspendTrackerInterceptor()))
        val registration = ServletRegistrationBean(servlet, "/HILLA/push")
        PluginPushSessionBridge().postProcessAfterInitialization(registration, "atmosphereServlet")
        servlet.framework().atmosphereHandlers.getValue("/HILLA/push").atmosphereHandler.onRequest(resource(subscribe, output))
        assertTrue(output.any { it.contains("authorized-config-channel") })
        assertNull(SecurityContextHolder.getContext().authentication)
        assertNull(PluginPushSessionBridge.currentRequest())
        messages.handleBrowserDisconnect("fixture-connection")
    }

    @Test fun `unwrapped actual Hilla invocation remains fail closed instead of borrowing worker authentication`() {
        val invoker = mockk<EndpointInvoker>()
        every { invoker.getReturnType("PluginEndpoint", "subscribe") } returns Flux::class.java
        every { invoker.invoke("PluginEndpoint", "subscribe", any(), any(), any()) } answers {
            access.captureAdministrator()
            Flux.just("must-not-emit")
        }
        val (push, messages) = push(invoker)
        messages.handleBrowserConnect("fixture-connection")
        val output = mutableListOf<String>()
        assertFailsWith<AccessDeniedException> { push.onRequest(resource(subscribe, output)) }
        assertTrue(output.isEmpty())
        assertNull(SecurityContextHolder.getContext().authentication)
        messages.handleBrowserDisconnect("fixture-connection")
    }

    @Test fun `handler binding clears on exception and never matches a different worker principal`() {
        val delegate = object : AtmosphereHandlerAdapter() {
            override fun onRequest(resource: AtmosphereResource) {
                val other = UsernamePasswordAuthenticationToken("different", null, principal.authorities)
                SecurityContextHolder.getContext().authentication = other
                access.captureAdministrator()
            }
        }
        assertFailsWith<AccessDeniedException> { PluginPushSessionBridge.SessionBoundPushHandler(delegate).onRequest(resource("{}")) }
        assertNull(PluginPushSessionBridge.currentRequest())
    }

    @Test fun `bridged real Hilla invocation without persisted session remains denied`() {
        val invoker = mockk<EndpointInvoker>()
        every { invoker.getReturnType("PluginEndpoint", "subscribe") } returns Flux::class.java
        every { invoker.invoke("PluginEndpoint", "subscribe", any(), any(), any()) } answers {
            access.captureAdministrator()
            Flux.just("must-not-emit")
        }
        val (push, messages) = push(invoker)
        messages.handleBrowserConnect("fixture-connection")
        val output = mutableListOf<String>()
        assertFailsWith<AccessDeniedException> { PluginPushSessionBridge.SessionBoundPushHandler(push).onRequest(resource(subscribe, output, sessionPresent = false)) }
        assertTrue(output.isEmpty())
        assertNull(PluginPushSessionBridge.currentRequest())
        messages.handleBrowserDisconnect("fixture-connection")
    }

    @Test fun `registration decorates only Hilla handler preserving mapping and interceptor identity`() {
        val (push, _) = push(mockk())
        val servlet = AtmosphereServlet()
        val interceptor = SuspendTrackerInterceptor()
        servlet.framework().addAtmosphereHandler("/HILLA/push", push, listOf(interceptor))
        val original = servlet.framework().atmosphereHandlers.getValue("/HILLA/push")
        val registration = ServletRegistrationBean(servlet, "/HILLA/push")
        val bridge = PluginPushSessionBridge()
        assertSame(registration, bridge.postProcessAfterInitialization(registration, "atmosphereServlet"))
        val installed = servlet.framework().atmosphereHandlers.getValue("/HILLA/push")
        assertIs<PluginPushSessionBridge.SessionBoundPushHandler>(installed.atmosphereHandler)
        assertEquals(original.mapping, installed.mapping)
        assertSame(original.broadcaster, installed.broadcaster)
        assertTrue(installed.interceptors.any { it === interceptor })
        bridge.postProcessAfterInitialization(registration, "atmosphereServlet")
        assertSame(installed, servlet.framework().atmosphereHandlers.getValue("/HILLA/push"))
    }
}
