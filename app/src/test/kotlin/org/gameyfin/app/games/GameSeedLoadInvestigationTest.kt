package org.gameyfin.app.games

import jakarta.persistence.EntityManager
import org.gameyfin.app.games.entities.*
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.games.extensions.toAdminDto
import org.gameyfin.app.games.extensions.toUserDto
import org.gameyfin.app.libraries.extensions.toAdminDto as libraryAdminDto
import org.gameyfin.app.libraries.extensions.toUserDto as libraryUserDto
import org.hibernate.Hibernate
import org.gameyfin.app.libraries.entities.Library
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.test.annotation.DirtiesContext
import java.lang.management.ManagementFactory
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.mockk.every
import io.mockk.mockk
import org.gameyfin.app.core.plugins.management.GameyfinPluginManager
import org.gameyfin.app.core.plugins.management.PluginManagementEntry
import org.gameyfin.app.core.plugins.PluginService
import org.gameyfin.pluginapi.gamemetadata.GameMetadataProvider
import org.gameyfin.app.libraries.LibraryRepository
import org.gameyfin.app.core.plugins.dto.ExternalProviderIdDto
import org.gameyfin.pluginapi.gamemetadata.Platform
import jakarta.persistence.EntityNotFoundException
import kotlin.test.assertFailsWith

@DataJpaTest(properties = [
    "spring.jpa.show-sql=false",
    "spring.jpa.properties.hibernate.session_factory.statement_inspector=org.gameyfin.app.games.GameLoadStatementInspector"
])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GameSeedLoadInvestigationTest @Autowired constructor(
    private val entityManager: EntityManager,
    private val repository: GameRepository,
    private val transactions: PlatformTransactionManager,
    private val libraryRepository: LibraryRepository
) {
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `132 game seed equivalent identifier load with four bounded concurrent workers`() {
        val ids = TransactionTemplate(transactions).execute {
            val ids = mutableListOf<Long>()
            listOf(104, 27, 1, 0).forEachIndexed { libraryIndex, count ->
                val library = Library(name = "Seed library $libraryIndex")
                entityManager.persist(library)
                repeat(count) { index ->
                    val path = "/synthetic/lib$libraryIndex/game-$index"
                    val game = Game(library = library, metadata = GameMetadata(path = path))
                    game.title = "Synthetic game $index"
                    game.metadata.fileSize = 49152
                    game.metadata.matchConfirmed = true
                    val variant = GameVariant(game = game, path = "$path/Normal 1.0", version = "1.0",
                        fileSize = 49152, isDefault = true, isLatestForVariant = true, scanManaged = false, defaultLocked = true)
                    variant.contents.add(VariantContent(variant = variant, name = "Grouped base", path = "$path/base-a.bin",
                        fileSize = 32768, required = true, defaultSelected = true,
                        paths = mutableListOf("$path/base-a.bin", "$path/base-b.bin")))
                    variant.contents.add(VariantContent(variant = variant, name = "Optional patch", path = "$path/patch.bin",
                        fileSize = 16384, type = VariantContentType.PATCH, paths = mutableListOf("$path/patch.bin")))
                    game.variants.add(variant)
                    repository.save(game)
                    ids.add(game.id!!)
                }
            }
            entityManager.flush()
            entityManager.clear()
            ids
        }!!
        assertEquals(132, ids.size)
        // Diagnostic follow-up for #27: successful provider results access library.platforms.
        for (accessPlatforms in listOf(false, true)) {
            val stats = GameLoadStatementInspector.QueryStats()
            GameLoadStatementInspector.perThread.set(stats)
            try {
                TransactionTemplate(transactions).execute {
                    val loaded = repository.findById(ids.first()).orElseThrow()
                    val before = stats.count
                    if (accessPlatforms) loaded.library.platforms.size
                    println("PROVIDER_PATH_DIAGNOSTIC platforms=$accessPlatforms initialSelects=$before totalSelects=${stats.count} libraryInitialized=${Hibernate.isInitialized(loaded.library)}")
                }
            } finally { GameLoadStatementInspector.perThread.remove() }
        }
        val provider = mockk<GameMetadataProvider>()
        val manager = mockk<GameyfinPluginManager>()
        val plugins = mockk<PluginService>()
        val entry = TransactionTemplate(transactions).execute {
            val entry = PluginManagementEntry("fixture-provider", enabled = true, priority = 1)
            entityManager.persist(entry)
            repository.findById(ids.first()).orElseThrow().metadata.originalIds = mapOf(entry to "fixture-original")
            entityManager.flush()
            entry
        }!!
        every { manager.getExtensions("fixture-provider") } returns listOf(provider)
        every { manager.getExtensions(GameMetadataProvider::class.java) } returns listOf(provider)
        every { plugins.getPluginManagementEntry(provider.javaClass) } returns entry
        val service = GameService(repository, manager, plugins, mockk(), mockk(), mockk(), mockk(), mockk(), libraryRepository)
        for (positive in listOf(false, true)) {
            every { provider.fetchById("fixture-original") } returns if (positive)
                org.gameyfin.pluginapi.gamemetadata.GameMetadata(originalId = "fixture-original", title = "Synthetic game 0") else null
            val stats = GameLoadStatementInspector.QueryStats()
            GameLoadStatementInspector.perThread.set(stats)
            try {
                TransactionTemplate(transactions).execute {
                    val supplied = Game(id = ids.first(), library = Library(name = "Detached input"), metadata = GameMetadata(path = "/synthetic/input"))
                    service.updateMetadata(supplied)
                    val loaded = repository.findById(ids.first()).orElseThrow()
                    println("PROVIDER_REFRESH_DIAGNOSTIC positive=$positive selects=${stats.count} libraryInitialized=${Hibernate.isInitialized(loaded.library)}")
                    assertFalse(Hibernate.isInitialized(loaded.library))
                    assertTrue(stats.count < 50, "Provider refresh must not load the entire library")
                    assertEquals("fixture-original", loaded.metadata.originalIds.values.single())
                    assertEquals(1, loaded.variants.size)
                }
            } finally { GameLoadStatementInspector.perThread.remove() }
        }
        val providerPlatforms = setOf(Platform.PC_MICROSOFT_WINDOWS, Platform.LINUX)
        every { provider.fetchById("fixture-original") } returns
            org.gameyfin.pluginapi.gamemetadata.GameMetadata(originalId = "fixture-original", title = "Provider title", platforms = providerPlatforms)
        val originalIds = mapOf(provider.javaClass.name to ExternalProviderIdDto("fixture-provider", "fixture-original"))
        val existingLibraryId = TransactionTemplate(transactions).execute {
            repository.findById(ids.first()).orElseThrow().library.id!!
        }!!
        for (allowed in listOf(emptyList(), listOf(Platform.LINUX), providerPlatforms.toList())) {
            TransactionTemplate(transactions).execute {
                entityManager.find(Library::class.java, existingLibraryId).platforms = allowed.toMutableList()
            }
            TransactionTemplate(transactions).execute {
                val proxy = entityManager.getReference(Library::class.java, existingLibraryId)
                val matched = service.matchManually(originalIds, java.nio.file.Path.of("/synthetic/input"), proxy, persist = false)!!
                assertEquals(allowed.toSet(), matched.platforms.toSet())
                assertEquals("Provider title", matched.title)
                assertEquals("fixture-original", matched.metadata.originalIds.values.single())
                assertTrue(matched.library === proxy)
                assertFalse(Hibernate.isInitialized(proxy))
            }
        }
        TransactionTemplate(transactions).execute {
            val loaded = entityManager.find(Library::class.java, existingLibraryId)
            loaded.platforms = mutableListOf(Platform.LINUX)
            val matched = service.matchManually(originalIds, java.nio.file.Path.of("/synthetic/input"), loaded, persist = false)!!
            assertEquals(listOf(Platform.LINUX), matched.platforms)
        }
        val transient = Library(name = "Transient", platforms = mutableListOf(Platform.LINUX))
        assertEquals(listOf(Platform.LINUX), service.matchManually(originalIds, java.nio.file.Path.of("/synthetic/input"), transient, persist = false)!!.platforms)
        val refreshed = TransactionTemplate(transactions).execute {
            val stats = GameLoadStatementInspector.QueryStats()
            GameLoadStatementInspector.perThread.set(stats)
            try {
                val supplied = Game(id = ids.first(), library = transient, metadata = GameMetadata(path = "/synthetic/input"))
                val updated = service.updateMetadata(supplied)!!
                assertTrue(stats.count < 50)
                assertFalse(Hibernate.isInitialized(updated.library))
                updated
            } finally { GameLoadStatementInspector.perThread.remove() }
        }!!
        assertEquals("Provider title", refreshed.toAdminDto().title)
        assertEquals(listOf(Platform.LINUX), refreshed.toUserDto().platforms)
        assertEquals("fixture-original", refreshed.metadata.originalIds.values.single())
        assertEquals(1, refreshed.toUserDto().variants.size)
        assertEquals(2, refreshed.toAdminDto().variants.single().contents.size)
        assertFalse(Hibernate.isInitialized(refreshed.library))
        TransactionTemplate(transactions).execute {
            val missing = entityManager.getReference(Library::class.java, Long.MAX_VALUE)
            assertFailsWith<EntityNotFoundException> {
                service.matchManually(originalIds, java.nio.file.Path.of("/synthetic/input"), missing, persist = false)
            }
        }
        val detached = TransactionTemplate(transactions).execute {
            repository.findById(ids.first()).orElseThrow()
        }!!
        assertFalse(Hibernate.isInitialized(detached.library))
        val libraryId = detached.library.id!!
        assertEquals(libraryId, detached.toAdminDto().libraryId)
        assertEquals(libraryId, detached.toUserDto().libraryId)
        assertEquals(1, detached.toAdminDto().variants.size)
        assertEquals(2, detached.toUserDto().variants.single().contents.size)
        assertFalse(Hibernate.isInitialized(detached.library))
        val detachedLibrary = TransactionTemplate(transactions).execute {
            entityManager.find(Library::class.java, libraryId)
        }!!
        assertEquals(104, detachedLibrary.games.size)
        assertEquals(104, detachedLibrary.libraryAdminDto().gameIds!!.size)
        assertEquals(104, detachedLibrary.libraryUserDto().gameIds!!.size)
        TransactionTemplate(transactions).execute {
            val game = repository.findById(ids.first()).orElseThrow()
            val proxy = game.library
            assertTrue(proxy === entityManager.getReference(Library::class.java, libraryId))
            assertEquals(libraryId, proxy.id)
            proxy.name = "Updated seed library"
            entityManager.flush()
            assertEquals("Updated seed library", entityManager.find(Library::class.java, libraryId).name)
        }
        val executor = Executors.newFixedThreadPool(4)
        try {
            for (mode in listOf("identifier", "jpql", "identifier", "jpql")) {
                val process = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
                val cpuBefore = process.processCpuTime
                val started = System.nanoTime()
                val tasks = (0..3).map { worker -> executor.submit(Callable {
                    val stats = GameLoadStatementInspector.QueryStats()
                    GameLoadStatementInspector.perThread.set(stats)
                    try {
                        repeat(125) { iteration ->
                            TransactionTemplate(transactions).execute {
                                val id = ids[(worker * 125 + iteration) % ids.size]
                                val loaded = if (mode == "identifier") repository.findById(id).orElseThrow()
                                    else entityManager.createQuery("select g from Game g where g.id = :id", Game::class.java)
                                        .setParameter("id", id).singleResult
                                entityManager.detach(loaded)
                                assertEquals(1, loaded.variants.size)
                                assertEquals(2, loaded.variants.single().contents.size)
                                assertEquals(3, loaded.variants.single().contents.sumOf { it.paths.size })
                                assertEquals(true, loaded.variants.single().defaultLocked)
                            }
                        }
                        stats
                    } finally { GameLoadStatementInspector.perThread.remove() }
                }) }
                val observations = tasks.map { it.get(120, TimeUnit.SECONDS) }
                assertTrue(observations.sumOf { it.count } < 25_000,
                    "Single game reads must not cascade into loading every game in its library")
                val durationMs = (System.nanoTime() - started) / 1_000_000.0
                val cpuMs = (process.processCpuTime - cpuBefore) / 1_000_000.0
                println("SEED_LOAD mode=$mode loads=500 concurrency=4 durationMs=$durationMs processCpuMs=$cpuMs selects=${observations.sumOf { it.count }} maxJoins=${observations.maxOf { it.maxJoins }}")
                observations.flatMap { it.shapes }.distinct().forEach { println("SEED_LOAD_SQL mode=$mode $it") }
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
        TransactionTemplate(transactions).execute {
            val library = entityManager.find(Library::class.java, libraryId)
            val removed = library.games.single { it.id == ids.first() }
            val variantId = removed.variants.single().id!!
            val contentIds = removed.variants.single().contents.map { it.id!! }
            assertTrue(library.games.removeIf { it.id == ids.first() })
            entityManager.flush()
            entityManager.clear()
            assertFalse(repository.existsById(ids.first()))
            assertEquals(null, entityManager.find(GameVariant::class.java, variantId))
            contentIds.forEach { assertEquals(null, entityManager.find(VariantContent::class.java, it)) }
            assertTrue(repository.existsById(ids[1]))
            assertEquals(103, entityManager.find(Library::class.java, libraryId).games.size)
        }
    }
}
