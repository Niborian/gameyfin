package org.gameyfin.app.games

import io.mockk.*
import jakarta.persistence.EntityManager
import org.gameyfin.app.core.filesystem.FilesystemService
import org.gameyfin.app.core.plugins.PluginService
import org.gameyfin.app.core.plugins.management.*
import org.gameyfin.app.games.entities.*
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.games.variants.*
import org.gameyfin.app.libraries.LibraryRepository
import org.gameyfin.app.libraries.entities.Library
import org.gameyfin.app.libraries.scan.LibraryGameProcessor
import org.gameyfin.app.media.ImageService
import org.gameyfin.pluginapi.gamemetadata.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.*
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.*
import kotlin.test.assertEquals

/** Local diagnostic only: real H2/GameService/processor/Company services, no external I/O. */
@DataJpaTest(properties=["spring.jpa.show-sql=false"])
@Import(CompanyService::class,CompanyInsertService::class)
class PositiveProviderProcessorReproductionTest @Autowired constructor(
    val em:EntityManager,val games:GameRepository,val libraries:LibraryRepository,
    val transactions:PlatformTransactionManager,val companies:CompanyService
) {
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    fun `existing positive refresh repeatedly reuses shared developer and publisher`() = scenario(false, false)

    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    fun `same company name remains distinct by developer and publisher type`() = scenario(false, true)

    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    fun `concurrent existing positive refreshes reuse shared committed companies`() = scenario(true, false)

    private fun scenario(concurrent:Boolean, sameName:Boolean) {
        val token=UUID.randomUUID().toString()
        val developer=if(sameName) "Shared-$token" else "Fixture Studio-$token"
        val publisher=if(sameName) developer else "Fixture Publisher-$token"
        val provider=object:GameMetadataProvider {
            override val supportedPlatforms=setOf(Platform.PC_MICROSOFT_WINDOWS,Platform.LINUX)
            override fun fetchByTitle(gameTitle:String,platformFilter:Set<Platform>,maxResults:Int)=emptyList<org.gameyfin.pluginapi.gamemetadata.GameMetadata>()
            override fun fetchById(id:String)=org.gameyfin.pluginapi.gamemetadata.GameMetadata(
                originalId=id,title="Enriched fixture game "+id.removePrefix("fixture-"),platforms=supportedPlatforms,
                description="Deterministic enrichment for synthetic game "+id.removePrefix("fixture-"),
                developedBy=setOf(developer),publishedBy=setOf(publisher),
                genres=setOf(Genre.ACTION),keywords=setOf("synthetic-positive-provider"))
        }
        val manager=mockk<GameyfinPluginManager>();val plugins=mockk<PluginService>()
        val images=mockk<ImageService>();val files=mockk<FilesystemService>()
        val discovery=mockk<GameVariantDiscoveryService>();val variants=mockk<GameVariantService>()
        val tx=TransactionTemplate(transactions)
        val entry=tx.execute {PluginManagementEntry("fixture-$token",true,1,PluginTrustLevel.THIRD_PARTY).also{em.persist(it)}}!!
        every{manager.getExtensions(entry.pluginId)} returns listOf(provider)
        every{manager.getExtensions(GameMetadataProvider::class.java)} returns listOf(provider)
        every{plugins.getPluginManagementEntry(provider.javaClass)} returns entry
        every{files.calculateFileSize(any())} returns 49152L
        every{discovery.discover(any())} answers {DiscoveredGameVariants(firstArg(),emptyList())}
        every{variants.syncVariants(any(),any(),any())} answers {firstArg()}
        val service=GameService(games,manager,plugins,mockk(),companies,mockk(),images,files,libraries)
        val processor=LibraryGameProcessor(service,images,files,discovery,mockk(),variants)
        val ids=tx.execute {
            val library=Library(name="Synthetic",platforms=mutableListOf(Platform.PC_MICROSOFT_WINDOWS));em.persist(library)
            (31000..31005).map {n->Game(library=library,metadata=org.gameyfin.app.games.entities.GameMetadata("/synthetic/$token/$n",originalIds=mapOf(entry to "fixture-$n"))).also{it.title="Synthetic game $n";em.persist(it)}.id!!}.also{em.flush()}
        }!!
        fun refresh(id:Long) {
            try {
                tx.execute {processor.processExistingGame(games.findById(id).orElseThrow());em.flush()}
                tx.execute {assertEquals("Enriched fixture game "+(31000+ids.indexOf(id)),games.findById(id).orElseThrow().title)}
            } catch(error:Exception) {
                var cause:Throwable=error
                while(cause.cause!=null && cause.cause!==cause)cause=cause.cause!!
                println("POSITIVE_PROCESSOR_CAUSE type=${cause.javaClass.name} message=${cause.message}")
                throw error
            }
        }
        if(concurrent) {
            val pool=Executors.newFixedThreadPool(4)
            try {ids.map {id->pool.submit(Callable {refresh(id)})}.forEach{it.get(30,TimeUnit.SECONDS)}}
            finally {pool.shutdownNow()}
        } else ids.forEach(::refresh)
        // Repeated successful matching must be harmless even when no fields change.
        ids.forEach(::refresh)
        tx.execute {
            val rows=em.createQuery("select c from Company c where c.name in :names",Company::class.java).setParameter("names",setOf(developer,publisher)).resultList
            assertEquals(2,rows.size)
            assertEquals(setOf(CompanyType.DEVELOPER,CompanyType.PUBLISHER),rows.map{it.type}.toSet())
            ids.forEach {id->
                val game=games.findById(id).orElseThrow()
                assertEquals(developer,game.developers.single().name)
                assertEquals(publisher,game.publishers.single().name)
                assertEquals(listOf(Platform.PC_MICROSOFT_WINDOWS),game.platforms)
                assertEquals(listOf(Genre.ACTION),game.genres)
                assertEquals(listOf("synthetic-positive-provider"),game.keywords)
            }
        }
        verify(exactly=0){images.downloadIfNew(any())}
    }
}
