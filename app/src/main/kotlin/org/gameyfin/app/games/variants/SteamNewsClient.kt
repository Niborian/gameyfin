package org.gameyfin.app.games.variants

import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.Instant

data class SteamNewsEvent(
    val id: String,
    val title: String,
    val url: String,
    val publishedAt: Instant,
    val tags: Set<String>,
    val contents: String
)

/** Small adapter for Steam's documented public ISteamNews endpoint. */
@Component
class SteamNewsClient(webClientBuilder: WebClient.Builder) {
    private val client = webClientBuilder.baseUrl("https://api.steampowered.com").build()

    fun latest(appId: String): List<SteamNewsEvent> {
        require(appId.matches(Regex("[1-9]\\d*"))) { "Steam app ID must be a positive decimal value" }
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
            .block()
            ?: throw IllegalStateException("Steam returned no news response")

        return response.appnews?.newsitems.orEmpty().map { item ->
            SteamNewsEvent(
                id = item.gid,
                title = item.title,
                url = item.url,
                publishedAt = Instant.ofEpochSecond(item.date),
                tags = item.tags.toSet(),
                contents = item.contents
            )
        }
    }

    private data class SteamNewsResponse(val appnews: SteamNewsAppNews? = null)
    private data class SteamNewsAppNews(val newsitems: List<SteamNewsItem> = emptyList())
    private data class SteamNewsItem(
        val gid: String,
        val title: String,
        val url: String,
        val date: Long,
        val tags: List<String> = emptyList(),
        val contents: String = ""
    )
}
