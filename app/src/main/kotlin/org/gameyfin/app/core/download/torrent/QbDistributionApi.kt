package org.gameyfin.app.core.download.torrent

import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

/** Official release-5.2.3 torrentcreatorcontroller/torrentscontroller protocol only.
 * No redirects, DNS targets, proxies, URL acquisition, arbitrary API actions, retries,
 * preference mutations or default client-policy changes. Not a registered provider. */
internal class QbDistributionApi(private val policy: QbDistributionPolicy, private val credentials: QbDistributionCredentials) : AutoCloseable {
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    private val mapper = JsonMapper.builder(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
        .maxNestingDepth(16).maxStringLength(8192).maxNumberLength(32).build()).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()
    private val client = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(3)).proxy(object : ProxySelector() {
            override fun select(uri: URI) = listOf(Proxy.NO_PROXY)
            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) = Unit
        }).build()
    private var authenticated = false
    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
    private fun form(fields: Map<String, String>) = fields.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }.toByteArray()
    private fun json(bytes: ByteArray): JsonNode = try { mapper.readTree(bytes) } catch (_: Exception) { error("Invalid distribution API JSON") }
    private fun hash(value: String) { require(value.matches(Regex("[a-f0-9]{40}"))) }
    private fun task(value: String) { require(value.matches(Regex("[A-Za-z0-9-]{1,128}"))) }
    private fun request(route: String, fields: Map<String, String>? = null, query: Map<String, String> = emptyMap(),
                        payload: ByteArray? = null, contentType: String? = null, limit: Int = 1024 * 1024): Pair<Int, ByteArray> {
        require(limit in 1..2 * 1024 * 1024)
        val uri = policy.origin.resolve("/api/v2/$route" + if (query.isEmpty()) "" else "?" + form(query).toString(Charsets.UTF_8))
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Referer", policy.origin.toString())
        val body = payload ?: fields?.let(::form)
        if (body == null) builder.GET() else {
            require(body.size <= 2 * 1024 * 1024)
            builder.header("Content-Type", contentType ?: "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofByteArray(body))
        }
        val future = client.sendAsync(builder.build(), HttpResponse.BodyHandler { BoundedBody(limit) })
        return try { val response = future.get(11, TimeUnit.SECONDS); response.statusCode() to response.body() }
        catch (interrupted: InterruptedException) { future.cancel(true); Thread.currentThread().interrupt(); throw interrupted }
        catch (_: Exception) { future.cancel(true); throw IllegalStateException("Distribution API request outcome unavailable") }
    }
    private fun success(result: Pair<Int, ByteArray>): ByteArray {
        check(result.first in 200..299) { "Distribution API refused operation" }; return result.second
    }
    @Synchronized fun verifyClient() {
        policy.requireAdmission()
        if (!authenticated) {
            val login = request("auth/login", mapOf("username" to credentials.username, "password" to credentials.password), limit = 4096)
            check(login.first in setOf(200, 204) && login.second.toString(Charsets.UTF_8).trim() in setOf("", "Ok.")) { "Distribution authentication refused" }
            check(cookies.cookieStore.cookies.any { (it.name == "SID" || it.name.matches(Regex("QBT_SID_[1-9][0-9]{0,4}"))) && !it.hasExpired() && it.value.isNotEmpty() }) { "Distribution session cookie absent" }
            authenticated = true
        }
        check(success(request("app/version", limit = 128)).toString(Charsets.UTF_8).trim() == policy.expectedVersion) { "Unreviewed distribution client version" }
        check(success(request("app/webapiVersion", limit = 128)).toString(Charsets.UTF_8).trim() == policy.expectedWebApiVersion) { "Unreviewed distribution API version" }
        val preferences = json(success(request("app/preferences")))
        for (key in listOf("dht", "pex", "lsd", "upnp", "random_port")) check(preferences[key]?.isBoolean == true && !preferences[key].booleanValue()) { "Distribution discovery policy differs" }
        check(preferences["ip_filter_enabled"]?.booleanValue() == true) { "Distribution peer filter is not enabled" }
    }
    fun create(snapshotId: UUID, operation: UUID): String {
        val response = json(success(request("torrentcreator/addTask", mapOf("sourcePath" to policy.source(snapshotId),
            "private" to "true", "format" to "v1", "startSeeding" to "false", "optimizeAlignment" to "false",
            "comment" to policy.comment(operation), "trackers" to policy.approvedTrackers.sorted().joinToString("|") { encode(it) }, "urlSeeds" to ""))))
        return response["taskID"]?.asText()?.also(::task) ?: error("Creation task identity absent")
    }
    fun tasks(): List<JsonNode> {
        val rows = json(success(request("torrentcreator/status")))
        check(rows.isArray && rows.size() <= 128) { "Creation task inventory exceeds bound" }; return rows.toList()
    }
    fun ownedTask(taskId: String, snapshotId: UUID, operation: UUID): JsonNode {
        task(taskId)
        val rows = json(success(request("torrentcreator/status", query = mapOf("taskID" to taskId))))
        check(rows.isArray && rows.size() == 1)
        val row = rows[0]
        // release-5.2.3 parseUrls("") preserves one empty entry in task params/status,
        // although TorrentCreator ignores it. No actual URL or other shape is admitted.
        val seeds = row["urlSeeds"]
        val noSeeds = seeds == null || seeds.isArray && seeds.size() == 1 && seeds[0].isString && seeds[0].asText().isEmpty()
        check(row["taskID"]?.asText() == taskId && row["sourcePath"]?.asText() == policy.source(snapshotId) &&
            row["comment"]?.asText() == policy.comment(operation) && row["private"]?.booleanValue() == true && row["format"]?.asText() == "v1" &&
            row["torrentFilePath"] == null && row["source"] == null && noSeeds) { "Creation task ownership differs" }
        check(row["trackers"]?.isArray == true && row["trackers"].size() == policy.approvedTrackers.size &&
            row["trackers"].all { it.isString } && row["trackers"].map { it.asText() }.toSet() == policy.approvedTrackers) { "Creation tracker policy differs" }
        return row
    }
    fun metadata(taskId: String): ByteArray { task(taskId); return success(request("torrentcreator/torrentFile", query = mapOf("taskID" to taskId))) }
    fun deleteTask(taskId: String) { task(taskId); success(request("torrentcreator/deleteTask", mapOf("taskID" to taskId))) }
    fun find(infoHash: String): JsonNode? {
        hash(infoHash); val rows = json(success(request("torrents/info", query = mapOf("hashes" to infoHash))))
        check(rows.isArray && rows.size() <= 1); return rows.firstOrNull()?.also { check(it["hash"]?.asText() == infoHash) }
    }
    /** Dedicated-client inventory used only to prove absence of this operation/path.
     * Never used to adopt or mutate another row; an overlarge inventory fails closed. */
    fun inventory(): List<JsonNode> {
        val rows = json(success(request("torrents/info")))
        check(rows.isArray && rows.size() <= 128) { "Distribution inventory exceeds bound" }
        val hashes = rows.map { row ->
            check(row["hash"]?.isString == true); row["hash"].asText().also(::hash)
        }
        check(hashes.distinct().size == hashes.size) { "Duplicate distribution inventory identity" }
        return rows.toList()
    }
    fun export(infoHash: String): ByteArray { hash(infoHash); return success(request("torrents/export", query = mapOf("hash" to infoHash))) }
    fun properties(infoHash: String): JsonNode { hash(infoHash); return json(success(request("torrents/properties", query = mapOf("hash" to infoHash)))) }
    fun files(infoHash: String): List<JsonNode> { hash(infoHash); val rows = json(success(request("torrents/files", query = mapOf("hash" to infoHash)))); check(rows.isArray && rows.size() <= 4096); return rows.toList() }
    fun trackers(infoHash: String): List<JsonNode> { hash(infoHash); val rows = json(success(request("torrents/trackers", query = mapOf("hash" to infoHash)))); check(rows.isArray && rows.size() <= 16); return rows.toList() }
    fun webSeeds(infoHash: String): List<JsonNode> { hash(infoHash); val rows = json(success(request("torrents/webseeds", query = mapOf("hash" to infoHash)))); check(rows.isArray && rows.size() <= 16); return rows.toList() }
    fun addStopped(snapshotId: UUID, operation: UUID, metadata: ByteArray) {
        val boundary = "gameyfin" + UUID.randomUUID().toString().replace("-", "")
        val output = ByteArrayOutputStream()
        fun part(name: String, value: String) { output.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n").toByteArray()) }
        mapOf("stopped" to "true", "skip_checking" to "false", "savepath" to policy.savePath(snapshotId), "category" to policy.category,
            "tags" to policy.tag(operation), "autoTMM" to "false", "useDownloadPath" to "false", "contentLayout" to "Original", "forced" to "false").forEach(::part)
        output.write(("--$boundary\r\nContent-Disposition: form-data; name=\"torrents\"; filename=\"owned.torrent\"\r\nContent-Type: application/x-bittorrent\r\n\r\n").toByteArray())
        output.write(metadata); output.write("\r\n--$boundary--\r\n".toByteArray())
        success(request("torrents/add", payload = output.toByteArray(), contentType = "multipart/form-data; boundary=$boundary"))
    }
    fun stop(infoHash: String) = mutate("stop", infoHash)
    fun start(infoHash: String) = mutate("start", infoHash)
    fun recheck(infoHash: String) = mutate("recheck", infoHash)
    fun remove(infoHash: String) { hash(infoHash); success(request("torrents/delete", mapOf("hashes" to infoHash, "deleteFiles" to "false"))) }
    private fun mutate(action: String, infoHash: String) { hash(infoHash); success(request("torrents/$action", mapOf("hashes" to infoHash))) }
    override fun close() { client.shutdownNow(); cookies.cookieStore.removeAll(); authenticated = false }

    private class BoundedBody(private val limit: Int) : HttpResponse.BodySubscriber<ByteArray> {
        private val future = CompletableFuture<ByteArray>(); private val output = ByteArrayOutputStream()
        private var subscription: Flow.Subscription? = null
        override fun getBody(): CompletionStage<ByteArray> = future
        override fun onSubscribe(value: Flow.Subscription) { if (subscription != null) value.cancel() else { subscription = value; value.request(1) } }
        override fun onNext(items: List<ByteBuffer>) {
            if (future.isDone) return
            for (item in items) {
                if (item.remaining() > limit - output.size()) { subscription?.cancel(); future.completeExceptionally(IllegalStateException("Distribution response exceeds bound")); return }
                val bytes = ByteArray(item.remaining()); item.get(bytes); output.write(bytes)
            }
            subscription?.request(1)
        }
        override fun onError(error: Throwable) { future.completeExceptionally(IllegalStateException("Distribution response unavailable")) }
        override fun onComplete() { future.complete(output.toByteArray()) }
    }
}
