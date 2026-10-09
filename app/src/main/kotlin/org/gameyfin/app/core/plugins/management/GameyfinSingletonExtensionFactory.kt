package org.gameyfin.app.core.plugins.management

import org.pf4j.DefaultExtensionFactory
import org.pf4j.PluginManager
import org.pf4j.PluginStateEvent

/** Singleton creation and lifecycle invalidation share a monitor, unlike PF4J's
 * SingletonExtensionFactory, whose containsKey/get/remove sequence can race.
 * Only discovery is serialized; calls to provider methods are not locked.
 */
class GameyfinSingletonExtensionFactory(pluginManager: PluginManager) : DefaultExtensionFactory() {
    private val instances = mutableMapOf<Class<*>, Any>()

    init {
        pluginManager.addPluginStateListener(::invalidate)
    }

    @Synchronized
    override fun <T : Any?> create(extensionClass: Class<T>): T {
        val instance = instances[extensionClass] ?: super.create(extensionClass).also {
            instances[extensionClass] = requireNotNull(it)
        }
        return extensionClass.cast(instance)
    }

    @Synchronized
    private fun invalidate(event: PluginStateEvent) {
        if (!event.pluginState.isStarted) {
            val loader = event.plugin.pluginClassLoader
            instances.keys.removeIf { it.classLoader === loader }
        }
    }
}
