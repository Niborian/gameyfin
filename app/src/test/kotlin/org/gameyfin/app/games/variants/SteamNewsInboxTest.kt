package org.gameyfin.app.games.variants

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.gameyfin.app.config.ConfigProperties
import org.gameyfin.app.config.ConfigService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SteamNewsInboxTest {
    private val now = Instant.parse("2026-10-30T12:00:00Z")
    private val classifier = SteamNewsContentUpdateClassifier()
    private fun database(url: String = "jdbc:h2:mem:news-${UUID.randomUUID()};DB_CLOSE_DELAY=-1"): JdbcTemplate {
        val jdbc = JdbcTemplate(DriverManagerDataSource(url, "sa", ""))
        jdbc.execute("CREATE TABLE GAME(ID BIGINT PRIMARY KEY,TITLE VARCHAR(255))")
        jdbc.execute("CREATE TABLE GAME_VARIANT(ID BIGINT PRIMARY KEY,GAME_ID BIGINT,STEAM_APP_ID VARCHAR(20),STEAM_APP_ID_VERIFIED_AT TIMESTAMP WITH TIME ZONE)")
        val migration = requireNotNull(javaClass.getResource("/db/migration/V2.4.8.3__Add_steam_news_inbox.sql")).readText()
        migration.split(';').filter { it.isNotBlank() }.forEach(jdbc::execute)
        jdbc.update("INSERT INTO GAME VALUES(1,'Synthetic game')")
        jdbc.update("INSERT INTO GAME_VARIANT(ID,GAME_ID,STEAM_APP_ID,STEAM_APP_ID_VERIFIED_AT) VALUES(1,1,'244850',CURRENT_TIMESTAMP),(2,1,'244850',CURRENT_TIMESTAMP),(3,1,'999',NULL)")
        return jdbc
    }
    private fun event(id: String = "123", title: String = "Update 1.5") = SteamNewsEvent(id, title,
        "https://store.steampowered.com/news/app/244850/view/$id", now.minusSeconds(100), setOf("patchnotes"), "Patch notes")

    @Test @Timeout(15)
    fun `scheduled dispatch yields while a serial provider request is in flight`() {
        val config = mockk<ConfigService>()
        val store = mockk<SteamNewsInboxStore>(relaxed=true)
        val client = mockk<SteamNewsClient>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val recorded = CountDownLatch(1)
        every { config.get(ConfigProperties.Requests.SteamUpdates.Enabled) } returns true
        every { store.due(any()) } returns listOf("244850")
        every { client.latest("244850") } answers { started.countDown(); release.await(); emptyList() }
        every { store.record("244850", emptyList(), any(), classifier) } answers { recorded.countDown() }
        val monitor = SteamNewsMonitor(config, store, client, classifier)
        try {
            monitor.scheduledPoll() // Must return while the provider is still waiting.
            assertTrue(started.await(3, TimeUnit.SECONDS))
            assertEquals(1L, release.count)
            monitor.scheduledPoll()
            verify(exactly=1) { client.latest("244850") }
            release.countDown()
            assertTrue(recorded.await(3, TimeUnit.SECONDS))
        } finally { release.countDown(); monitor.shutdown() }
    }

    @Test fun `file backed H2 retains review decisions and failure evidence after shutdown and reopen`(@TempDir root: Path) {
        val url = "jdbc:h2:file:${root.resolve("cache").toAbsolutePath().toString().replace('\\', '/')};DB_CLOSE_ON_EXIT=FALSE"
        val jdbc = database(url)
        val store = SteamNewsInboxStore(jdbc)
        store.record("244850", listOf(event(), event("124").copy(feedName="fixture-feed", feedLabel="Synthetic feed")), now, classifier)
        store.review("244850", "123", true, now)
        store.review("244850", "124", false, now)
        store.failed("244850", now.plusSeconds(30))
        jdbc.execute("SHUTDOWN")
        val reopened = JdbcTemplate(DriverManagerDataSource(url, "sa", ""))
        val restored = SteamNewsInboxStore(reopened)
        val beforeExpiry = restored.inbox(now.plus(Duration.ofDays(6)))
        assertTrue(beforeExpiry.notices.isEmpty())
        assertTrue(beforeExpiry.checks.single().failed)
        assertEquals(now, beforeExpiry.checks.single().succeededAt)
        assertEquals(now.plusSeconds(30), beforeExpiry.checks.single().attemptedAt)
        val notice = restored.inbox(now.plus(Duration.ofDays(8))).notices.single()
        assertEquals("124", notice.eventId)
        assertEquals("fixture-feed", notice.feedName)
        assertEquals("Synthetic feed", notice.feedLabel)
        assertEquals("patchnotes", notice.tags)
        assertEquals("Steam public ISteamNews", notice.source)
        assertEquals("CONTENT_UPDATE", notice.classification)
        assertEquals("Matched Steam patchnotes tag", notice.reason)
        assertEquals(event("124").url, notice.url)
        assertEquals(now, notice.observedAt)
        assertEquals(event().publishedAt, notice.publishedAt)
        assertEquals(2, reopened.queryForObject("SELECT COUNT(*) FROM STEAM_NEWS_NOTICE", Int::class.java))
        reopened.execute("SHUTDOWN")
    }

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

    @Test fun `monitoring opt out hides evidence and stops polling without removing Steam identity`() {
        val jdbc = database()
        val store = SteamNewsInboxStore(jdbc)
        store.record("244850", listOf(event()), now, classifier)
        jdbc.update("UPDATE GAME_VARIANT SET STEAM_NEWS_WATCH_ENABLED=FALSE WHERE ID=1")
        assertEquals(1, store.inbox(now).notices.size, "The other explicitly watched variant still needs this event")
        jdbc.update("UPDATE GAME_VARIANT SET STEAM_NEWS_WATCH_ENABLED=FALSE WHERE ID=2")
        assertTrue(store.inbox(now).notices.isEmpty())
        assertTrue(store.due(now.plus(Duration.ofDays(2))).isEmpty())
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM GAME_VARIANT WHERE STEAM_APP_ID='244850' AND STEAM_APP_ID_VERIFIED_AT IS NOT NULL", Int::class.java))
        jdbc.update("UPDATE GAME_VARIANT SET STEAM_NEWS_WATCH_ENABLED=TRUE WHERE ID=1")
        assertEquals(1, store.inbox(now).notices.size)
    }

    @Test fun `official Steam news CDN evidence is preserved while lookalikes and non-news paths are refused`() {
        val store = SteamNewsInboxStore(database())
        val official = "https://steamstore-a.akamaihd.net/news/externalpost/steam_community_announcements/123"
        val rejected = listOf(
            "https://steamstore-a.akamaihd.net.evil.invalid/news/externalpost/123",
            "https://arbitrary.akamaihd.net/news/externalpost/123",
            "https://steamstore-a.akamaihd.net/assets/123",
            "http://steamstore-a.akamaihd.net/news/externalpost/123",
            "https://steamstore-a.akamaihd.net:444/news/externalpost/123",
            "https://user:secret@steamstore-a.akamaihd.net/news/externalpost/123"
        )
        store.record("244850", listOf(event("124").copy(url=official)) + rejected.mapIndexed { i, url -> event((200+i).toString()).copy(url=url) }, now, classifier)
        val notice = store.inbox(now).notices.single()
        assertEquals("124", notice.eventId)
        assertEquals(official, notice.url)
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
        (10..34).forEach { jdbc.update("INSERT INTO GAME_VARIANT(ID,GAME_ID,STEAM_APP_ID,STEAM_APP_ID_VERIFIED_AT) VALUES(?,1,?,CURRENT_TIMESTAMP)", it, it.toString()) }
        val first = store.due(now)
        assertEquals(10, first.size)
        first.forEach { store.record(it, emptyList(), now, classifier) }
        val later = store.due(now.plus(Duration.ofDays(2)))
        assertEquals(10, later.size)
        assertTrue(first.intersect(later.toSet()).isEmpty(), "A large catalogue must not starve unchecked app IDs")
    }
}
