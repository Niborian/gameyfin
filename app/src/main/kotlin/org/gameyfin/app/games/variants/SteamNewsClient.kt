package org.gameyfin.app.games.variants

import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.Instant
import java.time.Duration

data class SteamNewsEvent(
    val id: String,
    val title: String,
    val url: String,
    val publishedAt: Instant,
    val tags: Set<String>,
    val contents: String,
    val feedName: String = "",
    val feedLabel: String = ""
)

/** Small adapter for Steam's documented public ISteamNews endpoint. */
@Component
class SteamNewsClient {
    private val client = WebClient.builder().baseUrl("https://api.steampowered.com")
        .codecs { it.defaultCodecs().maxInMemorySize(512 * 1024) }.build()

    fun latest(appId: String): List<SteamNewsEvent> {
        require(appId.matches(Regex("[1-9][0-9]{0,19}"))) { "Steam app ID must be a bounded positive decimal value" }
        val response = client.get()
            .uri { uri ->
                uri.path("/ISteamNews/GetNewsForApp/v2/")
                    .queryParam("appid", appId)
                    .queryParam("count", 20)
                    .queryParam("maxlength", 12000)
                    .build()
            }
            .retrieve()
            .bodyToMono(SteamNewsResponse::class.java)
            .timeout(Duration.ofSeconds(12))
            .block(Duration.ofSeconds(13))
            ?: throw IllegalStateException("Steam returned no news response")

        val news = response.appnews ?: throw IllegalStateException("Steam returned no app news")
        check(news.appid.toString() == appId) { "Steam returned a different app ID" }
        return news.newsitems.take(20).map { item ->
            SteamNewsEvent(
                id = item.gid,
                title = item.title,
                url = item.url,
                publishedAt = Instant.ofEpochSecond(item.date),
                tags = item.tags.toSet(),
                contents = item.contents,
                feedName = item.feedname,
                feedLabel = item.feedlabel
            )
        }
    }

    private data class SteamNewsResponse(val appnews: SteamNewsAppNews? = null)
    private data class SteamNewsAppNews(val appid: Long, val newsitems: List<SteamNewsItem> = emptyList())
    private data class SteamNewsItem(
        val gid: String,
        val title: String,
        val url: String,
        val date: Long,
        val tags: List<String> = emptyList(),
        val contents: String = "",
        val feedname: String = "",
        val feedlabel: String = ""
    )
}
