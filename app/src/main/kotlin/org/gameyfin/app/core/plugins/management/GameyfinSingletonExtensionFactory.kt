package org.gameyfin.app.core.plugins.management

import org.pf4j.DefaultExtensionFactory
import org.pf4j.PluginManager
import org.pf4j.PluginStateEvent
import java.util.concurrent.ConcurrentHashMap

/** A lookup captures one stable loader cache, avoiding PF4J's check/get race.
 * Lifecycle invalidation detaches that cache without waiting for constructors:
 * PF4J invokes listeners while holding its manager monitor, so waiting here
 * could deadlock a constructor that calls back into the manager.
 * An already-running lookup may finish with the old instance; subsequent
 * lookups use a fresh cache. Provider method calls are never locked.
 */
class GameyfinSingletonExtensionFactory(pluginManager: PluginManager) : DefaultExtensionFactory() {
    private class LoaderCache {
        val instances = mutableMapOf<Class<*>, Any>()
    }
    private val bootstrapLoader = Any()
    private val caches = ConcurrentHashMap<Any, LoaderCache>()

    init {
        pluginManager.addPluginStateListener(::invalidate)
    }

    override fun <T : Any?> create(extensionClass: Class<T>): T {
        val cache = caches.computeIfAbsent(extensionClass.classLoader ?: bootstrapLoader) { LoaderCache() }
        synchronized(cache) {
            val instance = cache.instances[extensionClass] ?: super.create(extensionClass).also {
                cache.instances[extensionClass] = requireNotNull(it)
            }
            return extensionClass.cast(instance)
        }
    }

    private fun invalidate(event: PluginStateEvent) {
        if (!event.pluginState.isStarted) {
            caches.remove(event.plugin.pluginClassLoader ?: bootstrapLoader)
        }
    }
}
