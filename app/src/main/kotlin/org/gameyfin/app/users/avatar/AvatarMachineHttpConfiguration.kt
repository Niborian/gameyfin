package org.gameyfin.app.users.avatar

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

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
            .csrf { it.disable() }
            .cors { it.disable() }
            .anonymous { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .oauth2Login { it.disable() }
        return http.build()
    }
}
