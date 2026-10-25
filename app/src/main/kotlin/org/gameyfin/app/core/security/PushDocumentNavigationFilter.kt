package org.gameyfin.app.core.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** A reverse-proxy login can return the browser to its interrupted Hilla request.
 * A push URL is a transport endpoint, not an application landing page. Only recover
 * explicit document navigation; never redirect socket, polling or RPC traffic.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class PushDocumentNavigationFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val path = request.requestURI.removePrefix(request.contextPath)
        if (request.method == "GET" && path == "/HILLA/push" &&
            request.getHeader("Sec-Fetch-Dest") == "document" &&
            !request.getHeader("Upgrade").equals("websocket", ignoreCase = true)) {
            response.setHeader("Cache-Control", "no-store")
            // Discard transport identifiers and arbitrary continuation parameters.
            // The existing application security chain still governs the landing page.
            response.sendRedirect("${request.contextPath}/")
            return
        }
        chain.doFilter(request, response)
    }
}
