package org.gameyfin.app.games

import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import jakarta.persistence.EntityManager
import org.gameyfin.app.core.Role
import org.gameyfin.app.core.config.JacksonConfig
import org.gameyfin.app.core.security.EncryptionUtils
import org.gameyfin.app.users.entities.User
import org.gameyfin.app.core.plugins.management.PluginManagementEntry
import org.gameyfin.app.core.plugins.management.GameyfinPluginManager
import org.gameyfin.app.core.plugins.PluginService
import org.gameyfin.app.config.ConfigService
import org.gameyfin.app.core.filesystem.FilesystemService
import org.gameyfin.app.users.UserService
import org.gameyfin.app.media.ImageService
import org.gameyfin.app.games.entities.*
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.games.extensions.toDtos
import org.gameyfin.app.libraries.entities.Library
import org.gameyfin.app.media.Image
import org.gameyfin.app.media.ImageType
import org.gameyfin.pluginapi.gamemetadata.Genre
import org.gameyfin.pluginapi.gamemetadata.Platform
import org.hibernate.Session
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.aop.support.AopUtils
import java.net.URI
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/** Real H2 DTO parity and request-local batch restoration regression. */
@DataJpaTest(showSql=false,properties=["spring.jpa.show-sql=false","spring.jpa.properties.hibernate.generate_statistics=true",
    "spring.jpa.properties.hibernate.session_factory.statement_inspector=org.gameyfin.app.games.GameBrowseBatchStatementInspector"])
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Import(GameService::class,GameBrowseBatchCollaborators::class)
class GameBrowseBatchTest @Autowired constructor(
    val em:EntityManager,val gameRepository:GameRepository,val service:GameService
) {
    @TempDir lateinit var root:Path
    private fun rows(query:String)=em.createQuery(query).resultList.map{(it as Array<*>).toList()}
    private fun selectionSnapshot()=listOf(
        rows("select v.id,v.game.id,v.path,v.version,v.isDefault,v.defaultLocked,v.retirementState from GameVariant v order by v.id"),
        rows("select c.id,c.variant.id,c.path,c.type,c.required,c.defaultSelected from VariantContent c order by c.id"),
        rows("select c.id,p from VariantContent c join c.paths p order by c.id,index(p)"),
        rows("select g.id,g.metadata.path,g.metadata.downloadCount from Game g order by g.id"))
    private fun fileSnapshot()=Files.walk(root).use{paths->paths.filter{Files.isRegularFile(it)}.toList().associateWith{Files.readAllBytes(it).toList()}}

    @Test fun `administrator user-field provenance retains normal serialized username`() {
        mockkObject(EncryptionUtils)
        every { EncryptionUtils.encrypt(any()) } answers { firstArg() }
        every { EncryptionUtils.decrypt(any()) } answers { firstArg() }
        val session=em.unwrap(Session::class.java);val previous=session.fetchBatchSize
        try {
            val user=User(username="fixture-author",email="fixture-author@example.invalid");em.persist(user)
            val library=Library(name="User provenance");em.persist(library)
            val game=Game(library=library,metadata=GameMetadata(path="/fixture/user-owned"))
            game.title="User-owned title";game.metadata.fields["title"]=GameFieldMetadata(source=GameFieldUserSource(user))
            em.persist(game);em.flush();em.clear()
            SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken("fixture",null,listOf(SimpleGrantedAuthority(Role.Names.ADMIN)))
            val mapper=JacksonConfig().jsonMapperFactory().build()
            val expected=mapper.readTree(mapper.writeValueAsString(gameRepository.findAll().toDtos()))
            em.clear()
            val actual=mapper.readTree(mapper.writeValueAsString(service.getAll()))
            assertEquals(expected,actual)
            assertEquals("fixture-author",actual[0]["metadata"]["fields"]["title"]["source"].asText())
            assertEquals(previous,session.fetchBatchSize)
        }finally{session.fetchBatchSize=previous;SecurityContextHolder.clearContext();unmockkObject(EncryptionUtils)}
    }

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    fun `normal injected service opens read-only request transaction`() {
        assertTrue(AopUtils.isAopProxy(service))
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        var readOnly=false;var batch=-999
        GameBrowseBatchStatementInspector.callback.set {
            readOnly=TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            batch=em.unwrap(Session::class.java).fetchBatchSize
        }
        try {
            assertTrue(service.getAll().isEmpty())
            assertTrue(readOnly);assertEquals(64,batch)
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        }finally{GameBrowseBatchStatementInspector.callback.remove()}
    }

    @Test fun `actual 132 game DTO load batches collections without changing DTOs or session policy`() {
        val plugin=PluginManagementEntry("fixture.metadata");em.persist(plugin)
        val developer=Company(name="Fixture shared studio",type=CompanyType.DEVELOPER);em.persist(developer)
        val publisher=Company(name="Fixture shared publisher",type=CompanyType.PUBLISHER);em.persist(publisher)
        listOf(104,27,1,0).forEachIndexed {libraryIndex,count->
            val library=Library(name="Fixture $libraryIndex",platforms=mutableListOf(Platform.PC_MICROSOFT_WINDOWS))
            em.persist(library)
            repeat(count){index->
                val path=Files.createDirectories(root.resolve("lib$libraryIndex/game-$index"))
                listOf("1.0","1.3").forEach{version->
                    val dir=Files.createDirectory(path.resolve("Normal $version"))
                    Files.write(dir.resolve("game.bin"),ByteArray(16384))
                    Files.writeString(dir.resolve("metadata.txt"),"variant=Normal\nversion=$version\n")
                }
                listOf("base-a.bin","base-b.bin","patch.bin").forEach{Files.write(path.resolve(it),ByteArray(16384))}
                val game=Game(library=library,metadata=GameMetadata(path.toString(),fileSize=49152,downloadCount=index%5,
                    originalIds=mapOf(plugin to "fixture-$libraryIndex-$index"),matchConfirmed=true))
                game.title="Fixture game $libraryIndex-$index";game.summary="Synthetic summary"
                game.platforms.add(Platform.PC_MICROSOFT_WINDOWS);game.genres=mutableListOf(Genre.ACTION)
                game.keywords=mutableListOf("fixture","positive");game.videoUrls=mutableListOf(URI("https://example.invalid/fixture"))
                game.developers.add(developer);game.publishers.add(publisher)
                game.coverImage=Image(type=ImageType.COVER,blurhash="fixture-cover")
                game.images.add(Image(type=ImageType.SCREENSHOT,blurhash="fixture-screenshot"))
                game.metadata.fields["title"]=GameFieldMetadata(source=GameFieldPluginSource(plugin))
                listOf("1.0","1.3").forEach{version->
                    val variant=GameVariant(game=game,path=path.resolve("Normal $version").toString(),version=version,
                        isDefault=version=="1.0",defaultLocked=true,scanManaged=false,
                        retirementState=if(version=="1.0")VariantRetirementState.ACTIVE else VariantRetirementState.ARCHIVED,
                        tags=mutableSetOf("fixture","stable"))
                    variant.contents.add(VariantContent(variant=variant,name="Required base",path=path.resolve("base-a.bin").toString(),
                        required=true,defaultSelected=true,paths=mutableListOf(path.resolve("base-a.bin").toString(),path.resolve("base-b.bin").toString()),tags=mutableSetOf("required")))
                    variant.contents.add(VariantContent(variant=variant,name="Optional patch",path=path.resolve("patch.bin").toString(),type=VariantContentType.PATCH))
                    game.variants.add(variant)
                }
                em.persist(game)
            }
        }
        em.flush();em.clear()
        val beforeFiles=fileSnapshot();val beforeSelection=selectionSnapshot()
        assertEquals(924,beforeFiles.size);assertEquals(264,beforeSelection[0].size);assertEquals(528,beforeSelection[1].size)
        val session=em.unwrap(Session::class.java)
        val oldBatch=session.fetchBatchSize;val oldSubselect=session.isSubselectFetchingEnabled
        val mapper=JacksonConfig().jsonMapperFactory().build()
        try {
            for(role in listOf(Role.Names.USER,Role.Names.ADMIN,Role.Names.SUPERADMIN)) {
                val admin=role!=Role.Names.USER
                SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken("fixture",null,
                    listOf(SimpleGrantedAuthority(role)))
                var baseline:String?=null
                for((label,previous) in listOf("legacy" to 0,"service" to 0,"service" to 17)) {
                    em.clear();session.fetchBatchSize=previous
                    val stats=GameLoadStatementInspector.QueryStats();GameLoadStatementInspector.perThread.set(stats)
                    val persistenceStats=em.entityManagerFactory.unwrap(SessionFactory::class.java).statistics
                    persistenceStats.clear();val started=System.nanoTime()
                    val threadStats=ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
                    val threadId=Thread.currentThread().threadId()
                    val cpuBefore=threadStats?.takeIf{it.isCurrentThreadCpuTimeSupported && it.isThreadCpuTimeEnabled}?.currentThreadCpuTime
                    val allocationBefore=threadStats?.takeIf{it.isThreadAllocatedMemoryEnabled}?.getThreadAllocatedBytes(threadId)
                    val dtos=try{
                        if(label=="legacy")gameRepository.findAll().toDtos() else service.getAll()
                    }finally{GameLoadStatementInspector.perThread.remove()}
                    val json=mapper.writeValueAsString(dtos)
                    val wall=(System.nanoTime()-started)/1000000.0
                    val cpuMs=cpuBefore?.let{(threadStats!!.currentThreadCpuTime-it)/1000000.0}
                    val allocatedBytes=allocationBefore?.let{threadStats!!.getThreadAllocatedBytes(threadId)-it}
                    println("GAME_BROWSE_BATCH role=$role mode=$label previous=$previous selects=${stats.count} entityLoads=${persistenceStats.entityLoadCount} collections=${persistenceStats.collectionLoadCount} wallMs=$wall cpuMs=$cpuMs allocatedBytes=$allocatedBytes jsonBytes=${json.toByteArray().size}")
                    assertEquals(132,dtos.size)
                    if(baseline==null)baseline=json else assertEquals(mapper.readTree(baseline),mapper.readTree(json))
                    assertEquals(if(label=="legacy")3435 else 73,stats.count)
                    assertEquals(previous,session.fetchBatchSize)
                    assertEquals(oldSubselect,session.isSubselectFetchingEnabled)
                    val tree=mapper.readTree(json)
                    assertEquals(if(admin)2 else 1,tree[0]["variants"].size())
                    assertEquals(2,tree[0]["variants"][0]["contents"].size())
                    assertTrue(tree[0]["cover"]["id"].asLong()>0)
                    assertEquals(1,tree[0]["images"].size())
                }
            }
        }finally{session.fetchBatchSize=oldBatch;session.isSubselectFetchingEnabled=oldSubselect;SecurityContextHolder.clearContext()}
        em.flush();em.clear();assertEquals(beforeSelection,selectionSnapshot());assertEquals(beforeFiles,fileSnapshot())
    }
}

class GameBrowseBatchStatementInspector : org.hibernate.resource.jdbc.spi.StatementInspector {
    companion object { val callback=ThreadLocal<()->Unit>() }
    private val delegate=GameLoadStatementInspector()
    override fun inspect(sql:String):String? {
        if(sql.trimStart().startsWith("select",ignoreCase=true))callback.get()?.invoke()
        return delegate.inspect(sql)
    }
}

@TestConfiguration
@EnableTransactionManagement(proxyTargetClass = true)
class GameBrowseBatchCollaborators {
    @Bean fun browsePluginManager():GameyfinPluginManager=mockk()
    @Bean fun browsePluginService():PluginService=mockk()
    @Bean fun browseConfig():ConfigService=mockk()
    @Bean fun browseCompanies():CompanyService=mockk()
    @Bean fun browseUsers():UserService=mockk()
    @Bean fun browseImages():ImageService=mockk { every { prePopulateImageCache() } just Runs }
    @Bean fun browseFilesystem():FilesystemService=mockk()
}
