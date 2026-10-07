package org.gameyfin.app.libraries

import org.flywaydb.core.Flyway
import org.gameyfin.tools.OfflineH2Rehearsal
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.*

class OfflineBackupRehearsalTest {
    private val image = "synthetic/gameyfin@sha256:" + "a".repeat(64)
    @Test
    fun `offline migrated database and data restore preserve counts and source bytes`(@TempDir root: Path) {
        val db = migratedDatabase(root)
        val data = Files.createDirectory(root.resolve("data"))
        Files.createDirectories(data.resolve("covers/empty"))
        val payload = Files.writeString(data.resolve("covers/fixture.bin"), "synthetic cover")
        val mv = db.resolve("fixture.mv.db")
        val original = Files.readAllBytes(mv)
        val originalModified = Files.getLastModifiedTime(mv)
        val backup = root.resolve("backup")
        val restore = root.resolve("restore")

        val evidence = OfflineH2Rehearsal.rehearse(db, data, backup, restore, "fixture",
            image, "fixture", "test-only")

        assertEquals("1", evidence.getProperty("count.LIBRARY"))
        assertEquals("2", evidence.getProperty("count.GAME"))
        assertEquals("3", evidence.getProperty("count.GAME_VARIANT"))
        assertEquals("4", evidence.getProperty("count.VARIANT_CONTENT"))
        assertTrue(Instant.parse(evidence.getProperty("rehearsedAt")) <= Instant.now())
        assertTrue(evidence.getProperty("restoreDurationMillis").toLong() >= 0)
        assertContentEquals(original, Files.readAllBytes(mv))
        assertEquals(originalModified, Files.getLastModifiedTime(mv))
        assertEquals("synthetic cover", Files.readString(payload))
        assertEquals("synthetic cover", Files.readString(restore.resolve("data/covers/fixture.bin")))
        assertTrue(Files.isDirectory(restore.resolve("data/covers/empty")))
        val manifest = Files.readString(backup.resolve("rehearsal.properties"))
        assertFalse(manifest.contains("test-only"))
    }

    @Test
    fun `active H2 database is refused before backup creation`(@TempDir root: Path) {
        val db = migratedDatabase(root)
        val data = Files.createDirectory(root.resolve("data"))
        DriverManager.getConnection(url(db), "fixture", "test-only").use {
            assertFails { OfflineH2Rehearsal.rehearse(db, data, root.resolve("backup"), root.resolve("restore"),
                "fixture", image, "fixture", "test-only") }
        }
        assertFalse(Files.exists(root.resolve("backup")))
    }

    @Test
    fun `overlapping roots and existing destinations are rejected`(@TempDir root: Path) {
        val db = migratedDatabase(root)
        val data = Files.createDirectory(root.resolve("data"))
        assertFailsWith<IllegalArgumentException> {
            OfflineH2Rehearsal.rehearse(db, data, data.resolve("backup"), root.resolve("restore"),
                "fixture", image, "fixture", "test-only")
        }
        val existing = Files.createDirectory(root.resolve("backup"))
        assertFailsWith<IllegalArgumentException> {
            OfflineH2Rehearsal.rehearse(db, data, existing, root.resolve("restore"),
                "fixture", image, "fixture", "test-only")
        }
        assertFalse(Files.exists(root.resolve("restore")))
    }

    private fun url(db: Path) = "jdbc:h2:file:${db.resolve("fixture").toAbsolutePath().toString().replace('\\', '/')}"

    private fun migratedDatabase(root: Path): Path {
        val db = Files.createDirectory(root.resolve("db"))
        Flyway.configure().dataSource(url(db), "fixture", "test-only")
            .locations("classpath:db/migration").load().migrate()
        DriverManager.getConnection(url(db), "fixture", "test-only").use { connection ->
            connection.createStatement().use { sql ->
                sql.execute("INSERT INTO LIBRARY (ID, CREATED_AT, UPDATED_AT, NAME) VALUES (1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'Fixture')")
                for (id in 1..2) sql.execute("INSERT INTO GAME (ID, CREATED_AT, UPDATED_AT, TITLE, PATH, LIBRARY_ID) VALUES ($id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'Fixture $id', '/synthetic/game$id', 1)")
                for (id in 1..3) sql.execute("INSERT INTO GAME_VARIANT (ID, IS_DEFAULT, IS_LATEST_FOR_VARIANT, NAME, PATH, VERSION, GAME_ID) VALUES ($id, TRUE, TRUE, 'Normal', '/synthetic/variant$id', '$id', 1)")
                for (id in 1..4) sql.execute("INSERT INTO VARIANT_CONTENT (ID, DEFAULT_SELECTED, NAME, PATH, REQUIRED, TYPE, VARIANT_ID) VALUES ($id, TRUE, 'Content $id', '/synthetic/content$id', TRUE, 'BASE', 1)")
            }
        }
        return db
    }
}
