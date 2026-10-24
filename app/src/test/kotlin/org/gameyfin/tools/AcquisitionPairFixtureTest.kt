package org.gameyfin.tools

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.Executors
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.gameyfin.app.requests.AcquisitionLiveProviderTest
import org.gameyfin.app.requests.AcquisitionProviderSettings
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tools.jackson.databind.node.ObjectNode

internal fun qbConfiguration(password: String, secureCookie: Boolean? = null): String {
    val salt = ByteArray(16).also(java.security.SecureRandom()::nextBytes)
    val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(PBEKeySpec(password.toCharArray(), salt, 100000, 512)).encoded
    val encoded = Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(derived)
    val transport = secureCookie?.let { "WebUI\\SecureCookie=$it\n" }.orEmpty()
    return "[LegalNotice]\nAccepted=true\n[Preferences]\nWebUI\\Address=*\nWebUI\\Username=fixture\nWebUI\\Password_PBKDF2=\"@ByteArray($encoded)\"\nWebUI\\LocalHostAuth=true\nWebUI\\CSRFProtection=true\n${transport}WebUI\\HostHeaderValidation=false\nConnection\\UPnP=false\n[BitTorrent]\nSession\\DHTEnabled=false\nSession\\PeXEnabled=false\nSession\\LSDEnabled=false\n"
}
internal fun fixtureSecret() = ByteArray(24).also(java.security.SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
internal fun FixtureHttp.qb(path: String, fields: Map<String, String>? = null): ByteArray {
    val reply = request("/api/v2/$path", fields?.let(FixtureHttp::form), mapOf("Referer" to origin, "Content-Type" to "application/x-www-form-urlencoded"), seconds = 10)
    check(reply.status == 200) { "Synthetic qB API refusal" }; return reply.bytes
}

/** Same authenticated readiness for both isolated HTTP clients, including persisted restart state. */
internal fun FixtureHttp.awaitQbAuthentication(password: String, seconds: Long, inspectState: () -> tools.jackson.databind.JsonNode, ready: () -> Boolean = { true }) {
    val deadline = FixtureDeadline(seconds)
    var stage = "TRANSPORT_UNAVAILABLE"
    var loginStatus: Int? = null; var probeStatus: Int? = null; var attempts = 0
    try {
        FixtureRuntime.await(seconds, { deadline.remaining() }) {
            attempts++; stage = "TRANSPORT_UNAVAILABLE"; loginStatus = null; probeStatus = null
            val login = request("/api/v2/auth/login", FixtureHttp.form(mapOf("username" to "fixture", "password" to password)), mapOf("Referer" to origin, "Content-Type" to "application/x-www-form-urlencoded"), seconds = minOf(10, deadline.remaining()))
            loginStatus = login.status
            if (login.status != 200) { stage = "LOGIN_HTTP_REFUSED"; return@await false }
            if (login.bytes.toString(Charsets.UTF_8).trim() !in listOf("", "Ok.")) { stage = "LOGIN_REFUSED"; return@await false }
            if (!cookies.cookieStore.cookies.any { (it.name == "SID" || it.name.matches(Regex("QBT_SID_[1-9][0-9]{0,4}"))) && it.value.isNotEmpty() && !it.hasExpired() }) { stage = "SESSION_COOKIE_ABSENT"; return@await false }
            stage = "AUTHENTICATED_PROBE_UNAVAILABLE"
            val probe = request("/api/v2/app/version", headers = mapOf("Referer" to origin), seconds = minOf(10, deadline.remaining()))
            probeStatus = probe.status
            if (probe.status != 200 || !probe.bytes.toString(Charsets.UTF_8).matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+"))) { stage = "AUTHENTICATED_PROBE_REFUSED"; return@await false }
            stage = "OWNED_STATE_NOT_READY"
            ready().also { if (it) stage = "AUTHENTICATED" }
        }
    } catch (failure: Exception) {
        val diagnostic = linkedMapOf<String, Any?>("scope" to "owned-synthetic-qb-authentication", "stage" to stage, "attempts" to attempts, "loginHttpStatus" to loginStatus, "probeHttpStatus" to probeStatus, "secureCookieExplicitlyDisabledForLoopback" to true)
        try {
            val actual = inspectState()
            diagnostic["containerRunning"] = actual["Running"].booleanValue()
            diagnostic["containerExitCode"] = actual["ExitCode"].asInt()
            diagnostic["containerOomKilled"] = actual["OOMKilled"].booleanValue()
        } catch (_: Exception) { diagnostic["containerInspectionUnavailable"] = true }
        // Never retain bodies, cookie names/values, credentials or raw container logs.
        println(FixtureRuntime.json.writeValueAsString(diagnostic))
        throw failure
    }
}

/** Real pinned providers; invented trackerless magnet, internal network, fresh configuration only. */
@EnabledIfEnvironmentVariable(named = "GAMEYFIN_ACQUISITION_PAIR_FIXTURE", matches = "true")
class AcquisitionPairFixtureTest {
    @Test fun `real owned pair passes adapter acceptance and stopped identity survives restart`() {
        FixtureRuntime.hosted()
        val root = FixtureRuntime.temporary("gameyfin-acquisition-")
        val pool = Executors.newFixedThreadPool(2)
        val server = HttpServer.create(InetSocketAddress("0.0.0.0", 0), 8)
        server.executor = pool
        server.createContext("/api") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40)
            val xml = if (query.split('&').any { it == "t=caps" }) "<caps><server title=\"Lawful synthetic fixture\"/><limits max=\"100\" default=\"100\"/><searching><search available=\"yes\" supportedParams=\"q\"/><tv-search available=\"no\"/><movie-search available=\"no\"/></searching><categories><category id=\"1000\" name=\"Console\"/><category id=\"4000\" name=\"PC\"/></categories></caps>"
            else "<rss version=\"2.0\" xmlns:torznab=\"http://torznab.com/schemas/2015/feed\"><channel><title>Fixture</title><item><title>Lawful synthetic fixture</title><guid isPermaLink=\"false\">$magnet</guid><link>$magnet</link><enclosure url=\"$magnet\" length=\"1\" type=\"application/x-bittorrent\"/><pubDate>Wed, 07 Oct 2026 12:00:00 GMT</pubDate><category>4000</category><torznab:attr name=\"category\" value=\"4000\"/><torznab:attr name=\"size\" value=\"1\"/><torznab:attr name=\"seeders\" value=\"0\"/><torznab:attr name=\"infohash\" value=\"${"a".repeat(40)}\"/><torznab:attr name=\"magneturl\" value=\"$magnet\"/></item></channel></rss>"
            exchange.use { it.responseHeaders.add("Content-Type", "application/rss+xml"); val bytes = xml.toByteArray(); it.sendResponseHeaders(200, bytes.size.toLong()); it.responseBody.use { body -> body.write(bytes) } }
        }
        var qbRelay: FixtureRelay? = null; var prowlarrRelay: FixtureRelay? = null
        val owned = FixtureContainers(root)
        try {
            val password = fixtureSecret(); val key = fixtureSecret()
            val qb = Files.createDirectories(root.resolve("qb/qBittorrent")); Files.writeString(qb.resolve("qBittorrent.conf"), qbConfiguration(password, secureCookie = false))
            val prowlarr = Files.createDirectory(root.resolve("prowlarr"))
            Files.writeString(prowlarr.resolve("config.xml"), "<Config><BindAddress>*</BindAddress><Port>9696</Port><ApiKey>$key</ApiKey><AuthenticationMethod>External</AuthenticationMethod><AuthenticationRequired>DisabledForLocalAddresses</AuthenticationRequired></Config>")
            val downloads = Files.createDirectory(root.resolve("downloads"))
            owned.createNetwork(); server.start()
            val gateway = FixtureRuntime.tree(owned.docker("network", "inspect", owned.network))[0]["IPAM"]["Config"][0]["Gateway"].asText()
            val uid = owned.docker("version", "--format", "{{.Server.Os}}")
            require(uid == "linux")
            val user = FixtureRuntime.command(listOf("id"), "-u"); val group = FixtureRuntime.command(listOf("id"), "-g")
            fun args(config: java.nio.file.Path) = listOf("--add-host", "host.docker.internal:$gateway", "--memory", "512m", "--cpus", "1", "-e", "PUID=$user", "-e", "PGID=$group", "--mount", "type=bind,src=$config,dst=/config")
            val qbId = owned.create("qb", QB, args(root.resolve("qb")) + listOf("--mount", "type=bind,src=$downloads,dst=/downloads"))
            val prowlarrId = owned.create("prowlarr", PROWLARR, args(prowlarr))
            qbRelay = FixtureRelay(owned.address(qbId), 8080); prowlarrRelay = FixtureRelay(owned.address(prowlarrId), 9696)
            val qbOrigin = qbRelay.origin; val prowlarrOrigin = prowlarrRelay.origin
            val api = FixtureHttp(prowlarrOrigin)
            fun api(path: String, body: String? = null, seconds: Long = 10): tools.jackson.databind.JsonNode {
                val reply = api.request("/api/v1/$path", body, mapOf("X-Api-Key" to key, "Content-Type" to "application/json"), seconds)
                check(reply.status in 200..299); return FixtureRuntime.tree(reply.bytes.toString(Charsets.UTF_8))
            }
            FixtureRuntime.await(180) { api("system/status"); true }
            val indexer = api("indexer/schema", seconds = 90).first { it["implementation"].asText() == "Torznab" } as ObjectNode
            indexer.put("name", "Lawful synthetic fixture"); indexer.put("enable", true); indexer.put("priority", 25); indexer.put("appProfileId", api("appprofile")[0]["id"].asInt())
            for (field in indexer["fields"]) when (field["name"].asText()) {
                "baseUrl" -> (field as ObjectNode).put("value", "http://host.docker.internal:${server.address.port}")
                "apiPath" -> (field as ObjectNode).put("value", "/api")
                "apiKey" -> (field as ObjectNode).put("value", "synthetic-only")
            }
            val indexerId = api("indexer", FixtureRuntime.json.writeValueAsString(indexer))["id"].asText()
            val client = FixtureHttp(qbOrigin)
            fun inspectQb() = FixtureRuntime.tree(owned.docker("inspect", qbId))[0]["State"]
            client.awaitQbAuthentication(password, 120, ::inspectQb)
            client.qb("torrents/createCategory", mapOf("category" to "fixture-acquisition", "savePath" to "/downloads/fixture-acquisition"))
            require(api("search?indexerIds=$indexerId&query=fixture&type=search").size() > 0)
            val settings = AcquisitionProviderSettings().apply {
                enabled = true; dedicatedClientAcknowledged = true; prowlarrUrl = prowlarrOrigin; prowlarrApiKey = key
                qbittorrentUrl = qbOrigin; qbittorrentUsername = "fixture"; qbittorrentPassword = password
                category = "fixture-acquisition"; managedTag = "fixture-managed"; savePath = "/downloads/fixture-acquisition"
            }
            AcquisitionLiveProviderTest().exercise(settings, indexerId)
            owned.docker("restart", qbId)
            client.awaitQbAuthentication(password, 120, ::inspectQb) {
                val torrents = FixtureRuntime.tree(client.qb("torrents/info").toString(Charsets.UTF_8))
                torrents.size() == 1 && torrents[0]["hash"].asText() == "a".repeat(40) && torrents[0]["state"].asText() == "stoppedDL"
            }
            Files.walk(downloads).use { paths -> require(paths.noneMatch { Files.isRegularFile(it) }) { "Synthetic magnet created payload" } }
        } finally {
            try { qbRelay?.close() } finally { try { prowlarrRelay?.close() } finally { server.stop(0); pool.shutdownNow(); owned.close() } }
        }
    }
    companion object {
        const val QB = "linuxserver/qbittorrent@sha256:b522f9f4b769f8f36d49d22d5eb6a92e9aa18904c6a1830b1439df511ec21983"
        const val PROWLARR = "linuxserver/prowlarr@sha256:f9151e5bc1025c6d0a630d503210cdcb6bb55a7cc098562609d96a408d838902"
    }
}
