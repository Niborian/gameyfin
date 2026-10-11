package org.gameyfin.app.games

import io.mockk.*
import org.gameyfin.app.core.plugins.PluginService
import org.gameyfin.app.core.plugins.management.*
import org.gameyfin.app.games.entities.*
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.libraries.entities.Library
import org.gameyfin.app.users.entities.User
import org.gameyfin.pluginapi.gamemetadata.*
import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.test.assertEquals

class PositiveRefreshOwnedCompaniesTest {
    @Test
    fun `user-owned changed companies skip canonicalization and retain selected values`() {
        val repository=mockk<GameRepository>();val manager=mockk<GameyfinPluginManager>()
        val plugins=mockk<PluginService>();val companies=mockk<CompanyService>()
        val provider=mockk<GameMetadataProvider>();val entry=PluginManagementEntry("fixture",true,1)
        val library=Library(id=1,name="Synthetic",platforms=mutableListOf(Platform.PC_MICROSOFT_WINDOWS))
        val game=Game(id=1,library=library,metadata=org.gameyfin.app.games.entities.GameMetadata("/synthetic/owned",originalIds=mapOf(entry to "fixture-31000")))
        game.title="Same title";game.platforms=mutableListOf(Platform.PC_MICROSOFT_WINDOWS)
        game.developers=mutableListOf(Company(name="User developer",type=CompanyType.DEVELOPER))
        game.publishers=mutableListOf(Company(name="User publisher",type=CompanyType.PUBLISHER))
        val user=mockk<User>()
        for(field in listOf("developers","publishers"))game.metadata.fields[field]=GameFieldMetadata(source=GameFieldUserSource(user))
        every{repository.findById(1)} returns Optional.of(game)
        every{manager.getExtensions("fixture")} returns listOf(provider)
        every{manager.getExtensions(GameMetadataProvider::class.java)} returns listOf(provider)
        every{plugins.getPluginManagementEntry(provider.javaClass)} returns entry
        every{provider.fetchById("fixture-31000")} returns org.gameyfin.pluginapi.gamemetadata.GameMetadata(
            originalId="fixture-31000",title="Same title",platforms=setOf(Platform.PC_MICROSOFT_WINDOWS),
            developedBy=setOf("Provider developer"),publishedBy=setOf("Provider publisher"))
        GameService(repository,manager,plugins,mockk(),companies,mockk(),mockk(),mockk(),mockk()).updateMetadata(game)
        assertEquals("User developer",game.developers.single().name)
        assertEquals("User publisher",game.publishers.single().name)
        verify(exactly=0){companies.createOrGet(any())}
    }
}
