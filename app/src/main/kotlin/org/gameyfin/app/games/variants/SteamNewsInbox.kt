package org.gameyfin.app.games.variants

import com.vaadin.hilla.Endpoint
import jakarta.annotation.security.RolesAllowed
import jakarta.annotation.PreDestroy
import org.gameyfin.app.config.ConfigProperties
import org.gameyfin.app.config.ConfigService
import org.gameyfin.app.core.Role
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class SteamNewsNoticeDto(
    val appId: String, val eventId: String, val gameId: Long, val gameTitle: String,
    val title: String, val url: String, val publishedAt: Instant, val observedAt: Instant,
    val classification: String, val reason: String, val source: String,
    val feedName: String, val feedLabel: String, val tags: String
)
data class SteamNewsCheckDto(val appId: String, val attemptedAt: Instant, val succeededAt: Instant?, val failed: Boolean)
data class SteamNewsInboxDto(val notices: List<SteamNewsNoticeDto>, val checks: List<SteamNewsCheckDto>)

/** Persists public evidence separately from local versions and download approval. */
@Service
class SteamNewsInboxStore(private val jdbc: JdbcTemplate) {
    fun due(now: Instant): List<String> = jdbc.queryForList("""
        SELECT APP_ID FROM (
        SELECT V.STEAM_APP_ID AS APP_ID,MIN(C.ATTEMPTED_AT) AS LAST_ATTEMPT
        FROM GAME_VARIANT V LEFT JOIN STEAM_NEWS_CHECK C ON C.APP_ID=V.STEAM_APP_ID
        WHERE V.STEAM_APP_ID IS NOT NULL AND V.STEAM_APP_ID_VERIFIED_AT IS NOT NULL
          AND V.STEAM_NEWS_WATCH_ENABLED=TRUE
          AND LENGTH(V.STEAM_APP_ID)<=20 AND REGEXP_LIKE(V.STEAM_APP_ID,'^[1-9][0-9]*$')
          AND (C.ATTEMPTED_AT IS NULL OR C.ATTEMPTED_AT<=?)
        GROUP BY V.STEAM_APP_ID)
        ORDER BY LAST_ATTEMPT NULLS FIRST,APP_ID FETCH FIRST 10 ROWS ONLY
    """.trimIndent(), String::class.java, Timestamp.from(now.minus(Duration.ofDays(1)))).filterNotNull()

    @Transactional
    fun record(appId: String, events: List<SteamNewsEvent>, now: Instant, classifier: SteamNewsContentUpdateClassifier) {
        events.take(20).forEach { event ->
            val classification = classifier.classify(event.title, event.tags, event.contents)
            if (classification.classification == SteamNewsClassification.NOT_CONTENT_UPDATE) return@forEach
            // Public links are evidence only; never fetch links or render provider HTML.
            val uri = runCatching { URI(event.url) }.getOrNull() ?: return@forEach
            if (uri.scheme != "https" || uri.userInfo != null || uri.host !in setOf("store.steampowered.com", "steamcommunity.com", "www.steamcommunity.com")) return@forEach
            if (!event.id.matches(Regex("[0-9]{1,32}")) || event.title.isBlank() || event.url.length > 2048) return@forEach
            jdbc.update("""
                INSERT INTO STEAM_NEWS_NOTICE(APP_ID,EVENT_ID,TITLE,EVENT_URL,PUBLISHED_AT,OBSERVED_AT,CLASSIFICATION,REASON,SOURCE,FEED_NAME,FEED_LABEL,TAGS)
                SELECT ?,?,?,?,?,?,?,?,?,?,?,? WHERE NOT EXISTS
                  (SELECT 1 FROM STEAM_NEWS_NOTICE WHERE APP_ID=? AND EVENT_ID=?)
            """.trimIndent(), appId, event.id, event.title.take(512), event.url, Timestamp.from(event.publishedAt),
                Timestamp.from(now), classification.classification.name, classification.reason.take(512),
                "Steam public ISteamNews", event.feedName.take(256), event.feedLabel.take(256), event.tags.sorted().joinToString(", ").take(2048), appId, event.id)
        }
        jdbc.update("""
            MERGE INTO STEAM_NEWS_CHECK(APP_ID,ATTEMPTED_AT,SUCCEEDED_AT,FAILED) KEY(APP_ID) VALUES(?,?,?,FALSE)
        """.trimIndent(), appId, Timestamp.from(now), Timestamp.from(now))
        // Bounded history per app. Review decisions survive refreshes for retained event IDs.
        jdbc.update("""
            DELETE FROM STEAM_NEWS_NOTICE WHERE APP_ID=? AND EVENT_ID NOT IN
              (SELECT EVENT_ID FROM STEAM_NEWS_NOTICE WHERE APP_ID=? ORDER BY PUBLISHED_AT DESC,EVENT_ID DESC FETCH FIRST 100 ROWS ONLY)
        """.trimIndent(), appId, appId)
    }

    @Transactional
    fun failed(appId: String, now: Instant) {
        val updated = jdbc.update("UPDATE STEAM_NEWS_CHECK SET ATTEMPTED_AT=?,FAILED=TRUE WHERE APP_ID=?", Timestamp.from(now), appId)
        if (updated == 0) jdbc.update("INSERT INTO STEAM_NEWS_CHECK(APP_ID,ATTEMPTED_AT,FAILED) VALUES(?,?,TRUE)", appId, Timestamp.from(now))
    }

    fun inbox(now: Instant): SteamNewsInboxDto {
        val notices = jdbc.query("""
            SELECT N.*,G.ID AS GAME_ID,G.TITLE AS GAME_TITLE FROM STEAM_NEWS_NOTICE N
            JOIN GAME G ON EXISTS (SELECT 1 FROM GAME_VARIANT V WHERE V.GAME_ID=G.ID
                AND V.STEAM_APP_ID=N.APP_ID AND V.STEAM_APP_ID_VERIFIED_AT IS NOT NULL AND V.STEAM_NEWS_WATCH_ENABLED=TRUE)
            WHERE N.IGNORED=FALSE AND (N.SNOOZED_UNTIL IS NULL OR N.SNOOZED_UNTIL<=?)
            ORDER BY N.PUBLISHED_AT DESC,N.APP_ID,N.EVENT_ID,G.ID FETCH FIRST 100 ROWS ONLY
        """.trimIndent(), { rs, _ -> SteamNewsNoticeDto(rs.getString("APP_ID"), rs.getString("EVENT_ID"),
            rs.getLong("GAME_ID"), rs.getString("GAME_TITLE"), rs.getString("TITLE"), rs.getString("EVENT_URL"),
            rs.getTimestamp("PUBLISHED_AT").toInstant(), rs.getTimestamp("OBSERVED_AT").toInstant(),
            rs.getString("CLASSIFICATION"), rs.getString("REASON"), rs.getString("SOURCE"), rs.getString("FEED_NAME"),
            rs.getString("FEED_LABEL"), rs.getString("TAGS")) }, Timestamp.from(now))
        val checks = jdbc.query("""
            SELECT C.* FROM STEAM_NEWS_CHECK C WHERE EXISTS (SELECT 1 FROM GAME_VARIANT V
              WHERE V.STEAM_APP_ID=C.APP_ID AND V.STEAM_APP_ID_VERIFIED_AT IS NOT NULL AND V.STEAM_NEWS_WATCH_ENABLED=TRUE)
            ORDER BY C.ATTEMPTED_AT DESC FETCH FIRST 100 ROWS ONLY
        """.trimIndent()) { rs, _ -> SteamNewsCheckDto(rs.getString("APP_ID"), rs.getTimestamp("ATTEMPTED_AT").toInstant(),
            rs.getTimestamp("SUCCEEDED_AT")?.toInstant(), rs.getBoolean("FAILED")) }
        return SteamNewsInboxDto(notices, checks)
    }

    fun review(appId: String, eventId: String, ignore: Boolean, now: Instant) {
        require(appId.matches(Regex("[1-9][0-9]{0,19}")) && eventId.matches(Regex("[0-9]{1,32}")))
        require(jdbc.update("UPDATE STEAM_NEWS_NOTICE SET IGNORED=?,SNOOZED_UNTIL=? WHERE APP_ID=? AND EVENT_ID=?",
            ignore, if (ignore) null else Timestamp.from(now.plus(Duration.ofDays(7))), appId, eventId) == 1) { "Notice no longer exists" }
    }
}

@Service
class SteamNewsMonitor(private val config: ConfigService, private val store: SteamNewsInboxStore,
    private val client: SteamNewsClient, private val classifier: SteamNewsContentUpdateClassifier) {
    private val running = AtomicBoolean(false)
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, "steam-news-monitor").apply { isDaemon = true } })

    // One small serial batch per hour, at most one attempt per app/day. Never scans files or acquires content.
    @Scheduled(initialDelay = 300_000, fixedDelay = 3_600_000)
    fun scheduledPoll() {
        if (config.get(ConfigProperties.Requests.SteamUpdates.Enabled) != true) return
        // Never occupy Spring's shared scheduler while waiting for Steam. No queued batches or idle worker thread.
        try { worker.execute { poll() } } catch (_: RejectedExecutionException) { /* Existing work or shutdown: skip. */ }
    }

    @PreDestroy
    fun shutdown() { worker.shutdownNow() }

    fun poll() {
        if (config.get(ConfigProperties.Requests.SteamUpdates.Enabled) != true || !running.compareAndSet(false, true)) return
        try {
            for (appId in store.due(Instant.now())) {
                if (config.get(ConfigProperties.Requests.SteamUpdates.Enabled) != true) break
                try { store.record(appId, client.latest(appId), Instant.now(), classifier) }
                catch (failure: Exception) {
                    if (failure is InterruptedException) { Thread.currentThread().interrupt(); break }
                    store.failed(appId, Instant.now()) // Preserve last success and evidence; never log provider payloads.
                }
            }
        } finally { running.set(false) }
    }
}

@Endpoint
@RolesAllowed(Role.Names.ADMIN)
class SteamNewsInboxEndpoint(private val store: SteamNewsInboxStore) {
    fun inbox(): SteamNewsInboxDto = store.inbox(Instant.now())
    fun ignore(appId: String, eventId: String) = store.review(appId, eventId, true, Instant.now())
    fun snooze(appId: String, eventId: String) = store.review(appId, eventId, false, Instant.now())
}
