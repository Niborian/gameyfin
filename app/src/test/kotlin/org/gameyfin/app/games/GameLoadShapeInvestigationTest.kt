package org.gameyfin.app.games

import jakarta.persistence.EntityManager
import org.gameyfin.app.games.entities.*
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.core.plugins.management.PluginManagementEntry
import org.gameyfin.app.libraries.entities.Library
import org.hibernate.resource.jdbc.spi.StatementInspector
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

class GameLoadStatementInspector : StatementInspector {
    class QueryStats {
        var count = 0
        var maxJoins = 0
        val shapes = linkedSetOf<String>()
    }
    companion object {
        val statements = CopyOnWriteArrayList<String>()
        val perThread = ThreadLocal<QueryStats>()
    }
    override fun inspect(sql: String): String {
        if (sql.startsWith("select", ignoreCase = true)) {
            val stats = perThread.get()
            if (stats == null) statements.add(sql) else {
                stats.count++
                stats.maxJoins = maxOf(stats.maxJoins, Regex("\\bjoin\\b", RegexOption.IGNORE_CASE).findAll(sql).count())
                if (stats.shapes.size < 100) stats.shapes.add(sql)
            }
        }
        return sql
    }
}

/** Local investigation only: actual H2/Hibernate, no filesystem or external providers. */
@DataJpaTest(properties = [
    "spring.jpa.show-sql=false",
    "spring.jpa.properties.hibernate.session_factory.statement_inspector=org.gameyfin.app.games.GameLoadStatementInspector"
])
class GameLoadShapeInvestigationTest @Autowired constructor(
    private val entityManager: EntityManager,
    private val repository: GameRepository
) {
    @Test
    fun `compare identifier loading and JPQL with identical eager variant graph`() {
        for (fieldCount in listOf(0, 15, 60)) exercise(fieldCount)
    }

    private fun exercise(fieldCount: Int) {
        val library = Library(name = "Load-shape fixture $fieldCount")
        entityManager.persist(library)
        val game = Game(library = library, metadata = GameMetadata(path = "/synthetic/load-shape-$fieldCount"))
        game.title = "Fixture game"
        game.keywords = listOf("one", "two", "three")
        val providers = (1..3).map { PluginManagementEntry(pluginId = "fixture-provider-$fieldCount-$it") }
        providers.forEach(entityManager::persist)
        game.metadata.originalIds = providers.associateWith { "fixture-original-id" }
        repeat(fieldCount) { field ->
            game.metadata.fields["field-$field"] = GameFieldMetadata(source = GameFieldPluginSource(providers[field % providers.size]))
        }
        repeat(3) { version ->
            val variant = GameVariant(game = game, version = "$version", path = "${game.metadata.path}/$version")
            variant.tags.addAll(listOf("base", "fixture"))
            variant.isDefault = version == 1
            variant.defaultLocked = version == 1
            repeat(4) { content ->
                variant.contents.add(VariantContent(
                    variant = variant, name = "Content $content", path = "${variant.path}/$content",
                    required = content == 0, paths = mutableListOf("${variant.path}/$content/a", "${variant.path}/$content/b"),
                    tags = mutableSetOf("fixture", "exact")
                ))
            }
            game.variants.add(variant)
        }
        repository.saveAndFlush(game)
        val id = game.id!!
        for (mode in listOf("identifier", "jpql", "identifier", "jpql")) {
            entityManager.clear()
            GameLoadStatementInspector.statements.clear()
            val start = System.nanoTime()
            val loaded = if (mode == "identifier") repository.findById(id).orElseThrow()
                else entityManager.createQuery("select g from Game g where g.id = :id", Game::class.java)
                    .setParameter("id", id).singleResult
            val duration = (System.nanoTime() - start) / 1_000_000.0
            entityManager.detach(loaded)
            assertEquals(3, loaded.variants.size)
            assertEquals(12, loaded.variants.sumOf { it.contents.size })
            assertEquals(24, loaded.variants.sumOf { v -> v.contents.sumOf { it.paths.size } })
            assertEquals(listOf("1"), loaded.variants.filter { it.isDefault && it.defaultLocked }.map { it.version })
            assertEquals(setOf("fixture", "exact"), loaded.variants.first().contents.first().tags)
            assertEquals(fieldCount, loaded.metadata.fields.size)
            assertEquals(3, loaded.metadata.originalIds.size)
            val statements = GameLoadStatementInspector.statements.toList()
            val joins = statements.map { Regex("\\bjoin\\b", RegexOption.IGNORE_CASE).findAll(it).count() }
            println("GAME_LOAD_SHAPE fields=$fieldCount mode=$mode durationMs=$duration selects=${statements.size} maxJoins=${joins.maxOrNull()} totalJoins=${joins.sum()}")
            println("GAME_LOAD_FIRST_SQL mode=$mode ${statements.firstOrNull()}")
            // Replay only the first generated SQL with a synthetic ID. Prepare and
            // execution are separate observations; neither is full-scan throughput.
            entityManager.unwrap(org.hibernate.Session::class.java).doWork { connection ->
                val prepareStarted = System.nanoTime()
                connection.prepareStatement(statements.first()).use { query ->
                    val prepareMs = (System.nanoTime() - prepareStarted) / 1_000_000.0
                    query.setLong(1, id)
                    val executeStarted = System.nanoTime()
                    var rows = 0
                    query.executeQuery().use { results -> while (results.next()) rows++ }
                    val executeMs = (System.nanoTime() - executeStarted) / 1_000_000.0
                    println("GAME_LOAD_REPLAY fields=$fieldCount mode=$mode rows=$rows prepareMs=$prepareMs executeMs=$executeMs")
                }
            }
        }
    }
}
