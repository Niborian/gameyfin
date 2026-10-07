package org.gameyfin.app.requests

import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import org.gameyfin.app.config.ConfigProperties
import org.gameyfin.app.config.ConfigService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.test.*

class AcquisitionProviderTest {
    private val hash = "a".repeat(40)
    private val requests = mutableListOf<Pair<String, Map<String, String>>>()
    private var added = false
    private var wrongTags = false
    private var redirect = false
    private var categoryExists = true
    private var state = "stoppedDL"
    private var acknowledgeWithoutChangingState = false
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val fields = exchange.requestBody.readBytes().toString(Charsets.UTF_8).split('&').filter { it.contains('=') }
                .associate { val parts = it.split('=', limit = 2); URLDecoder.decode(parts[0], Charsets.UTF_8) to URLDecoder.decode(parts[1], Charsets.UTF_8) }
            requests.add(path to fields)
            var status = 200
            val body = when (path) {
                "/api/v1/indexer" -> {
                    assertEquals("synthetic-api-key", exchange.requestHeaders.getFirst("X-Api-Key"))
                    assertNull(exchange.requestHeaders.getFirst("Cookie"))
                    """[{"id":2,"enable":true},{"id":3,"enable":true},{"id":4,"enable":false}]"""
                }
                "/api/v1/search" -> {
                    assertTrue(exchange.requestURI.rawQuery.contains("indexerIds=2"))
                    if (redirect) { status = 302; exchange.responseHeaders.add("Location", "/must-not-follow") }
                    """[{"indexerId":2,"title":"Open source fixture","magnetUrl":"magnet:?xt=urn:btih:$hash"},{"indexerId":3,"title":"Unapproved","magnetUrl":"magnet:?xt=urn:btih:${"b".repeat(40)}"},{"indexerId":2,"title":"URL only","downloadUrl":"https://invalid.example/torrent"}]"""
                }
                "/api/v2/auth/login" -> {
                    assertEquals("synthetic-user", fields["username"])
                    assertEquals("synthetic-password", fields["password"])
                    assertNull(exchange.requestHeaders.getFirst("X-Api-Key"))
                    exchange.responseHeaders.add("Set-Cookie", "SID=fixture-session; Path=/; HttpOnly")
                    "Ok."
                }
                "/api/v2/torrents/info" -> {
                    assertTrue(exchange.requestHeaders.getFirst("Cookie").contains("SID=fixture-session"))
                    if (!added) "[]" else """[{"hash":"$hash","category":"isolated-fixture","state":"$state","tags":"${if (wrongTags) "other" else "fixture-managed,fixture-managed-request-7,fixture-managed-candidate-11"}"}]"""
                }
                "/api/v2/app/version" -> "v5.0.0"
                "/api/v2/torrents/categories" -> if (categoryExists) """{"isolated-fixture":{"name":"isolated-fixture"}}""" else "{}"
                "/api/v2/torrents/add" -> { assertEquals("true", fields["stopped"]); added = true; state = "stoppedDL"; "Ok." }
                "/api/v2/torrents/stop" -> { if (!acknowledgeWithoutChangingState) state = "stoppedDL"; "" }
                "/api/v2/torrents/start" -> { if (!acknowledgeWithoutChangingState) state = "downloading"; "" }
                else -> { status = 500; "Unexpected endpoint" }
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val settings = AcquisitionProviderSettings().apply {
        enabled = true; dedicatedClientAcknowledged = true
        prowlarrUrl = "http://127.0.0.1:${server.address.port}"; qbittorrentUrl = prowlarrUrl
        prowlarrApiKey = "synthetic-api-key"; qbittorrentUsername = "synthetic-user"; qbittorrentPassword = "synthetic-password"
        category = "isolated-fixture"; managedTag = "fixture-managed"
    }
    private val config = mockk<ConfigService>().also {
        every { it.get(ConfigProperties.Requests.Acquisition.ApprovedIndexerIds) } returns arrayOf("2", "4")
    }
    private val provider = AcquisitionProvider(settings, AcquisitionScopePolicy(config, settings), JsonMapper.builder().build())
    @AfterEach fun close() { server.stop(0) }

    @Test fun `search scopes active approved indexers and ignores arbitrary URL or unexpected indexer results`() {
        assertEquals(listOf("2"), provider.activeIndexers())
        assertEquals(listOf(hash), provider.search("2", "fixture").map { it.hash })
        assertFailsWith<IllegalArgumentException> { provider.search("3", "fixture") }
        assertFailsWith<IllegalArgumentException> { provider.search("4", "fixture") }
        assertFalse(requests.any { it.first.contains("grab") })
    }

    @Test fun `approved add uses dedicated scope then stop and retry only exact persisted hash without deletion`() {
        val result = provider.search("2", "fixture").single()
        provider.add(result, 7, 11)
        val fields = requests.single { it.first.endsWith("/add") }.second
        assertEquals("isolated-fixture", fields["category"])
        assertEquals("true", fields["stopped"])
        assertEquals("fixture-managed,fixture-managed-request-7,fixture-managed-candidate-11", fields["tags"])
        provider.stop(hash, 7, 11)
        provider.start(hash, 7, 11)
        assertEquals(hash, requests.single { it.first.endsWith("/stop") }.second["hashes"])
        assertFalse(requests.any { it.first.contains("delete") || it.second.values.contains("all") })
        // Cookie from qB must not leak to Prowlarr even when origin is shared in this fixture.
        provider.activeIndexers()
    }

    @Test fun `missing category refuses add and mis-tagged stopped acknowledgment never starts`() {
        val result = provider.search("2", "fixture").single()
        categoryExists = false
        assertFailsWith<IllegalStateException> { provider.add(result, 7, 11) }
        assertFalse(requests.any { it.first.endsWith("/add") })
        categoryExists = true; wrongTags = true
        assertFailsWith<IllegalArgumentException> { provider.add(result, 7, 11) }
        assertEquals("stoppedDL", state)
        assertFalse(requests.any { it.first.endsWith("/start") })
    }

    @Test fun `HTTP acknowledgment alone never confirms stop or resume state`() {
        added = true; state = "downloading"; acknowledgeWithoutChangingState = true
        assertFailsWith<IllegalStateException> { provider.stop(hash, 7, 11) }
        state = "stoppedDL"
        assertFailsWith<IllegalStateException> { provider.start(hash, 7, 11) }
    }

    @Test fun `existing torrent is never adopted and missing scope refuses stop`() {
        val result = provider.search("2", "fixture").single()
        added = true
        assertFailsWith<IllegalStateException> { provider.add(result, 7, 11) }
        wrongTags = true
        assertFailsWith<IllegalArgumentException> { provider.stop(hash, 7, 11) }
        assertFalse(requests.any { it.first.endsWith("/add") || it.first.endsWith("/stop") })
    }

    @Test fun `disabled or unacknowledged clients cause no HTTP and redirects are not followed`() {
        settings.enabled = false
        assertFailsWith<IllegalStateException> { provider.activeIndexers() }
        assertTrue(requests.isEmpty())
        settings.enabled = true; settings.dedicatedClientAcknowledged = false
        assertFailsWith<IllegalStateException> { provider.activeIndexers() }
        assertTrue(requests.isEmpty())
        settings.dedicatedClientAcknowledged = true; redirect = true
        assertFailsWith<IllegalStateException> { provider.search("2", "fixture") }
        assertFalse(requests.any { it.first == "/must-not-follow" })
    }

    @Test fun `credentials require safe explicitly configured origins and exact magnet identity`() {
        settings.prowlarrUrl = "http://remote.invalid"
        assertFailsWith<IllegalArgumentException> { provider.activeIndexers() }
        assertTrue(requests.isEmpty())
        assertFailsWith<IllegalArgumentException> { AcquisitionProvider.magnetHash("https://invalid.example") }
        assertFailsWith<IllegalArgumentException> { AcquisitionProvider.magnetHash("magnet:?xt=urn:btih:$hash&xt=urn:btih:$hash") }
        assertEquals(hash, AcquisitionProvider.magnetHash("magnet:?xt=urn:btih:${hash.uppercase()}"))
    }
}
