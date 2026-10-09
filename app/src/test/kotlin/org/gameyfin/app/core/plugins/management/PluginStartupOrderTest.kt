package org.gameyfin.app.core.plugins.management

import io.mockk.*
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.libraries.LibraryRepository
import org.gameyfin.app.platforms.PlatformService
import org.gameyfin.pluginapi.gamemetadata.GameMetadataProvider
import org.junit.jupiter.api.Test
import org.pf4j.PluginStateEvent
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import java.time.Duration
import kotlin.test.assertEquals

class PluginStartupOrderTest {
    @Configuration
    @EnableAsync
    open class AsyncConfiguration

    @Test
    fun `ready event completes loading before platform registry enumeration`() {
        val manager = mockk<GameyfinPluginManager>(relaxed = true)
        val indicator = mockk<PluginsLoadedIndicator>(relaxed = true)
        val games = mockk<GameRepository>(relaxed = true)
        val libraries = mockk<LibraryRepository>(relaxed = true)
        val platforms = PlatformService(games, libraries, manager)
        val calls = mutableListOf<String>()
        every { manager.loadPlugins() } answers {
            calls.add("load")
            // A callback arriving while PF4J is mutating must not enumerate it.
            platforms.onPluginStateChange(mockk<PluginStateEvent>())
            verify(exactly = 0) { manager.getExtensions(GameMetadataProvider::class.java) }
        }
        every { manager.startPlugins() } answers { calls.add("start") }
        every { manager.plugins } returns emptyList()
        every { manager.getExtensions(GameMetadataProvider::class.java) } answers {
            assertEquals(listOf("load", "start"), calls.take(2))
            calls.add("enumerate")
            emptyList()
        }
        AnnotationConfigApplicationContext().use { context ->
            context.register(AsyncConfiguration::class.java)
            // Register the dependent listener first, deliberately reversing registration order.
            context.registerBean("platforms", PlatformService::class.java, java.util.function.Supplier { platforms })
            context.registerBean("loader", PluginManagerConfig::class.java,
                java.util.function.Supplier { PluginManagerConfig(manager, indicator) })
            context.refresh()
            context.publishEvent(ApplicationReadyEvent(SpringApplication(), emptyArray(), context, Duration.ZERO))
        }
        assertEquals(listOf("load", "start", "enumerate"), calls)
        verify(exactly = 1) { indicator.markReady() }
    }
}
