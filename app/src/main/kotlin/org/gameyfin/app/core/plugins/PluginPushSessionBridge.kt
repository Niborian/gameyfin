package org.gameyfin.app.core.plugins

import com.vaadin.hilla.push.PushEndpoint
import jakarta.servlet.http.HttpServletRequest
import org.atmosphere.cpr.AtmosphereHandler
import org.atmosphere.cpr.AtmosphereResource
import org.atmosphere.cpr.AtmosphereResourceEvent
import org.atmosphere.cpr.AtmosphereServlet
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.stereotype.Component

/** Hilla's socket-message dispatch binds authentication but does not enter Spring's servlet filters.
 * Decorate its actual handler through Atmosphere's public registration API, retaining the existing
 * mapping, broadcaster and interceptors. Never infer a session from a principal, registry search,
 * URL, cookie value or later Reactor thread. The request exists only during the real handler call.
 */
@Component
class PluginPushSessionBridge : BeanPostProcessor {
    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        val registration = bean as? ServletRegistrationBean<*> ?: return bean
        val servlet = registration.servlet as? AtmosphereServlet ?: return bean
        val framework = servlet.framework()
        for ((mapping, wrapper) in framework.atmosphereHandlers.toMap()) {
            val delegate = wrapper.atmosphereHandler
            if (delegate !is PushEndpoint) continue
            val bound = SessionBoundPushHandler(delegate)
            val interceptors = wrapper.interceptors.toList()
            val broadcaster = wrapper.broadcaster
            if (broadcaster == null) framework.addAtmosphereHandler(mapping, bound, interceptors)
            else framework.addAtmosphereHandler(mapping, bound, broadcaster, interceptors)
        }
        return bean
    }

    internal class SessionBoundPushHandler(private val delegate: AtmosphereHandler) : AtmosphereHandler {
        override fun onRequest(resource: AtmosphereResource) {
            val previous = request.get()
            request.set(resource.request)
            try { delegate.onRequest(resource) }
            finally { if (previous == null) request.remove() else request.set(previous) }
        }
        override fun onStateChange(event: AtmosphereResourceEvent) = delegate.onStateChange(event)
        override fun destroy() = delegate.destroy()
    }

    companion object {
        private val request = ThreadLocal<HttpServletRequest>()
        internal fun currentRequest(): HttpServletRequest? = request.get()
    }
}
