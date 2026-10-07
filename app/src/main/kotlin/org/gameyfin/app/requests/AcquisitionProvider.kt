package org.gameyfin.app.requests

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

/** Secrets are supplied externally, never exposed by an endpoint or stored in ConfigEntry. */
@ConfigurationProperties("gameyfin.acquisition")
class AcquisitionProviderSettings {
    var enabled = false
    var dedicatedClientAcknowledged = false
    var prowlarrUrl = ""
    var prowlarrApiKey = ""
    var qbittorrentUrl = ""
    var qbittorrentUsername = ""
    var qbittorrentPassword = ""
    var category = "gameyfin-acquisition"
    var managedTag = "gameyfin-managed"
    var savePath = ""

    fun requireEnabled() {
        check(enabled && dedicatedClientAcknowledged) { "Acquisition requires an explicitly enabled, dedicated isolated client" }
        check(prowlarrApiKey.isNotBlank() && qbittorrentUsername.isNotBlank() && qbittorrentPassword.isNotBlank()) { "Acquisition credentials are missing" }
        endpoint(prowlarrUrl)
        endpoint(qbittorrentUrl)
        normalizedSavePath(savePath)
    }

    fun normalizedSavePath(value: String): String {
        val path = value.replace('\\', '/').trimEnd('/')
        require(path.isNotBlank() && path != "/" && !path.matches(Regex("[A-Za-z]:")) &&
            (path.startsWith("/") || path.matches(Regex("[A-Za-z]:/.+"))) &&
            !path.any { it.code < 32 } && path.split('/').none { it.trim() in setOf(".", "..") }) {
            "An explicit absolute dedicated acquisition save path, not a filesystem root or traversal, is required"
        }
        return path
    }

    private fun endpoint(value: String) {
        val uri = URI(value)
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) { "Provider endpoints must be explicit HTTP origins without credentials or query" }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "Use a dedicated provider origin, not an arbitrary path" }
        require(uri.scheme == "https" || uri.host in setOf("localhost", "127.0.0.1", "::1", "[::1]")) { "Remote provider credentials require HTTPS" }
    }
}

@Configuration
@EnableConfigurationProperties(AcquisitionProviderSettings::class)
class AcquisitionProviderConfiguration

data class AuthorizedSearchResult(val indexerId: String, val title: String, val magnet: String, val hash: String)
data class OwnedTorrent(val hash: String, val category: String, val tags: Set<String>, val state: String = "")
class AcquisitionPreflightRefusal(cause: Exception) : IllegalStateException("Submission refused before add; fix isolated provider configuration and review again", cause)

/** No arbitrary download URLs, redirect following, grab endpoint, filesystem or client-wide mutations. */
@Service
class AcquisitionProvider(
    private val settings: AcquisitionProviderSettings,
    private val policy: AcquisitionScopePolicy,
    private val mapper: ObjectMapper,
) {
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    private val qbHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .cookieHandler(cookies).build()
    private val prowlarrHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    fun activeIndexers(): List<String> {
        settings.requireEnabled()
        val approved = policy.approvedIndexerIds().toSet()
        val response = get(settings.prowlarrUrl, "/api/v1/indexer", true)
        val nodes = mapper.readTree(response)
        require(nodes.isArray) { "Invalid indexer response" }
        return nodes.filter { it.path("enable").asBoolean(false) && it.path("id").asText() in approved }
            .map { it.path("id").asText() }.distinct()
    }

    fun search(indexerId: String, query: String): List<AuthorizedSearchResult> {
        settings.requireEnabled()
        policy.requireApprovedIndexer(indexerId)
        require(indexerId in activeIndexers()) { "Approved indexer is not active" }
        require(query.isNotBlank() && query.length <= 256)
        val nodes = mapper.readTree(get(settings.prowlarrUrl, "/api/v1/search?indexerIds=${encode(indexerId)}&query=${encode(query)}&type=search", true))
        require(nodes.isArray) { "Invalid search response" }
        return nodes.take(100).mapNotNull { node ->
            if (node.path("indexerId").asText() != indexerId) return@mapNotNull null
            val suppliedMagnet = node.path("magnetUrl").asText("")
            val metadataHash = node.path("infoHash").asText("").lowercase()
            if (metadataHash.isNotBlank() && !metadataHash.matches(Regex("[a-f0-9]{40}"))) return@mapNotNull null
            // Prowlarr proxies MagnetUrl in its search response. Never fetch that URL:
            // construct a tracker-free magnet from the exact v1 identity metadata.
            val literalHash = if (suppliedMagnet.startsWith("magnet:"))
                runCatching { magnetHash(suppliedMagnet) }.getOrNull() ?: return@mapNotNull null else null
            if (literalHash != null && metadataHash.isNotBlank() && literalHash != metadataHash) return@mapNotNull null
            val hash = literalHash ?: metadataHash.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val magnet = "magnet:?xt=urn:btih:$hash"
            val title = node.path("title").asText().take(512)
            if (title.isBlank()) return@mapNotNull null
            AuthorizedSearchResult(indexerId, title, magnet, hash)
        }
    }

    fun lookup(hash: String): OwnedTorrent? {
        requireHash(hash)
        login()
        return readTorrent(hash)
    }

    private fun readTorrent(hash: String): OwnedTorrent? {
        val nodes = mapper.readTree(get(settings.qbittorrentUrl, "/api/v2/torrents/info?hashes=$hash"))
        require(nodes.isArray && nodes.size() <= 1) { "Ambiguous torrent response" }
        val node = nodes.firstOrNull() ?: return null
        require(node.path("hash").asText().equals(hash, true)) { "Torrent hash mismatch" }
        require(settings.normalizedSavePath(node.path("save_path").asText()) == settings.normalizedSavePath(settings.savePath)) { "Torrent save path no longer matches the dedicated acquisition root" }
        return OwnedTorrent(hash, node.path("category").asText(), node.path("tags").asText().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(), node.path("state").asText())
    }

    fun add(result: AuthorizedSearchResult, requestId: Long, candidateId: Long) {
        try {
            settings.requireEnabled()
            policy.requireApprovedIndexer(result.indexerId)
            require(result.indexerId in activeIndexers()) { "Approved indexer is no longer active" }
            require(magnetHash(result.magnet) == result.hash)
            login()
            val categories = mapper.readTree(get(settings.qbittorrentUrl, "/api/v2/torrents/categories"))
            check(categories.isObject && categories.has(policy.category())) { "Dedicated category must already exist before submission" }
            require(settings.normalizedSavePath(categories.path(policy.category()).path("savePath").asText()) == settings.normalizedSavePath(settings.savePath)) {
                "Dedicated category save path must match the configured acquisition root"
            }
            check(readTorrent(result.hash) == null) { "Existing torrent cannot be adopted or changed" }
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            throw AcquisitionPreflightRefusal(failure)
        }
        val body = post("/api/v2/torrents/add", mapOf("urls" to result.magnet, "category" to policy.category(),
            "tags" to policy.ownershipTags(requestId, candidateId).joinToString(","), "autoTMM" to "false", "stopped" to "true",
            "savepath" to settings.savePath, "useDownloadPath" to "false"))
        // qB5.2 returns counts and exact added identities; older releases use text.
        // Neither acknowledgment is sufficient without the exact owned state below.
        val acknowledged = body.trim() in setOf("", "Ok.") || runCatching {
            val reply = mapper.readTree(body)
            val ids = reply.path("added_torrent_ids")
            reply.isObject && reply.path("success_count").asInt(-1) == 1 &&
                reply.path("failure_count").asInt(-1) == 0 && reply.path("pending_count").asInt(-1) == 0 &&
                ids.isArray && ids.size() == 1 && ids.first().asText() == result.hash
        }.getOrDefault(false)
        check(acknowledged) { "Provider did not acknowledge submission; inspect before retry" }
        confirmState(result.hash, requestId, candidateId, stopped = true)
        // Never start an unowned/mis-tagged or unexpectedly running add response.
        post("/api/v2/torrents/start", mapOf("hashes" to result.hash))
        confirmState(result.hash, requestId, candidateId, stopped = false)
    }

    fun stop(hash: String, requestId: Long, candidateId: Long) = ownedAction("stop", hash, requestId, candidateId)
    fun start(hash: String, requestId: Long, candidateId: Long) = ownedAction("start", hash, requestId, candidateId)

    private fun ownedAction(action: String, hash: String, requestId: Long, candidateId: Long) {
        val torrent = lookup(hash) ?: error("Recorded torrent no longer exists")
        policy.requireOwnedTorrent(torrent.category, torrent.tags, requestId, candidateId)
        post("/api/v2/torrents/$action", mapOf("hashes" to hash))
        confirmState(hash, requestId, candidateId, stopped = action == "stop")
    }

    private fun confirmState(hash: String, requestId: Long, candidateId: Long, stopped: Boolean) {
        val expected = if (stopped) setOf("stoppedDL", "stoppedUP", "pausedDL", "pausedUP") else
            setOf("downloading", "metaDL", "stalledDL", "queuedDL", "uploading", "stalledUP", "queuedUP", "forcedDL", "forcedUP", "checkingDL", "checkingUP", "allocating", "moving", "checkingResumeData")
        repeat(3) { attempt ->
            val owned = readTorrent(hash)
            if (owned != null) {
                policy.requireOwnedTorrent(owned.category, owned.tags, requestId, candidateId)
                if (owned.state in expected) return
            }
            if (attempt < 2) Thread.sleep(250)
        }
        error("Owned torrent state was not confirmed; reconcile before retry")
    }

    private fun login() {
        settings.requireEnabled()
        val response = post("/api/v2/auth/login", mapOf("username" to settings.qbittorrentUsername, "password" to settings.qbittorrentPassword))
        check(response.trim() in setOf("", "Ok.")) { "Torrent client authentication failed" }
        check(cookies.cookieStore.cookies.any {
            (it.name == "SID" || it.name.matches(Regex("QBT_SID_[1-9][0-9]{0,4}"))) && it.value.isNotBlank()
        }) { "Torrent client authentication cookie missing" }
        check(get(settings.qbittorrentUrl, "/api/v2/app/version").trim().matches(Regex("v?5\\.[0-9]+.*"))) { "Only reviewed qBittorrent 5.x stopped/start/stop semantics are supported" }
    }

    private fun get(origin: String, path: String, prowlarr: Boolean = false): String {
        val builder = HttpRequest.newBuilder(URI(origin.trimEnd('/') + path)).timeout(Duration.ofSeconds(15)).GET()
        if (prowlarr) builder.header("X-Api-Key", settings.prowlarrApiKey)
        else builder.header("Referer", settings.qbittorrentUrl.trimEnd('/'))
        return send(builder.build(), prowlarr)
    }

    private fun post(path: String, fields: Map<String, String>): String = send(HttpRequest.newBuilder(URI(settings.qbittorrentUrl.trimEnd('/') + path))
        .header("Referer", settings.qbittorrentUrl.trimEnd('/'))
        .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(fields.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" })).build())

    private fun send(request: HttpRequest, prowlarr: Boolean = false): String {
        // Bound hostile responses without retaining provider bodies or credentials in error messages.
        val response = (if (prowlarr) prowlarrHttp else qbHttp).send(request) { LimitedBodySubscriber() }
        check(response.statusCode() in 200..299) { "Acquisition provider request failed (${response.statusCode()})" }
        return response.body()
    }

    /** Explicit body deadline as well as the request/header deadline; hostile streams cannot stall forever. */
    private class LimitedBodySubscriber : HttpResponse.BodySubscriber<String> {
        private val result = CompletableFuture<String>()
        private val output = ByteArrayOutputStream()
        @Volatile private var subscription: Flow.Subscription? = null
        init {
            result.orTimeout(15, TimeUnit.SECONDS).whenComplete { _, failure -> if (failure != null) subscription?.cancel() }
        }
        override fun getBody(): CompletionStage<String> = result
        override fun onSubscribe(subscription: Flow.Subscription) {
            this.subscription = subscription
            if (result.isDone) subscription.cancel() else subscription.request(1)
        }
        override fun onNext(buffers: List<ByteBuffer>) {
            try {
                for (buffer in buffers) {
                    check(buffer.remaining() <= 1_048_576 - output.size()) { "Acquisition response exceeds limit" }
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes); output.write(bytes)
                }
                subscription?.request(1)
            } catch (failure: Exception) { subscription?.cancel(); result.completeExceptionally(failure) }
        }
        override fun onError(failure: Throwable) { result.completeExceptionally(failure) }
        override fun onComplete() { result.complete(output.toString(Charsets.UTF_8)) }
    }

    companion object {
        private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
        private fun requireHash(hash: String) = require(hash.matches(Regex("[a-f0-9]{40}"))) { "Only exact v1 torrent hashes are supported" }
        fun magnetHash(magnet: String): String {
            require(magnet.length <= 8192 && magnet.startsWith("magnet:?")) { "Only bounded magnet metadata is supported" }
            val fields = magnet.substringAfter("magnet:?").split('&').map {
                val parts = it.split('=', limit = 2)
                require(parts.size == 2) { "Malformed magnet metadata" }
                URLDecoder.decode(parts[0], Charsets.UTF_8) to URLDecoder.decode(parts[1], Charsets.UTF_8)
            }
            require(fields.all { it.first in setOf("xt", "dn", "tr") }) { "Only identity, display name and trackers are supported" }
            val identities = fields.filter { it.first == "xt" }
            require(identities.size == 1 && identities.single().second.matches(Regex("urn:btih:[a-fA-F0-9]{40}"))) { "Exactly one v1 torrent identity is required" }
            return identities.single().second.removePrefix("urn:btih:").lowercase()
        }
    }
}
