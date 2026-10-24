package org.gameyfin.tools

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Tiny loopback HTTP protocol regression only; not evidence of real qB/container acceptance. */
class QbAuthenticationContractTest {
    private fun fixture(loginStatus: Int, cookie: Boolean, loginBody: String = "", versionStatus: Int = 200, action: (FixtureHttp, () -> Int) -> Unit) {
        val executor = Executors.newFixedThreadPool(2)
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4)
        server.executor = executor
        val authenticated = java.util.concurrent.atomic.AtomicInteger()
        server.createContext("/api/v2/auth/login") { request ->
            request.use {
                it.requestBody.use { body -> body.readNBytes(1024) }
                if (cookie) it.responseHeaders.add("Set-Cookie", "SID=synthetic-session; Path=/; HttpOnly")
                val bytes = loginBody.toByteArray()
                it.sendResponseHeaders(loginStatus, if (loginStatus == 204 || bytes.isEmpty()) -1 else bytes.size.toLong())
                if (loginStatus != 204 && bytes.isNotEmpty()) it.responseBody.use { body -> body.write(bytes) }
            }
        }
        server.createContext("/api/v2/app/version") { request -> request.use {
            val signedIn = it.requestHeaders.getFirst("Cookie").orEmpty().contains("SID=synthetic-session")
            val bytes = if (signedIn && versionStatus == 200) "v5.2.3".toByteArray() else "denied".toByteArray()
            if (signedIn) authenticated.incrementAndGet()
            it.sendResponseHeaders(if (signedIn) versionStatus else 403, bytes.size.toLong()); it.responseBody.use { body -> body.write(bytes) }
        } }
        server.createContext("/api/v2/torrents/stop") { request -> request.use { it.sendResponseHeaders(loginStatus, -1) } }
        server.start()
        try { action(FixtureHttp("http://127.0.0.1:${server.address.port}")) { authenticated.get() } }
        finally { server.stop(0); executor.shutdownNow(); check(executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) }
    }
    private fun state() = FixtureRuntime.tree("""{"Running":true,"ExitCode":0,"OOMKilled":false}""")

    @Test fun `204 empty login succeeds only after cookie authenticated version probe`() {
        fixture(204, true) { client, probes ->
            client.awaitQbAuthentication("synthetic", 3, ::state)
            assertEquals(1, probes())
            assertTrue(client.qb("torrents/stop", mapOf("hashes" to "a".repeat(40))).isEmpty())
        }
    }
    @Test fun `200 accepted login retains cookie authenticated version requirement`() {
        fixture(200, true, "Ok.") { client, probes ->
            client.awaitQbAuthentication("synthetic", 3, ::state)
            assertEquals(1, probes())
        }
    }
    @Test fun `204 without session cookie never becomes authenticated`() {
        fixture(204, false) { client, probes ->
            assertFailsWith<IllegalStateException> { client.awaitQbAuthentication("synthetic", 2, ::state) }
            assertEquals(0, probes())
        }
    }
    @Test fun `204 with cookie but refused authenticated version probe remains refused`() {
        fixture(204, true, versionStatus = 403) { client, probes ->
            assertFailsWith<IllegalStateException> { client.awaitQbAuthentication("synthetic", 2, ::state) }
            assertTrue(probes() > 0)
        }
    }
    @Test fun `HTTP refusal and rejected login body stay refused even with cookie`() {
        fixture(403, true) { client, probes ->
            assertFailsWith<IllegalStateException> { client.awaitQbAuthentication("synthetic", 2, ::state) }
            assertEquals(0, probes())
            assertFailsWith<IllegalStateException> { client.qb("torrents/stop", mapOf("hashes" to "a".repeat(40))) }
        }
        fixture(200, true, "Fails.") { client, probes ->
            assertFailsWith<IllegalStateException> { client.awaitQbAuthentication("synthetic", 2, ::state) }
            assertEquals(0, probes())
        }
    }
}
