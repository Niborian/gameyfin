package org.gameyfin.app.core.security

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PushDocumentNavigationFilterTest {
    private val filter = PushDocumentNavigationFilter()

    @Test
    fun `document return lands locally without retaining transport query or session creation`() {
        val request = MockHttpServletRequest("GET", "/HILLA/push").apply {
            addHeader("Sec-Fetch-Dest", "document")
            queryString = "transport=websocket&continue=https://invalid.example&trackingId=synthetic"
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        assertEquals("/", response.redirectedUrl)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertNull(request.getSession(false))
        assertNull(chain.request)
    }

    @Test
    fun `context path is preserved but transport query is discarded`() {
        val request = MockHttpServletRequest("GET", "/gameyfin/HILLA/push").apply {
            contextPath = "/gameyfin"
            addHeader("Sec-Fetch-Dest", "document")
        }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())
        assertEquals("/gameyfin/", response.redirectedUrl)
    }

    @Test
    fun `socket polling rpc and ordinary document requests retain original chain`() {
        for ((method, path, destination, upgrade) in listOf(
            listOf("GET", "/HILLA/push", "", "websocket"),
            listOf("GET", "/HILLA/push", "document", "websocket"),
            listOf("GET", "/HILLA/push", "empty", ""),
            listOf("GET", "/HILLA/push", "iframe", ""),
            listOf("POST", "/HILLA/push", "document", ""),
            listOf("GET", "/game/1", "document", "")
        )) {
            val request = MockHttpServletRequest(method, path).apply {
                addHeader("Sec-Fetch-Dest", destination)
                addHeader("Upgrade", upgrade)
            }
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()
            filter.doFilter(request, response, chain)
            assertSame(request, chain.request)
            assertNull(response.redirectedUrl)
        }
    }
}
