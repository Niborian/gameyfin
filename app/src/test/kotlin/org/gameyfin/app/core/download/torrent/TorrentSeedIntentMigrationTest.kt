package org.gameyfin.app.core.download.torrent

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class TorrentSeedIntentMigrationTest {
    @Test fun `pending metadata rejects null size after Flyway connection closes`() {
        val url = "jdbc:h2:mem:seed-migration-${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
            .target("2.4.3.38").load().migrate()
        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                val prefix = "INSERT INTO TORRENT_SEED_INTENT (ID,CLIENT_ID,SNAPSHOT_ID,MANIFEST_DIGEST,CREATED_AT,STATE,OPERATION_TOKEN,METADATA_TASK_ID,METADATA_DIGEST,METADATA_BYTES,UPDATED_AT,VERSION) VALUES ('${UUID.randomUUID()}','${UUID.randomUUID()}','${UUID.randomUUID()}','${"a".repeat(64)}',CURRENT_TIMESTAMP,'METADATA_PENDING_VALIDATION','${UUID.randomUUID()}','owned-task','${"b".repeat(64)}',"
                assertFailsWith<SQLException> { statement.execute(prefix + "NULL,CURRENT_TIMESTAMP,0)") }
                statement.execute(prefix + "100,CURRENT_TIMESTAMP,0)")
                statement.executeQuery("SELECT COUNT(*) FROM TORRENT_SEED_INTENT").use { rows ->
                    rows.next()
                    assertEquals(1, rows.getInt(1))
                }
            }
        }
    }
}
