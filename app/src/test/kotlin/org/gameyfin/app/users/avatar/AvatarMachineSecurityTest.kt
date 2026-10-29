package org.gameyfin.app.users.avatar

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.core.env.MapPropertySource
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.web.FilterChainProxy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.UUID
import kotlin.test.*

/** Real Spring security chain + servlet, synthetic denied native adapter. No OIDC login proof. */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [AvatarMachineSecurityTest.Harness::class])
@WebAppConfiguration
class AvatarMachineSecurityTest {
    @Autowired private lateinit var context: WebApplicationContext
    private lateinit var mvc: MockMvc
    @BeforeEach fun setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters<DefaultMockMvcBuilder>(context.getBean(FilterChainProxy::class.java)).build()
    }
    @Test fun `machine API remains absent by default without operator or security beans`() {
        for (machineOnly in listOf(false, true)) AnnotationConfigApplicationContext().use { disabled ->
            if (machineOnly) disabled.environment.propertySources.addFirst(MapPropertySource("owned-machine-only",
                mapOf("gameyfin.avatar.machine.enabled" to "true")))
            disabled.register(AvatarMachineHttpConfiguration::class.java); disabled.refresh()
            assertFalse(disabled.containsBean("avatarMachineServlet"))
            assertFalse(disabled.containsBean("avatarMachineSecurity"))
            assertTrue(disabled.getBeansOfType(AvatarMachineOperations::class.java).isEmpty())
        }
    }
    @Test fun `admin session and CSRF do not replace native bearer and no login redirect occurs`() {
        val session = MockHttpSession().apply { setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextImpl(
            UsernamePasswordAuthenticationToken("synthetic-admin", "irrelevant", listOf(SimpleGrantedAuthority("ROLE_ADMIN"))))) }
        val result = mvc.perform(get("/internal/avatar/v1/current").session(session).with(csrf())
            .header(AvatarMachineProtocol.HEADER, "{\"scope\":{}}")
            .with { it.setAsyncSupported(true); it.setContent(byteArrayOf()); it }).andReturn()
        assertEquals(400, result.response.status)
        assertNull(result.response.getHeader("Location")); assertNull(result.response.getHeader("Set-Cookie"))
        assertEquals("{\"status\":\"REFUSED\"}", result.response.contentAsString)
    }
    @Test fun `syntactic bearer reaches only native denial without browser authentication or CSRF`() {
        val scope = mapOf("installation" to UUID.randomUUID().toString(), "restore" to UUID.randomUUID().toString(),
            "configuration" to 1, "imageDigest" to "sha256:" + "b".repeat(64), "buildChecksum" to "c".repeat(64),
            "grant" to UUID.randomUUID().toString(), "grantGeneration" to 1, "bindingDigest" to "d".repeat(64),
            "bindingGeneration" to 1, "userId" to 1)
        val result = mvc.perform(get("/internal/avatar/v1/current").header("Authorization", "Bearer " + "a".repeat(43))
            .header(AvatarMachineProtocol.HEADER, String(AvatarMachineProtocol.json(mapOf("scope" to scope))))
            .with { it.setAsyncSupported(true); it.setContent(byteArrayOf()); it }).andReturn()
        assertEquals(403, result.response.status)
        assertNull(result.response.getHeader("Location")); assertNull(result.response.getHeader("Set-Cookie"))
    }
    @Test fun `ordinary protected route retains authentication and CSRF outside exact namespace`() {
        assertEquals(403, mvc.perform(get("/internal/avatar/v10/current")).andReturn().response.status)
        assertEquals(403, mvc.perform(post("/ordinary").with(user("synthetic").roles("ADMIN"))).andReturn().response.status)
        assertEquals(200, mvc.perform(post("/ordinary").with(user("synthetic").roles("ADMIN")).with(csrf())).andReturn().response.status)
    }
    @Test fun `CSRF filter remains and exception admits only exact nonbrowser bearer mutations`() {
        assertTrue(requireNotNull(context.getBean(FilterChainProxy::class.java)
            .getFilters("/internal/avatar/v1/avatar")).any { it is CsrfFilter })
        for (mutation in listOf(put("/internal/avatar/v1/avatar"), delete("/internal/avatar/v1/avatar"))) {
            val refused = mvc.perform(mutation).andReturn()
            assertEquals(403, refused.response.status)
            assertNull(refused.request.getSession(false)); assertNull(refused.response.getHeader("Set-Cookie"))
        }
        val bearer = "Bearer " + "a".repeat(43)
        for ((header, value) in listOf("Origin" to "https://synthetic.invalid", "Sec-Fetch-Site" to "same-origin",
            "Cookie" to "synthetic-only=value")) {
            val refused = mvc.perform(put("/internal/avatar/v1/avatar").header("Authorization", bearer)
                .header(header, value)).andReturn()
            assertEquals(403, refused.response.status)
            assertNull(refused.request.getSession(false)); assertNull(refused.response.getHeader("Set-Cookie"))
        }
        assertEquals(403, mvc.perform(post("/internal/avatar/v1/avatar").header("Authorization", bearer))
            .andReturn().response.status)
        assertEquals(403, mvc.perform(put("/internal/avatar/v1/current").header("Authorization", bearer))
            .andReturn().response.status)
        // Exemption is NOT authorization: malformed native pins still refuse.
        assertEquals(400, mvc.perform(delete("/internal/avatar/v1/avatar").header("Authorization", bearer)
            .header(AvatarMachineProtocol.HEADER, "{\"scope\":{}}")
            .with { it.setAsyncSupported(true); it.setContent(byteArrayOf()); it })
            .andReturn().response.status)
    }
    @Configuration @EnableWebSecurity @EnableWebMvc
    class Harness {
        @Bean @Order(0) fun machineChain(http: HttpSecurity) = AvatarMachineHttpConfiguration().avatarMachineSecurity(http)
        @Bean @Order(10) fun ordinaryChain(http: HttpSecurity): SecurityFilterChain {
            http.authorizeHttpRequests { it.anyRequest().hasRole("ADMIN") }
                .exceptionHandling { it.authenticationEntryPoint { _, response, _ -> response.status = 403 } }
            return http.build()
        }
        @Bean fun probe() = Probe()
    }
    @RestController
    class Probe {
        private val servlet = AvatarMachineServlet(object : AvatarMachineOperations {
            override fun preflight(secret: String, scope: AvatarLedger.Scope, requestFence: () -> Unit): Unit = throw AvatarAuthority.Denied()
            override fun current(secret: String, scope: AvatarLedger.Scope, requestFence: () -> Unit): AvatarLedger.Current = throw AvatarAuthority.Denied()
            override fun apply(secret: String, intent: AvatarLedger.Intent, png: CanonicalAvatarPng?, requestFence: () -> Unit): AvatarLedger.Receipt = throw AvatarAuthority.Denied()
            override fun originalReceipt(secret: String, installation: UUID, operation: UUID, intentDigest: String, restore: UUID,
                                         requestFence: () -> Unit): AvatarLedger.Receipt? = throw AvatarAuthority.Denied()
        })
        @RequestMapping("/internal/avatar/v1/{part}")
        fun machine(request: HttpServletRequest, response: HttpServletResponse) {
            servlet.service(object : HttpServletRequestWrapper(request) {
                override fun getPathInfo() = request.requestURI.removePrefix("/internal/avatar/v1")
            }, response)
        }
        @RequestMapping("/ordinary") fun ordinary() = "ordinary"
    }
}
