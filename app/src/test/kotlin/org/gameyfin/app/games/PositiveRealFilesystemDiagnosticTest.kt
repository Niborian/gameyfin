package org.gameyfin.app.games

import io.mockk.*
import jakarta.persistence.EntityManager
import org.gameyfin.app.core.filesystem.FilesystemService
import org.gameyfin.app.core.plugins.PluginService
import org.gameyfin.app.core.plugins.management.*
import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.games.entities.GameVariant
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.games.variants.*
import org.gameyfin.app.libraries.LibraryRepository
import org.gameyfin.app.libraries.entities.Library
import org.gameyfin.app.libraries.scan.LibraryGameProcessor
import org.gameyfin.app.media.ImageService
import org.gameyfin.pluginapi.gamemetadata.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import java.nio.file.Files
import java.nio.file.Path
import java.net.URI
import kotlin.test.assertEquals

/** Independent local diagnostic: no server, network, media, or hardlink activity. */
@DataJpaTest(properties=["spring.jpa.show-sql=false"])
@Import(CompanyService::class,CompanyInsertService::class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PositiveRealFilesystemDiagnosticTest @Autowired constructor(
    val em:EntityManager,val games:GameRepository,val libraries:LibraryRepository,val companies:CompanyService
) {
    @TempDir lateinit var root:Path

    @Test fun `actual discovery sizing and variant synchronization enrich one existing game`() {
        for(version in listOf("1.0","1.3")) {
            val dir=Files.createDirectory(root.resolve("Normal $version"))
            Files.write(dir.resolve("game.bin"),ByteArray(16384))
            Files.writeString(dir.resolve("metadata.txt"),"variant=Normal\nversion=$version\ncontent.base.type=BASE\ncontent.base.path=game.bin\ncontent.base.required=true\n")
        }
        for(name in listOf("base-a.bin","base-b.bin","patch.bin")) Files.write(root.resolve(name),ByteArray(16384))
        val sourceBytes=Files.walk(root).use{paths->paths.filter{Files.isRegularFile(it)}.toList().associateWith{Files.readAllBytes(it).toList()}}
        var collectionSize=1
        val provider=object:GameMetadataProvider {
            override val supportedPlatforms=setOf(Platform.PC_MICROSOFT_WINDOWS,Platform.LINUX)
            override fun fetchByTitle(gameTitle:String,platformFilter:Set<Platform>,maxResults:Int)=emptyList<org.gameyfin.pluginapi.gamemetadata.GameMetadata>()
            override fun fetchById(id:String)=org.gameyfin.pluginapi.gamemetadata.GameMetadata(originalId=id,
                title="Enriched fixture game 33000",platforms=supportedPlatforms,description="Synthetic enrichment",
                developedBy=setOf("Diagnostic Studio"),publishedBy=setOf("Diagnostic Publisher"),
                genres=Genre.entries.take(collectionSize).toSet(),themes=Theme.entries.take(collectionSize).toSet(),
                keywords=(1..collectionSize).map{"keyword-$it"}.toSet(),features=GameFeature.entries.take(collectionSize).toSet(),
                perspectives=PlayerPerspective.entries.take(collectionSize).toSet(),
                videoUrls=(1..collectionSize).map{URI("https://fixture.invalid/video/$it")}.toSet())
        }
        val entry=PluginManagementEntry("fixture-diagnostic",true,1,PluginTrustLevel.THIRD_PARTY).also{em.persist(it)}
        val manager=mockk<GameyfinPluginManager>();val plugins=mockk<PluginService>()
        every{manager.getExtensions(entry.pluginId)} returns listOf(provider)
        every{manager.getExtensions(GameMetadataProvider::class.java)} returns listOf(provider)
        every{plugins.getPluginManagementEntry(provider.javaClass)} returns entry
        val images=mockk<ImageService>();val mirror=mockk<HardlinkMirrorService>()
        val files=FilesystemService(mockk())
        val discovery=GameVariantDiscoveryService(VariantMetadataParser())
        val variants=GameVariantService(games,files,mirror)
        val service=GameService(games,manager,plugins,mockk(),companies,mockk(),images,files,libraries)
        val processor=LibraryGameProcessor(service,images,files,discovery,mockk(),variants)
        val library=Library(name="Synthetic",platforms=mutableListOf(Platform.PC_MICROSOFT_WINDOWS)).also{em.persist(it)}
        val game=Game(library=library,metadata=org.gameyfin.app.games.entities.GameMetadata(root.toString(),originalIds=mapOf(entry to "fixture-33000")))
        game.title="Synthetic game 33000"
        game.variants.add(GameVariant(game=game,name="Normal",version="1.0",path=root.resolve("Normal 1.0").toString(),isDefault=true,defaultLocked=true,scanManaged=false))
        em.persist(game);em.flush();val id=game.id!!
        val pinnedDefaultId=game.variants.single().id!!;em.clear()
        try {
            for(size in listOf(1,2,0)) {
                collectionSize=size
                repeat(2) {processor.processExistingGame(games.findById(id).orElseThrow());em.flush();em.clear()}
                val current=games.findById(id).orElseThrow()
                val expected=if(size==0)2 else size // Existing policy ignores empty provider values.
                assertEquals(expected,current.genres.size);assertEquals(expected,current.themes.size)
                assertEquals(expected,current.keywords.size);assertEquals(expected,current.features.size)
                assertEquals(expected,current.perspectives.size);assertEquals(expected,current.videoUrls.size)
                assertEquals(pinnedDefaultId,current.variants.single{it.isDefault}.id)
                assertEquals(setOf("1.0","1.3"),current.variants.map{it.version}.toSet())
            }
        } catch(error:Exception) {
            generateSequence(error as Throwable){it.cause}.take(8).forEach {cause->
                println("FIXTURE_EXCEPTION_CLASS ${cause.javaClass.name}")
                cause.stackTrace.filter{it.className.startsWith("org.gameyfin")||it.className.startsWith("org.hibernate")||it.className.startsWith("java.")}.take(20).forEach{println("FIXTURE_FRAME $it")}
            }
            throw error
        }
        em.flush();em.clear()
        val result=games.findById(id).orElseThrow()
        assertEquals("Enriched fixture game 33000",result.title)
        assertEquals(setOf("1.0","1.3"),result.variants.map{it.version}.toSet())
        assertEquals("1.0",result.variants.single{it.isDefault}.version)
        assertEquals(pinnedDefaultId,result.variants.single{it.isDefault}.id)
        val afterPaths=Files.walk(root).use{paths->paths.filter{Files.isRegularFile(it)}.toList().toSet()}
        assertEquals(sourceBytes.keys,afterPaths)
        sourceBytes.forEach{(path,bytes)->assertEquals(bytes,Files.readAllBytes(path).toList())}
        verify {images wasNot Called;mirror wasNot Called}
    }
}
