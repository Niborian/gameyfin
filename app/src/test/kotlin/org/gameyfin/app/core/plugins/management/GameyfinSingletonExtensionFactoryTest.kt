package org.gameyfin.app.core.plugins.management

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.pf4j.PluginManager
import org.pf4j.PluginState
import org.pf4j.PluginStateEvent
import org.pf4j.PluginStateListener
import org.pf4j.PluginWrapper
import org.pf4j.SingletonExtensionFactory
import org.pf4j.DefaultPluginManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class GameyfinSingletonExtensionFactoryTest {
    class Extension
    class OtherExtension
    class EventManager : DefaultPluginManager() {
        fun publish(event: PluginStateEvent) = firePluginStateEvent(event)
    }
    class BlockingExtension {
        init {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        companion object {
            var entered = CountDownLatch(1)
            var release = CountDownLatch(1)
        }
    }
    class ManagerCallingExtension {
        init {
            entered.countDown()
            check(callManager.await(5, TimeUnit.SECONDS))
            synchronized(managerMonitor) { }
        }
        companion object {
            var entered = CountDownLatch(1)
            var callManager = CountDownLatch(1)
            var managerMonitor: Any = Any()
        }
    }

    private fun event(manager: PluginManager, state: PluginState): PluginStateEvent {
        val wrapper = mockk<PluginWrapper>()
        every { wrapper.pluginState } returns state
        every { wrapper.pluginClassLoader } returns Extension::class.java.classLoader
        return PluginStateEvent(manager, wrapper, PluginState.STARTED)
    }

    @Test
    fun `PF4J check then get can observe lifecycle eviction and throw`() {
        val manager = mockk<PluginManager>()
        lateinit var listener: PluginStateListener
        every { manager.addPluginStateListener(any()) } answers { listener = firstArg() }
        val factory = SingletonExtensionFactory(manager)
        factory.create(Extension::class.java)
        val cacheField = SingletonExtensionFactory::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val original = cacheField.get(factory) as Map<ClassLoader, MutableMap<String, Any>>
        val interleaved = object : HashMap<ClassLoader, MutableMap<String, Any>>(original) {
            override fun containsKey(key: ClassLoader): Boolean {
                val present = super.containsKey(key)
                listener.pluginStateChanged(event(manager, PluginState.STOPPED))
                return present
            }
        }
        cacheField.set(factory, interleaved)
        assertFailsWith<NullPointerException> { factory.create(Extension::class.java) }
    }

    @Test
    fun `started preserves singleton while stopped and disabled evict it`() {
        val manager = mockk<PluginManager>()
        lateinit var listener: PluginStateListener
        every { manager.addPluginStateListener(any()) } answers { listener = firstArg() }
        val factory = GameyfinSingletonExtensionFactory(manager)
        val first = factory.create(Extension::class.java)
        assertSame(first, factory.create(Extension::class.java))
        val other = factory.create(OtherExtension::class.java)
        assertSame(other, factory.create(OtherExtension::class.java))
        assertNotSame<Any>(first, other)
        listener.pluginStateChanged(event(manager, PluginState.STARTED))
        assertSame(first, factory.create(Extension::class.java))
        val unrelated = mockk<PluginWrapper>()
        every { unrelated.pluginState } returns PluginState.UNLOADED
        every { unrelated.pluginClassLoader } returns object : ClassLoader() {}
        listener.pluginStateChanged(PluginStateEvent(manager, unrelated, PluginState.STOPPED))
        assertSame(first, factory.create(Extension::class.java))
        listener.pluginStateChanged(event(manager, PluginState.STOPPED))
        val second = factory.create(Extension::class.java)
        assertNotSame(first, second)
        listener.pluginStateChanged(event(manager, PluginState.DISABLED))
        assertNotSame(second, factory.create(Extension::class.java))
    }

    @Test
    fun `concurrent discoveries share one singleton`() {
        val manager = mockk<PluginManager>(relaxed = true)
        val factory = GameyfinSingletonExtensionFactory(manager)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val requests = (1..40).map { pool.submit<Extension> { factory.create(Extension::class.java) } }
            val expected = requests.first().get(5, TimeUnit.SECONDS)
            requests.forEach { assertSame(expected, it.get(5, TimeUnit.SECONDS)) }
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `lifecycle eviction detaches in flight cache without waiting and next lookup is fresh`() {
        val manager = mockk<PluginManager>()
        lateinit var listener: PluginStateListener
        every { manager.addPluginStateListener(any()) } answers { listener = firstArg() }
        val factory = GameyfinSingletonExtensionFactory(manager)
        BlockingExtension.entered = CountDownLatch(1)
        BlockingExtension.release = CountDownLatch(1)
        val invalidating = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2) { task -> Thread(task).apply { isDaemon = true } }
        try {
            val lookup = pool.submit<BlockingExtension> { factory.create(BlockingExtension::class.java) }
            assertTrue(BlockingExtension.entered.await(5, TimeUnit.SECONDS))
            val change = pool.submit {
                invalidating.countDown()
                listener.pluginStateChanged(event(manager, PluginState.STOPPED))
            }
            assertTrue(invalidating.await(5, TimeUnit.SECONDS))
            change.get(5, TimeUnit.SECONDS)
            BlockingExtension.release.countDown()
            val first = lookup.get(5, TimeUnit.SECONDS)
            change.get(5, TimeUnit.SECONDS)
            assertNotSame(first, factory.create(BlockingExtension::class.java))
        } finally {
            BlockingExtension.release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `manager locked lifecycle event does not deadlock constructor calling manager`() {
        // Exercise PF4J's real synchronized event dispatcher, not a mocked callback.
        val manager = EventManager()
        val factory = GameyfinSingletonExtensionFactory(manager)
        ManagerCallingExtension.entered = CountDownLatch(1)
        ManagerCallingExtension.callManager = CountDownLatch(1)
        ManagerCallingExtension.managerMonitor = manager
        // A regressed monitor deadlock must fail the bounded test, not pin the test JVM.
        val pool = Executors.newFixedThreadPool(2) { task -> Thread(task).apply { isDaemon = true } }
        try {
            val lookup = pool.submit<ManagerCallingExtension> { factory.create(ManagerCallingExtension::class.java) }
            assertTrue(ManagerCallingExtension.entered.await(5, TimeUnit.SECONDS))
            val change = pool.submit {
                synchronized(manager) {
                    ManagerCallingExtension.callManager.countDown()
                    manager.publish(event(manager, PluginState.STOPPED))
                }
            }
            change.get(5, TimeUnit.SECONDS)
            val old = lookup.get(5, TimeUnit.SECONDS)
            assertNotSame(old, factory.create(ManagerCallingExtension::class.java))
        } finally {
            ManagerCallingExtension.callManager.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
