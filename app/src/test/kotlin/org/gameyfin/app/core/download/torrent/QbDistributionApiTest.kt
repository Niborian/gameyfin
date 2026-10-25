package org.gameyfin.app.core.download.torrent

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.UUID
import kotlin.test.*

class QbDistributionApiTest {
    private fun policy(port: Int) = QbDistributionPolicy(UUID.randomUUID(), "http://127.0.0.1:$port", expectedWebApiVersion = "2.11.0",
        qbCacheRoot = "/owned/cache", cacheIdentity = "fixture-identity", approvedTrackers = setOf("https://tracker.example/announce"),
        dedicatedReadOnlyClientConfirmed = true, privatePeerEgressConfirmed = true)

    @Test fun `remote mapping is POSIX and rejects traversal public origins and missing admission`() {
        assertEquals("/owned/cache", policy(1234).qbCacheRoot)
        for (root in listOf("/cache", "/owned/../cache", "/owned/cache/", "C:/owned/cache", "/owned//cache")) {
            assertFailsWith<IllegalArgumentException> { QbDistributionPolicy(UUID.randomUUID(), "http://127.0.0.1:1234", expectedWebApiVersion = "2.11.0",
                qbCacheRoot = root, cacheIdentity = "id", approvedTrackers = setOf("https://tracker.example/announce"), dedicatedReadOnlyClientConfirmed = true, privatePeerEgressConfirmed = true) }
        }
        assertFailsWith<IllegalArgumentException> { QbDistributionPolicy(UUID.randomUUID(), "http://8.8.8.8:1234", expectedWebApiVersion = "2.11.0",
            qbCacheRoot = "/owned/cache", cacheIdentity = "id", approvedTrackers = setOf("https://tracker.example/announce"), dedicatedReadOnlyClientConfirmed = false, privatePeerEgressConfirmed = false) }
    }

    @Test fun `official creator request never starts seeding and deletion never deletes payload`() {
        val requests = mutableListOf<Pair<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v2/") { exchange ->
            val route = exchange.requestURI.path.removePrefix("/api/v2/")
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            requests += route to body
            val response = when (route) {
                "auth/login" -> { exchange.responseHeaders.add("Set-Cookie", "SID=disposable-fixture; HttpOnly; Path=/"); "Ok." }
                "app/version" -> "v5.2.3"
                "app/webapiVersion" -> "2.11.0"
                "app/preferences" -> """{"dht":false,"pex":false,"lsd":false,"upnp":false,"random_port":false,"ip_filter_enabled":true}"""
                "torrentcreator/addTask" -> """{"taskID":"owned-task"}"""
                else -> ""
            }.toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong()); exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val policy = policy(server.address.port)
            QbDistributionApi(policy, QbDistributionCredentials("fixture", "disposable")).use { api ->
                api.verifyClient()
                val snapshot = UUID.randomUUID(); val operation = UUID.randomUUID()
                assertEquals("owned-task", api.create(snapshot, operation))
                api.addStopped(snapshot, operation, "fixture-metainfo".toByteArray())
                api.remove("a".repeat(40))
                val creator = requests.single { it.first == "torrentcreator/addTask" }.second.split('&').associate {
                    val pieces = it.split('=', limit = 2); URLDecoder.decode(pieces[0], Charsets.UTF_8) to URLDecoder.decode(pieces[1], Charsets.UTF_8)
                }
                assertEquals("false", creator["startSeeding"]); assertEquals("true", creator["private"])
                assertEquals(policy.source(snapshot), creator["sourcePath"]); assertEquals(policy.comment(operation), creator["comment"])
                assertEquals("", creator["urlSeeds"]); assertFalse(creator.containsKey("source"))
                val add = requests.single { it.first == "torrents/add" }.second
                assertTrue(add.contains("name=\"stopped\"\r\n\r\ntrue")); assertTrue(add.contains("name=\"skip_checking\"\r\n\r\nfalse"))
                assertTrue(requests.single { it.first == "torrents/delete" }.second.contains("deleteFiles=false"))
            }
        } finally { server.stop(0) }
    }

    @Test fun `redirect is not followed and credentials are not represented`() {
        var redirected = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v2/auth/login") { it.responseHeaders.add("Location", "/forbidden"); it.sendResponseHeaders(302, -1); it.close() }
        server.createContext("/forbidden") { redirected = true; it.sendResponseHeaders(200, -1); it.close() }
        server.start()
        try {
            val credentials = QbDistributionCredentials("secret-user", "secret-password")
            assertFalse(credentials.toString().contains("secret"))
            QbDistributionApi(policy(server.address.port), credentials).use { assertFailsWith<IllegalStateException> { it.verifyClient() } }
            assertFalse(redirected)
        } finally { server.stop(0) }
    }

    @Test fun `remote API requires TLS and tracker policy cannot change after binding`() {
        val trackers = mutableSetOf("https://tracker.example/announce")
        fun configured(origin: String) = QbDistributionPolicy(UUID.randomUUID(), origin, expectedWebApiVersion = "2.11.0",
            qbCacheRoot = "/owned/cache", cacheIdentity = "id", approvedTrackers = trackers,
            dedicatedReadOnlyClientConfirmed = true, privatePeerEgressConfirmed = true)
        assertFailsWith<IllegalArgumentException> { configured("http://10.0.0.2:8080") }
        val remote = configured("https://10.0.0.2:8080")
        val digest = remote.mappingDigest
        trackers.clear(); trackers += "https://other.example/announce"
        assertEquals(setOf("https://tracker.example/announce"), remote.approvedTrackers)
        assertEquals(digest, remote.mappingDigest)
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (remote.approvedTrackers as MutableSet<String>).clear() }
    }

    @Test fun `creator status rejects duplicate trackers and trailing JSON`() {
        val snapshot = UUID.randomUUID(); val operation = UUID.randomUUID()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var duplicate = true
        server.createContext("/api/v2/torrentcreator/status") { exchange ->
            val tracker = "\"https://tracker.example/announce\""
            val response = ("""[{"taskID":"task","sourcePath":"/owned/cache/$snapshot/data","comment":"gameyfin-owned-distribution:$operation","private":true,"format":"v1","trackers":[$tracker${if (duplicate) ",$tracker" else ""}]}]""" +
                if (duplicate) "" else " {}").toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong()); exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            QbDistributionApi(policy(server.address.port), QbDistributionCredentials("fixture", "disposable")).use { api ->
                assertFailsWith<IllegalStateException> { api.ownedTask("task", snapshot, operation) }
                duplicate = false
                assertFailsWith<IllegalStateException> { api.ownedTask("task", snapshot, operation) }
            }
        } finally { server.stop(0) }
    }

    @Test fun `absence inventory refuses duplicate identities malformed hash and oversized row count`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var response = "[]"
        server.createContext("/api/v2/torrents/info") { exchange ->
            val bytes = response.toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            QbDistributionApi(policy(server.address.port), QbDistributionCredentials("fixture", "disposable")).use { api ->
                assertTrue(api.inventory().isEmpty())
                response = "[{\"hash\":\"${"a".repeat(40)}\"},{\"hash\":\"${"a".repeat(40)}\"}]"
                assertFails { api.inventory() }
                response = "[{\"hash\":12}]"; assertFails { api.inventory() }
                response = (0..128).joinToString(",", "[", "]") { "{\"hash\":\"${it.toString(16).padStart(40, '0')}\"}" }
                assertFails { api.inventory() }
            }
        } finally { server.stop(0) }
    }

    @Test fun `official empty URL seed status is accepted but every actual destination is refused`() {
        val snapshot = UUID.randomUUID(); val operation = UUID.randomUUID()
        var seeds: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v2/torrentcreator/status") { exchange ->
            val field = seeds?.let { ",\"urlSeeds\":$it" }.orEmpty()
            val bytes = """[{"taskID":"task","sourcePath":"/owned/cache/$snapshot/data","comment":"gameyfin-owned-distribution:$operation","private":true,"format":"v1","trackers":["https://tracker.example/announce"]$field}]""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            QbDistributionApi(policy(server.address.port), QbDistributionCredentials("fixture", "disposable")).use { api ->
                assertEquals("task", api.ownedTask("task", snapshot, operation)["taskID"].asText())
                seeds = "[\"\"]"
                assertEquals("task", api.ownedTask("task", snapshot, operation)["taskID"].asText())
                for (invalid in listOf("[\"https://foreign.example/payload\"]", "[1]", "[\"\",\"\"]", "[]", "\"\"", "null", "{}")) {
                    seeds = invalid
                    assertFailsWith<IllegalStateException> { api.ownedTask("task", snapshot, operation) }
                }
            }
        } finally { server.stop(0) }
    }

    @Test fun `revocation during delayed client proof prevents every following mutation POST`() {
        val posts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val authorized = java.util.concurrent.atomic.AtomicBoolean(true)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v2/") { exchange ->
            val route = exchange.requestURI.path.removePrefix("/api/v2/")
            if (exchange.requestMethod == "POST") posts += route
            val response = when (route) {
                "auth/login" -> { exchange.responseHeaders.add("Set-Cookie", "SID=disposable-fixture; HttpOnly; Path=/"); "Ok." }
                "app/version" -> "v5.2.3"
                "app/webapiVersion" -> "2.11.0"
                "app/preferences" -> {
                    // Model authority changing while the actual HTTP proof is in flight.
                    authorized.set(false)
                    """{"dht":false,"pex":false,"lsd":false,"upnp":false,"random_port":false,"ip_filter_enabled":true}"""
                }
                else -> error("Unexpected mutation reached transport")
            }.toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong()); exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            QbDistributionApi(policy(server.address.port), QbDistributionCredentials("fixture", "disposable"),
                { check(authorized.get()) { "Synthetic caller revoked" } }).use { api ->
                api.verifyClient()
                val snapshot = UUID.randomUUID(); val operation = UUID.randomUUID(); val hash = "a".repeat(40)
                val actions: List<() -> Unit> = listOf({ api.create(snapshot, operation); Unit },
                    { api.addStopped(snapshot, operation, byteArrayOf(1)) }, { api.start(hash) }, { api.stop(hash) },
                    { api.recheck(hash) }, { api.remove(hash) }, { api.deleteTask("task") })
                actions.forEach { action -> assertFailsWith<IllegalStateException> { action() } }
                assertEquals(listOf("auth/login"), posts)
            }
        } finally { server.stop(0) }
    }
}
