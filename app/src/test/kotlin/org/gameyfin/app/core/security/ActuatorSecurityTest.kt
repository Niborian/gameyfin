package org.gameyfin.app.core.security

import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.web.FilterChainProxy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.web.servlet.config.annotation.EnableWebMvc

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ActuatorSecurityTest.TestConfiguration::class])
@WebAppConfiguration
class ActuatorSecurityTest {
    @Autowired private lateinit var context: WebApplicationContext
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy::class.java))
            .build()
    }

    @Test
    fun `readiness remains available without a session`() {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk)
    }

    @Test
    fun `restart requires an administrator and a CSRF token`() {
        mvc.perform(post("/actuator/restart").with(user("admin").roles("ADMIN")))
            .andExpect(status().isForbidden)
        mvc.perform(post("/actuator/restart").with(user("member").roles("USER")).with(csrf()))
            .andExpect(status().isForbidden)
        mvc.perform(post("/actuator/restart").with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isOk)
    }

    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    class TestConfiguration {
        @Bean
        fun actuatorChain(http: HttpSecurity): SecurityFilterChain = SecurityConfig(
            mockk(), mockk(), mockk(), mockk()
        ).actuatorFilterChain(http)

        @Bean
        fun testActuator() = TestActuator()
    }

    @RestController
    class TestActuator {
        @GetMapping("/actuator/health/readiness", produces = [MediaType.TEXT_PLAIN_VALUE])
        fun health() = "ready"

        @PostMapping("/actuator/restart", produces = [MediaType.TEXT_PLAIN_VALUE])
        fun restart() = "restarted"
    }
}
