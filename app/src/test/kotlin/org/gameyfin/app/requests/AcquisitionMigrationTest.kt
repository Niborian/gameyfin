package org.gameyfin.app.requests

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.*

class AcquisitionMigrationTest {
    @Test fun `actual migrations enforce unique torrent ownership and preserve audit on request deletion`() {
        val url = "jdbc:h2:mem:acquisition-${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
        // Once checkpoint migrations 35/36 are on main, this also proves upgrade from those actual scripts.
        val baseline = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load()
            .info().pending().filter { it.version < org.flywaydb.core.api.MigrationVersion.fromVersion("2.4.3.37") }.maxBy { it.version }.version
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").target(baseline).load().migrate()
        val upgrade = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").target("2.4.3.37").load().migrate()
        assertEquals(1, upgrade.migrationsExecuted)
        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("INSERT INTO GAME_REQUEST (ID,TITLE,RELEASE,STATUS,CREATED_AT,UPDATED_AT) VALUES (7,'Open source fixture',CURRENT_TIMESTAMP,'QUEUED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
                statement.execute("INSERT INTO GAME_REQUEST_CANDIDATE (ID,GAME_REQUEST_ID,PROVIDER_LABEL,DISPLAY_NAME,EXTERNAL_REFERENCE,RECORDED_AT,RECORDED_BY,SELECTED) VALUES (11,7,'fixture','fixture','fixture',CURRENT_TIMESTAMP,'fixture-admin',TRUE)")
                statement.execute("INSERT INTO ACQUISITION_TRANSFER (ID,CANDIDATE_ID,INDEXER_ID,TORRENT_HASH,MAGNET,STATE,VERSION,UPDATED_AT) VALUES (1,11,'2','${"a".repeat(40)}','fixture','ACTIVE',0,CURRENT_TIMESTAMP)")
                statement.execute("INSERT INTO ACQUISITION_TRANSFER_AUDIT (ID,TRANSFER_ID,OPERATION,ACTOR,REASON,RECORDED_AT) VALUES (1,1,'SUBMIT_CONFIRMED','fixture-admin','Authorized open source fixture',CURRENT_TIMESTAMP)")
                assertFailsWith<SQLException> { statement.execute("DELETE FROM GAME_REQUEST WHERE ID=7") }
                statement.execute("INSERT INTO GAME_REQUEST_CANDIDATE (ID,GAME_REQUEST_ID,PROVIDER_LABEL,DISPLAY_NAME,EXTERNAL_REFERENCE,RECORDED_AT,RECORDED_BY,SELECTED) VALUES (12,7,'fixture','fixture','fixture',CURRENT_TIMESTAMP,'fixture-admin',FALSE)")
                assertFailsWith<SQLException> { statement.execute("INSERT INTO ACQUISITION_TRANSFER (ID,CANDIDATE_ID,INDEXER_ID,TORRENT_HASH,MAGNET,STATE,VERSION,UPDATED_AT) VALUES (2,12,'2','${"a".repeat(40)}','fixture','REVIEW',0,CURRENT_TIMESTAMP)") }
                statement.executeQuery("SELECT COUNT(*) FROM ACQUISITION_TRANSFER_AUDIT").use { rows -> rows.next(); assertEquals(1, rows.getInt(1)) }
            }
        }
    }
}
