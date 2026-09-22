package org.gameyfin.app.core.security

import io.mockk.every
import io.mockk.mockk
import org.gameyfin.app.config.ConfigProperties
import org.gameyfin.app.config.ConfigService
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals

class LoginRedirectControllerTest {
    private val config = mockk<ConfigService>()
    private val controller = LoginRedirectController(config)

    @Test
    fun `SSO receives only a validated local continuation`() {
        every { config.get(ConfigProperties.SSO.OIDC.Enabled) } returns true
        val request = MockHttpServletRequest().apply {
            addParameter("continue", "//evil.example/games")
        }
        val response = MockHttpServletResponse()

        controller.loginRedirect(request, response)

        assertEquals("/oauth2/authorization/oidc?continue=%2F", response.redirectedUrl)
    }

    @Test
    fun `direct login preserves a local path as one encoded parameter`() {
        every { config.get(ConfigProperties.SSO.OIDC.Enabled) } returns true
        val request = MockHttpServletRequest().apply {
            addParameter("direct", "1")
            addParameter("continue", "/games?sort=new&filter=owned")
        }
        val response = MockHttpServletResponse()

        controller.loginRedirect(request, response)

        assertEquals("/login?continue=%2Fgames%3Fsort%3Dnew%26filter%3Downed", response.redirectedUrl)
    }
}
