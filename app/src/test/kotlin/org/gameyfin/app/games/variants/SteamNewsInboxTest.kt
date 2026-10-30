package org.gameyfin.app.games.variants

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.gameyfin.app.config.ConfigProperties
import org.gameyfin.app.config.ConfigService
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class SteamNewsInboxTest {
    private val now = Instant.parse("2026-10-30T12:00:00Z")
    private val classifier = SteamNewsContentUpdateClassifier()
    private fun database(): JdbcTemplate {
        val jdbc = JdbcTemplate(DriverManagerDataSource("jdbc:h2:mem:news-${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", ""))
        jdbc.execute("CREATE TABLE GAME(ID BIGINT PRIMARY KEY,TITLE VARCHAR(255))")
        jdbc.execute("CREATE TABLE GAME_VARIANT(ID BIGINT PRIMARY KEY,GAME_ID BIGINT,STEAM_APP_ID VARCHAR(20),STEAM_APP_ID_VERIFIED_AT TIMESTAMP WITH TIME ZONE)")
        val migration = requireNotNull(javaClass.getResource("/db/migration/V2.4.8.3__Add_steam_news_inbox.sql")).readText()
        migration.split(';').filter { it.isNotBlank() }.forEach(jdbc::execute)
        jdbc.update("INSERT INTO GAME VALUES(1,'Synthetic game')")
        jdbc.update("INSERT INTO GAME_VARIANT VALUES(1,1,'244850',CURRENT_TIMESTAMP),(2,1,'244850',CURRENT_TIMESTAMP),(3,1,'999',NULL)")
        return jdbc
    }
    private fun event(id: String = "123", title: String = "Update 1.5") = SteamNewsEvent(id, title,
        "https://store.steampowered.com/news/app/244850/view/$id", now.minusSeconds(100), setOf("patchnotes"), "Patch notes")

    @Test fun `duplicates share one check and durable ignore survives refresh while later events appear`() {
        val jdbc = database()
        val store = SteamNewsInboxStore(jdbc)
        assertEquals(listOf("244850"), store.due(now))
        store.record("244850", listOf(event(), event()), now, classifier)
        assertEquals(1, store.inbox(now).notices.size)
        store.review("244850", "123", true, now)
        SteamNewsInboxStore(jdbc).record("244850", listOf(event(), event("124")), now.plusSeconds(1), classifier)
        assertEquals(listOf("124"), store.inbox(now.plusSeconds(2)).notices.map { it.eventId })
        assertTrue(store.due(now.plusSeconds(2)).isEmpty())
        assertEquals(listOf("244850"), store.due(now.plus(Duration.ofDays(2))))
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM GAME_VARIANT", Int::class.java))
    }

    @Test fun `failure preserves evidence and last success while snooze expires and unsafe links are refused`() {
        val jdbc = database()
        val store = SteamNewsInboxStore(jdbc)
        store.record("244850", listOf(event(), event("124").copy(url="https://evil.invalid/update")), now, classifier)
        store.failed("244850", now.plusSeconds(30))
        val inbox = store.inbox(now.plusSeconds(31))
        assertEquals(1, inbox.notices.size)
        assertTrue(inbox.checks.single().failed)
        assertEquals(now, inbox.checks.single().succeededAt)
        store.review("244850", "123", false, now)
        assertTrue(store.inbox(now.plus(Duration.ofDays(6))).notices.isEmpty())
        assertEquals(1, store.inbox(now.plus(Duration.ofDays(8))).notices.size)
        jdbc.update("UPDATE GAME_VARIANT SET STEAM_APP_ID_VERIFIED_AT=NULL")
        assertTrue(store.inbox(now.plus(Duration.ofDays(8))).notices.isEmpty())
        assertTrue(store.due(now.plus(Duration.ofDays(8))).isEmpty())
    }

    @Test fun `disabled monitor never reaches Steam and provider failures do not skip the next app`() {
        val config = mockk<ConfigService>()
        val store = mockk<SteamNewsInboxStore>(relaxed=true)
        val client = mockk<SteamNewsClient>()
        every { config.get(ConfigProperties.Requests.SteamUpdates.Enabled) } returns false
        val monitor = SteamNewsMonitor(config, store, client, classifier)
        monitor.poll()
        verify(exactly=0) { store.due(any()) }
        every { config.get(ConfigProperties.Requests.SteamUpdates.Enabled) } returns true
        every { store.due(any()) } returns listOf("244850", "440")
        every { client.latest("244850") } throws IllegalStateException("synthetic provider failure")
        every { client.latest("440") } returns emptyList()
        monitor.poll()
        verify(exactly=1) { store.failed("244850", any()) }
        verify(exactly=1) { store.record("440", emptyList(), any(), classifier) }
    }

    @Test fun `bounded batches prioritize unchecked apps before retrying earlier apps`() {
        val jdbc = database()
        val store = SteamNewsInboxStore(jdbc)
        (10..34).forEach { jdbc.update("INSERT INTO GAME_VARIANT VALUES(?,1,?,CURRENT_TIMESTAMP)", it, it.toString()) }
        val first = store.due(now)
        assertEquals(10, first.size)
        first.forEach { store.record(it, emptyList(), now, classifier) }
        val later = store.due(now.plus(Duration.ofDays(2)))
        assertEquals(10, later.size)
        assertTrue(first.intersect(later.toSet()).isEmpty(), "A large catalogue must not starve unchecked app IDs")
    }
}
