package org.gameyfin.app.libraries

import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.every
import jakarta.persistence.EntityManager
import org.hibernate.SessionFactory
import org.gameyfin.app.core.Role
import org.gameyfin.app.core.config.JacksonConfig
import org.gameyfin.app.core.security.EncryptionUtils
import org.gameyfin.app.core.plugins.management.PluginManagementEntry
import org.gameyfin.app.users.entities.User
import org.gameyfin.app.games.GameLoadStatementInspector
import org.gameyfin.app.games.entities.*
import org.gameyfin.app.libraries.dto.*
import org.gameyfin.app.libraries.entities.*
import org.gameyfin.app.libraries.extensions.toDtos
import org.gameyfin.pluginapi.gamemetadata.Platform
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.annotation.DirtiesContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real H2 browse regression; original mapper is the compatibility oracle. */
@DataJpaTest(properties=["spring.jpa.show-sql=false","spring.jpa.properties.hibernate.generate_statistics=true","spring.jpa.properties.hibernate.session_factory.statement_inspector=org.gameyfin.app.games.GameLoadStatementInspector"])
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class LibraryBrowseProjectionTest @Autowired constructor(val em:EntityManager,val repository:LibraryRepository) {
    @TempDir lateinit var root:Path

    private fun rows(query:String)=em.createQuery(query).resultList.map{it as Array<*>}
    private fun json(value:List<LibraryDto>):String {
        return JacksonConfig().jsonMapperFactory().build().writeValueAsString(value)
    }

    @Test fun `all ignored sources ordered collections nullable directories and role isolation match original DTOs`() {
        mockkObject(EncryptionUtils)
        every { EncryptionUtils.encrypt(any()) } answers { firstArg() }
        every { EncryptionUtils.decrypt(any()) } answers { firstArg() }
        try {
            val user=User(username="synthetic",email="synthetic@example.invalid")
            em.persist(user)
            val plugins=listOf(PluginManagementEntry("z.fixture"),PluginManagementEntry("a.fixture"))
            plugins.forEach(em::persist)
            val library=Library(name="Rich",platforms=mutableListOf(Platform.LINUX,Platform.PC_MICROSOFT_WINDOWS),
                metadata=LibraryMetadata(false,7))
            library.directories.addAll(listOf(DirectoryMapping(internalPath="/fixture/z",externalPath=null),DirectoryMapping(internalPath="/fixture/a",externalPath="/external/a")))
            library.ignoredPaths.addAll(listOf(
                IgnoredPath(path="/fixture/plugin",source=IgnoredPathPluginSource(plugins.toMutableList())),
                IgnoredPath(path="/fixture/user",source=IgnoredPathUserSource(user)),
                IgnoredPath(path="/fixture/grouped",source=IgnoredPathGroupedVariantSource()),
                IgnoredPath(path="/fixture/empty-plugin",source=IgnoredPathPluginSource(mutableListOf()))))
            em.persist(library);em.persist(Library(name="Empty"))
            repeat(3){index->
                val game=Game(library=library,metadata=GameMetadata(path="/fixture/game$index",downloadCount=index*3))
                game.title="Synthetic game $index"
                em.persist(game)
            }
            em.flush();em.clear()
            val service=LibraryService(repository,mockk(),mockk(),mockk())
            for(role in listOf(Role.Names.ADMIN,Role.Names.USER,Role.Names.SUPERADMIN,null)) {
                SecurityContextHolder.getContext().authentication=role?.let{UsernamePasswordAuthenticationToken("fixture",null,listOf(SimpleGrantedAuthority(it)))}
                em.clear();val expected=json(repository.findAll().toDtos())
                em.clear();val stats=GameLoadStatementInspector.QueryStats();GameLoadStatementInspector.perThread.set(stats)
                val actual=try{service.getAll()}finally{GameLoadStatementInspector.perThread.remove()}
                assertEquals(expected,json(actual))
                val admin=role==Role.Names.ADMIN || role==Role.Names.SUPERADMIN
                assertEquals(if(admin)7 else 2,stats.count)
                if(admin) {
                    val rich=actual.first{it.name=="Rich"} as LibraryAdminDto
                    assertEquals(9,rich.stats!!.downloadedGamesCount)
                    assertEquals(listOf("[\"z.fixture\", \"a.fixture\"]",user.id.toString(),"","[\"\"]"),rich.ignoredPaths!!.map{it.source})
                }
            }
        }finally{SecurityContextHolder.clearContext();unmockkObject(EncryptionUtils)}
    }

    @Test fun `empty catalogue uses one query and no graph`() {
        val stats=GameLoadStatementInspector.QueryStats();GameLoadStatementInspector.perThread.set(stats)
        try {
            assertTrue(LibraryService(repository,mockk(),mockk(),mockk()).getAll().isEmpty())
            assertEquals(1,stats.count)
        }finally{GameLoadStatementInspector.perThread.remove()}
    }

    @Test fun `historical null library game is excluded like the original library mapper`() {
        val library=Library(name="Historical")
        em.persist(library)
        val game=Game(library=library,metadata=GameMetadata(path="/fixture/historical"))
        game.title="Historical synthetic game"
        em.persist(game);em.flush()
        // The original migrated GAME.LIBRARY_ID permits null historical rows.
        em.createNativeQuery("UPDATE GAME SET LIBRARY_ID=NULL WHERE ID=:id")
            .setParameter("id",game.id).executeUpdate()
        em.clear()
        val expected=json(repository.findAll().toDtos())
        em.clear()
        assertEquals(expected,json(LibraryService(repository,mockk(),mockk(),mockk()).getAll()))
    }

    @Test fun `actual library service graph versus scalar DTO fields with 132 games`() {
        listOf(104,27,1,0).forEachIndexed {libraryIndex,count->
            val library=Library(name="Fixture $libraryIndex",platforms=mutableListOf(Platform.PC_MICROSOFT_WINDOWS),
                metadata=LibraryMetadata(libraryIndex!=3,libraryIndex))
            library.directories.add(DirectoryMapping(internalPath=root.resolve("lib$libraryIndex").toString(),externalPath="/fixture/lib$libraryIndex"))
            library.ignoredPaths.add(IgnoredPath(path=root.resolve("ignored-$libraryIndex").toString(),source=IgnoredPathGroupedVariantSource()))
            em.persist(library)
            repeat(count) {index->
                val gameRoot=Files.createDirectories(root.resolve("lib$libraryIndex/game-$index"))
                for(version in listOf("1.0","1.3")) {
                    val dir=Files.createDirectory(gameRoot.resolve("Normal $version"))
                    Files.write(dir.resolve("game.bin"),ByteArray(16384))
                    Files.writeString(dir.resolve("metadata.txt"),"variant=Normal\nversion=$version\n")
                }
                for(name in listOf("base-a.bin","base-b.bin","patch.bin"))Files.write(gameRoot.resolve(name),ByteArray(16384))
                val game=Game(library=library,metadata=GameMetadata(gameRoot.toString(),fileSize=49152,downloadCount=index%5,matchConfirmed=true))
                game.title="Fixture game $index"
                val variant=GameVariant(game=game,path=gameRoot.resolve("Normal 1.0").toString(),version="1.0",isDefault=true,defaultLocked=true,scanManaged=false)
                variant.contents.add(VariantContent(variant=variant,name="Grouped base",path=gameRoot.resolve("base-a.bin").toString(),required=true,defaultSelected=true,paths=mutableListOf(gameRoot.resolve("base-a.bin").toString(),gameRoot.resolve("base-b.bin").toString())))
                variant.contents.add(VariantContent(variant=variant,name="Optional patch",path=gameRoot.resolve("patch.bin").toString(),type=VariantContentType.PATCH))
                game.variants.add(variant);em.persist(game)
            }
        }
        em.flush();em.clear()
        fun selectionSnapshot()=listOf(
            rows("select v.id,v.game.id,v.path,v.version,v.isDefault,v.defaultLocked,v.scanManaged from GameVariant v order by v.id").map{it.toList()},
            rows("select c.id,c.variant.id,c.name,c.path,c.type,c.required,c.defaultSelected from VariantContent c order by c.id").map{it.toList()},
            rows("select c.id,p from VariantContent c join c.paths p order by c.id,index(p)").map{it.toList()},
            rows("select g.id,g.metadata.downloadCount from Game g order by g.id").map{it.toList()}
        )
        val beforeSelection=selectionSnapshot()
        assertEquals(132,beforeSelection[0].size)
        assertEquals(264,beforeSelection[1].size)
        assertEquals(264,beforeSelection[2].size)
        assertTrue(beforeSelection[3].sumOf{it[1] as Int}>0)
        val beforeFiles=Files.walk(root).use{paths->paths.filter{Files.isRegularFile(it)}.toList().associateWith{Files.readAllBytes(it).toList()}}
        assertEquals(924,beforeFiles.size)
        val service=LibraryService(repository,mockk(),mockk(),mockk())
        try {
            for(admin in listOf(false,true,false,true)) {
                SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken("fixture",null,listOf(SimpleGrantedAuthority(if(admin)Role.Names.ADMIN else Role.Names.USER)))
                fun measured(label:String,read:()->List<LibraryDto>):Pair<List<LibraryDto>,Long> {
                    em.clear();val stats=GameLoadStatementInspector.QueryStats();GameLoadStatementInspector.perThread.set(stats)
                    val started=System.nanoTime()
                    try {
                        val result=read()
                        println("LIBRARY_DTO_DIAGNOSTIC admin=$admin mode=$label selects=${stats.count} wallMs=${(System.nanoTime()-started)/1000000.0}")
                        return result to stats.count.toLong()
                    }finally{GameLoadStatementInspector.perThread.remove()}
                }
                val (actual,actualQueries)=measured("legacy-entity"){repository.findAll().toDtos()}
                val persistenceStats=em.entityManagerFactory.unwrap(SessionFactory::class.java).statistics
                persistenceStats.clear()
                val (projected,projectionQueries)=measured("service",service::getAll)
                assertEquals(0L,persistenceStats.entityLoadCount,"Browse projections must not hydrate entities")
                // PersistentBag deliberately lacks value equals; compare the
                // actual Hilla production serialization contract, not bag identity.
                val mapper=JacksonConfig().jsonMapperFactory().build()
                assertEquals(mapper.readTree(mapper.writeValueAsString(actual.sortedBy{it.id})),
                    mapper.readTree(mapper.writeValueAsString(projected.sortedBy{it.id})))
                assertEquals(listOf(104,27,1,0),projected.map{it.gameIds!!.size})
                assertTrue(actualQueries>projectionQueries*10,"Diagnostic must establish material query fanout before considering a fix")
                assertEquals(if(admin)7 else 2,projectionQueries)
            }
        }finally{SecurityContextHolder.clearContext()}
        em.flush();em.clear()
        assertEquals(beforeSelection,selectionSnapshot())
        val afterFiles=Files.walk(root).use{paths->paths.filter{Files.isRegularFile(it)}.toList().associateWith{Files.readAllBytes(it).toList()}}
        assertEquals(beforeFiles,afterFiles)
    }
}
