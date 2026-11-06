package org.gameyfin.app.games.variants

import io.mockk.mockk
import jakarta.persistence.EntityManager
import org.gameyfin.app.games.repositories.GameRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest(properties = ["spring.jpa.show-sql=false", "spring.jpa.hibernate.ddl-auto=validate"])
@Import(LearnedBaseSplitService::class, LearnedBaseSplitServiceTest.Dependencies::class)
class LearnedBaseSplitServiceTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val em: EntityManager,
    private val games: GameRepository,
    private val service: LearnedBaseSplitService
) {
    @TestConfiguration(proxyBeanMethods = false)
    class Dependencies {
        @Bean fun mapper() = JsonMapper.builder().build()
        @Bean fun grouping(games: GameRepository) = GameVariantGroupingService(games, mockk(), mockk(), mockk())
    }

    @TempDir lateinit var directory: Path

    @Test fun `learns old saves then asks after six and only automates after opt in`() {
        library()
        game(1, saved = true)
        game(2, saved = true)
        game(3)
        assertNull(service.review(3).preview)
        game(4, saved = true)
        val first = assertNotNull(service.review(3).preview)
        assertEquals(3, first.confirmations)
        assertEquals("1.0.5", first.version)
        assertEquals(listOf("Game.3.v1.0.5-OFME.rar", "Fix Repair"), first.items.map { it.sourceName })
        assertEquals("Multiplayer", first.items.last().name)
        assertTrue(first.items.last().defaultSelected)
        service.applyAutomatically(games.findById(3).orElseThrow())
        assertEquals(1, games.findById(3).orElseThrow().variants.single().contents.size)
        approve(3)
        game(5)
        approve(5)
        game(6)
        approve(6)
        val offer = assertNotNull(service.review(6).rule)
        assertEquals(6, offer.confirmations)
        assertTrue(offer.offer)
        assertFalse(service.setPreference(offer.id, "later").offer)
        for (id in 7L..9L) {
            game(id)
            approve(id)
        }
        assertEquals(9, service.review(9).rule?.confirmations)
        assertTrue(service.setPreference(offer.id, "mute").muted)
        assertNull(service.review(9).rule)
        assertTrue(service.setPreference(offer.id, "ask").offer)
        assertTrue(service.setPreference(offer.id, "enable").automatic)
        val root = game(10)
        val imported = games.findById(10).orElseThrow()
        service.applyAutomatically(imported)
        em.flush()
        em.clear()
        val variant = games.findById(10).orElseThrow().variants.single()
        assertEquals("1.0.5", variant.version)
        assertFalse(variant.scanManaged)
        assertEquals(setOf("BASE", "EXTRA"), variant.contents.map { it.type.name }.toSet())
        assertEquals(setOf(4L, 5L), variant.contents.map { it.fileSize }.toSet())
        assertEquals("base", Files.readString(root.resolve("Game.10.v1.0.5-OFME.rar")))
        assertEquals("extra", Files.readString(root.resolve("Fix Repair/fix.txt")))
        assertNull(service.review(10).preview)

        val other = game(11)
        Files.createDirectory(other.resolve("Unexpected"))
        service.applyAutomatically(games.findById(11).orElseThrow())
        assertEquals(1, games.findById(11).orElseThrow().variants.single().contents.size)
    }

    @Test fun `decline resets the streak and a stale layout cannot be applied`() {
        library()
        for (id in 1L..3L) game(id, saved = true)
        val root = game(4)
        val preview = assertNotNull(service.review(4).preview)
        val extra = Files.createDirectory(root.resolve("Unrecognized"))
        assertFailsWith<IllegalArgumentException> { service.decide(4, preview.revision, true) }
        Files.delete(extra)
        assertNull(service.decide(4, preview.revision, false))
        assertNull(service.review(4).preview)
        game(5)
        assertEquals(0, service.review(5).preview?.confirmations)
    }

    private fun approve(id: Long) {
        val preview = assertNotNull(service.review(id).preview)
        assertNotNull(service.decide(id, preview.revision, true))
        em.flush()
        em.clear()
    }

    private fun library() {
        jdbc.update("INSERT INTO LIBRARY(ID, CREATED_AT, UPDATED_AT, NAME, STORAGE_MODE) VALUES (99, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'Games', 'DIRECT')")
        jdbc.update("INSERT INTO DIRECTORY_MAPPING(ID, INTERNAL_PATH) VALUES (99, ?)", directory.toRealPath().toString())
        jdbc.update("INSERT INTO LIBRARY_DIRECTORIES(LIBRARY_ID, DIRECTORIES_ID) VALUES (99, 99)")
    }

    private fun game(id: Long, saved: Boolean = false): Path {
        val root = Files.createDirectory(directory.resolve("Game $id")).toRealPath()
        val archive = Files.writeString(root.resolve("Game.$id.v1.0.5-OFME.rar"), "base")
        val extra = Files.createDirectory(root.resolve("Fix Repair"))
        Files.writeString(extra.resolve("fix.txt"), "extra")
        val variant = 90000 + id
        jdbc.update(
            """INSERT INTO GAME(ID, CREATED_AT, UPDATED_AT, TITLE, PATH, FILE_SIZE, LIBRARY_ID, DOWNLOAD_COUNT, MATCH_CONFIRMED)
                VALUES (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?, 9, 99, 0, TRUE)""",
            id, "Game $id", root.toString()
        )
        jdbc.update(
            """INSERT INTO GAME_VARIANT(ID, NAME, VERSION, PATH, FILE_SIZE, GAME_ID, IS_DEFAULT, DEFAULT_LOCKED,
                IS_LATEST_FOR_VARIANT, SCAN_MANAGED, LINK_STATUS, STEAM_APP_ID_MANUAL_OVERRIDE)
                VALUES (?, 'Normal', '0', ?, 9, ?, TRUE, FALSE, TRUE, ?, 'DIRECT', FALSE)""",
            variant, root.toString(), id, !saved
        )
        content(variant * 2, variant, if (saved) archive else root, if (saved) archive.fileName.toString() else "Base game", "BASE", true)
        if (saved) content(variant * 2 + 1, variant, extra, "Multiplayer", "EXTRA", false)
        return root
    }

    private fun content(id: Long, variant: Long, path: Path, name: String, type: String, required: Boolean) {
        jdbc.update(
            """INSERT INTO VARIANT_CONTENT(ID, NAME, PATH, FILE_SIZE, TYPE, REQUIRED, DEFAULT_SELECTED, VARIANT_ID)
                VALUES (?, ?, ?, 9, ?, ?, TRUE, ?)""",
            id, name, path.toString(), type, required, variant
        )
        jdbc.update("INSERT INTO VARIANT_CONTENT_PATHS(VARIANT_CONTENT_ID, PATH_INDEX, PATH) VALUES (?, 0, ?)", id, path.toString())
    }
}
