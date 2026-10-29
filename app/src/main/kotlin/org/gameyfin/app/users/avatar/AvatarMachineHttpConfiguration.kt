package org.gameyfin.app.users.avatar

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRepository
import org.springframework.security.web.csrf.DefaultCsrfToken
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID

/** Off by default. Enabling also requires an explicitly supplied trusted native
 * operations bean; this configuration does NOT manufacture grants, inventory,
 * operator files or a bootstrap channel. Existing browser/OIDC chain is unchanged.
 * Private/TLS connector/exposure and native acceptance gates precede enablement.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gameyfin.avatar", name = ["machine.enabled", "overlay.enabled"], havingValue = "true")
class AvatarMachineHttpConfiguration {
    @Bean
    fun avatarMachineServlet(operations: AvatarMachineOperations): ServletRegistrationBean<AvatarMachineServlet> =
        ServletRegistrationBean(AvatarMachineServlet(operations), "/internal/avatar/v1/*").apply {
            setName("gameyfinAvatarMachine")
            setAsyncSupported(true)
        }

    @Bean
    @Order(0)
    fun avatarMachineSecurity(http: HttpSecurity): SecurityFilterChain {
        http.securityMatcher("/internal/avatar/v1", "/internal/avatar/v1/**")
            // Dedicated verifier-only native servlet is the authorization boundary.
            // No ordinary authentication method, session context or CSRF cookie.
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .securityContext { it.disable() }
            .requestCache { it.disable() }
            // Keep the CSRF filter. Only an exact non-browser bearer mutation is
            // exempt; verifier/scope/commit checks still belong to the servlet.
            .csrf { it.csrfTokenRepository(MachineOnlyCsrfTokens).ignoringRequestMatchers(RequestMatcher { request ->
                request.method in setOf("PUT", "DELETE") &&
                    request.requestURI == request.contextPath + "/internal/avatar/v1/avatar" &&
                    request.getHeader("Origin") == null && request.getHeader("Sec-Fetch-Site") == null &&
                    request.getHeader("Cookie") == null &&
                    request.getHeaders("Authorization").toList().let { headers ->
                        headers.size == 1 && headers.single().matches(Regex("Bearer [A-Za-z0-9_-]{43}"))
                    }
            }) }
            .cors { it.disable() }
            .anonymous { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .oauth2Login { it.disable() }
        return http.build()
    }

    /** Rejected browser requests must not create a session/cookie via the CSRF
     * filter. There is no browser token-issuance endpoint in this machine chain.
     * A non-exempt unsafe request cannot present this fresh per-request token.
     * Ordinary browser/OIDC chains keep their existing repositories unchanged. */
    private object MachineOnlyCsrfTokens : CsrfTokenRepository {
        override fun generateToken(request: HttpServletRequest): CsrfToken =
            DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", UUID.randomUUID().toString())
        override fun loadToken(request: HttpServletRequest): CsrfToken? = null
        override fun saveToken(token: CsrfToken?, request: HttpServletRequest, response: HttpServletResponse) = Unit
    }
}
