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
import java.net.URI
import java.util.Optional
import kotlin.test.assertSame

class PositiveRefreshOwnedCollectionsTest {
    @Test fun `provider refresh does not replace user owned collection objects`() {
        val repository=mockk<GameRepository>();val manager=mockk<GameyfinPluginManager>();val plugins=mockk<PluginService>()
        val provider=mockk<GameMetadataProvider>();val entry=PluginManagementEntry("fixture",true,1)
        val library=Library(id=1,name="Synthetic",platforms=mutableListOf(Platform.PC_MICROSOFT_WINDOWS))
        val game=Game(id=1,library=library,metadata=org.gameyfin.app.games.entities.GameMetadata("/synthetic/owned",originalIds=mapOf(entry to "fixture-33000")))
        game.title="Same title";game.platforms=mutableListOf(Platform.PC_MICROSOFT_WINDOWS)
        game.genres=mutableListOf(Genre.entries.last());game.themes=mutableListOf(Theme.entries.last())
        game.keywords=mutableListOf("owned");game.features=mutableListOf(GameFeature.entries.last())
        game.perspectives=mutableListOf(PlayerPerspective.entries.last());game.videoUrls=mutableListOf(URI("https://fixture.invalid/owned"))
        val before=listOf(game.genres,game.themes,game.keywords,game.features,game.perspectives,game.videoUrls)
        val user=mockk<User>()
        for(field in listOf("genres","themes","keywords","features","perspectives","videoUrls"))game.metadata.fields[field]=GameFieldMetadata(source=GameFieldUserSource(user))
        every{repository.findById(1)} returns Optional.of(game)
        every{manager.getExtensions("fixture")} returns listOf(provider)
        every{manager.getExtensions(GameMetadataProvider::class.java)} returns listOf(provider)
        every{plugins.getPluginManagementEntry(provider.javaClass)} returns entry
        every{provider.fetchById("fixture-33000")} returns org.gameyfin.pluginapi.gamemetadata.GameMetadata(originalId="fixture-33000",title="Same title",platforms=setOf(Platform.PC_MICROSOFT_WINDOWS),
            genres=setOf(Genre.entries.first()),themes=setOf(Theme.entries.first()),keywords=setOf("provider"),
            features=setOf(GameFeature.entries.first()),perspectives=setOf(PlayerPerspective.entries.first()),videoUrls=setOf(URI("https://fixture.invalid/provider")))
        GameService(repository,manager,plugins,mockk(),mockk(),mockk(),mockk(),mockk(),mockk()).updateMetadata(game)
        before.zip(listOf(game.genres,game.themes,game.keywords,game.features,game.perspectives,game.videoUrls)).forEach{(a,b)->assertSame(a,b)}
    }
}
