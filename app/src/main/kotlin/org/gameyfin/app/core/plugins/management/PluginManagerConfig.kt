package org.gameyfin.app.core.plugins.management

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

@Configuration
class PluginManagerConfig(
    private val pluginManager: GameyfinPluginManager,
    private val pluginsLoadedIndicator: PluginsLoadedIndicator
) {
    private val log = KotlinLogging.logger {}

    // PF4J's registry is not safe to enumerate while startup loading mutates it.
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent::class)
    fun loadPlugins() {
        pluginManager.loadPlugins()
        pluginManager.startPlugins()
        log.info { "Loaded plugins: ${pluginManager.plugins.map { it.pluginId }}" }
        pluginsLoadedIndicator.markReady()
    }
}
