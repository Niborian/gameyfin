package org.gameyfin.app.users.avatar

import jakarta.servlet.AsyncContext
import jakarta.servlet.AsyncEvent
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockAsyncContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Servlet callbacks over owned bytes, not a real connector/security/OIDC/restore proof. */
class AvatarMachineServletTest {
    private val secret = "a".repeat(43) // Synthetic; Ops is an isolated transport fake, not the native verifier.
    private val scope = AvatarLedger.Scope(UUID.randomUUID(), UUID.randomUUID(), 1, "sha256:" + "b".repeat(64),
        "c".repeat(64), UUID.randomUUID(), 1, "d".repeat(64), 1, 1)
    private val scopeJson = mapOf("installation" to scope.installation.toString(), "restore" to scope.restore.toString(),
        "configuration" to 1, "imageDigest" to scope.imageDigest, "buildChecksum" to scope.buildChecksum,
        "grant" to scope.grant.toString(), "grantGeneration" to 1, "bindingDigest" to scope.bindingDigest,
        "bindingGeneration" to 1, "userId" to 1)
    private fun pin(bytes: ByteArray?, action: String = "intent"): String = String(AvatarMachineProtocol.json(
        if (action == "current") mapOf("scope" to scopeJson) else mapOf("scope" to scopeJson,
            "operation" to UUID.randomUUID().toString(), "expectedVersion" to 0, "expectedHash" to null,
            "sourceGeneration" to 1, "desiredHash" to bytes?.let {
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(it)) })))
    private class Input(private val bytes: ByteArray) : ServletInputStream() {
        var listener: ReadListener? = null
        var consumed = 0
        override fun isFinished() = consumed == bytes.size
        override fun isReady() = true
        override fun setReadListener(value: ReadListener) { listener = value }
        override fun read(): Int = if (isFinished) -1 else bytes[consumed++].toInt() and 255
        override fun read(out: ByteArray, offset: Int, length: Int): Int {
            if (isFinished) return -1
            val count = minOf(length, bytes.size - consumed)
            bytes.copyInto(out, offset, consumed, consumed + count); consumed += count; return count
        }
        fun deliver() { listener?.onDataAvailable(); if (isFinished) listener?.onAllDataRead() }
    }
    private open class Request(bytes: ByteArray, val unknownLength: Boolean = false) : MockHttpServletRequest() {
        val source = Input(bytes)
        init { setAsyncSupported(true); setContent(bytes) }
        override fun getInputStream() = source
        override fun getContentLengthLong() = if (unknownLength) -1L else super.getContentLengthLong()
    }
    private fun request(bytes: ByteArray = byteArrayOf(), method: String = "PUT", path: String = "/avatar",
                        unknownLength: Boolean = false): Request = Request(bytes, unknownLength).apply {
        this.method = method; pathInfo = path; contentType = "image/png"
        addHeader("Authorization", "Bearer $secret")
        addHeader(AvatarMachineProtocol.HEADER, pin(if (method == "DELETE") null else bytes,
            if (path == "/current") "current" else "intent"))
    }
    private class Ops : AvatarMachineOperations {
        var permitted = true; var preflights = 0; var writes = 0; var reads = 0; var recoveryReads = 0
        var received: ByteArray? = null
        override fun preflight(secret: String, scope: AvatarLedger.Scope, requestFence: () -> Unit) {
            requestFence(); preflights++; if (!permitted) throw AvatarAuthority.Denied()
        }
        override fun current(secret: String, scope: AvatarLedger.Scope, requestFence: () -> Unit): AvatarLedger.Current {
            requestFence(); reads++; return AvatarLedger.Current(0, AvatarLedger.Kind.ABSENT, null, null)
        }
        override fun apply(secret: String, intent: AvatarLedger.Intent, png: CanonicalAvatarPng?,
                           requestFence: () -> Unit): AvatarLedger.Receipt {
            requestFence(); if (!permitted) throw AvatarAuthority.Denied()
            writes++; received = png?.copyBytes()
            return AvatarLedger.Receipt(intent.scope, intent.operation, intent.digest(), intent.action,
                1, png?.sha256, intent.sourceGeneration, Instant.EPOCH)
        }
        override fun originalReceipt(secret: String, installation: UUID, operation: UUID, intentDigest: String,
                                     restore: UUID, requestFence: () -> Unit): AvatarLedger.Receipt? {
            requestFence(); recoveryReads++; return null
        }
    }
    private fun timeout(request: Request) {
        val context = request.asyncContext as MockAsyncContext
        context.listeners.toList().forEach { it.onTimeout(AsyncEvent(context)) }
    }

    @Test fun `authorized PUT receives bytes nonblocking after preflight and preserves canonical content`() {
        val ops = Ops(); val servlet = AvatarMachineServlet(ops); val bytes = png()
        val request = request(bytes); val response = MockHttpServletResponse()
        servlet.service(request, response)
        assertEquals(1, ops.preflights); assertEquals(0, request.source.consumed); assertEquals(0, ops.writes)
        request.source.deliver()
        assertEquals(200, response.status); assertEquals(1, ops.writes); assertContentEquals(bytes, ops.received)
        assertTrue(response.contentAsString.contains("intentDigest")); assertFalse(response.contentAsString.contains(secret))
        assertEquals("no-store", response.getHeader("Cache-Control")); assertNull(request.getSession(false))
    }
    @Test fun `denied preflight consumes no body and revocation during upload refuses later apply`() {
        val ops = Ops(); val servlet = AvatarMachineServlet(ops)
        ops.permitted = false
        val denied = request(png()); val deniedResponse = MockHttpServletResponse()
        servlet.service(denied, deniedResponse)
        assertEquals(403, deniedResponse.status); assertEquals(0, denied.source.consumed)
        ops.permitted = true
        val started = request(png()); val response = MockHttpServletResponse()
        servlet.service(started, response); ops.permitted = false; started.source.deliver()
        assertEquals(403, response.status); assertEquals(0, ops.writes)
    }
    @Test fun `unknown length overrun reads at most limit plus one and malformed PNG never writes`() {
        val ops = Ops(); val servlet = AvatarMachineServlet(ops)
        val request = request(ByteArray(CanonicalAvatarPng.MAX_BYTES + 200), unknownLength = true)
        val response = MockHttpServletResponse(); servlet.service(request, response); request.source.deliver()
        assertEquals(400, response.status); assertEquals(CanonicalAvatarPng.MAX_BYTES + 1, request.source.consumed)
        val malformed = request(byteArrayOf(1, 2, 3)); val refused = MockHttpServletResponse()
        servlet.service(malformed, refused); malformed.source.deliver()
        assertEquals(400, refused.status); assertEquals(0, ops.writes)
    }
    @Test fun `two uploads bound concurrency and timeout releases only its own admission`() {
        val ops = Ops(); val servlet = AvatarMachineServlet(ops)
        val first = request(png()); val second = request(png())
        servlet.service(first, MockHttpServletResponse()); servlet.service(second, MockHttpServletResponse())
        val busy = MockHttpServletResponse(); servlet.service(request(png()), busy); assertEquals(503, busy.status)
        timeout(first); first.source.deliver(); assertEquals(0, ops.writes)
        val current = MockHttpServletResponse(); servlet.service(request(method = "GET", path = "/current"), current)
        assertEquals(200, current.status)
        timeout(second)
    }
    @Test fun `blocked timeout output retains its permit until output completes`() {
        val ops = Ops(); val servlet = AvatarMachineServlet(ops)
        val entered = CountDownLatch(1); val allow = CountDownLatch(1)
        val response = object : MockHttpServletResponse() {
            override fun getOutputStream(): ServletOutputStream = object : ServletOutputStream() {
                override fun isReady() = false
                override fun setWriteListener(listener: WriteListener) = Unit
                override fun write(value: Int) { entered.countDown(); check(allow.await(3, TimeUnit.SECONDS)) }
            }
        }
        val first = request(png()); val second = request(png())
        servlet.service(first, response); servlet.service(second, MockHttpServletResponse())
        val executor = Executors.newSingleThreadExecutor()
        try {
            val timingOut = executor.submit { timeout(first) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val busy = MockHttpServletResponse(); servlet.service(request(png()), busy); assertEquals(503, busy.status)
            allow.countDown(); timingOut.get(3, TimeUnit.SECONDS)
            val current = MockHttpServletResponse(); servlet.service(request(method = "GET", path = "/current"), current)
            assertEquals(200, current.status)
        } finally { allow.countDown(); timeout(second); executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)) }
    }
    @Test fun `async setup failure completes owned context and does not overrelease slots`() {
        val ops = Ops(); val servlet = AvatarMachineServlet(ops)
        var completed = false
        val broken = object : Request(png()) {
            override fun startAsync(): AsyncContext {
                val real = super.startAsync()
                return Proxy.newProxyInstance(AsyncContext::class.java.classLoader, arrayOf(AsyncContext::class.java)) { _, call, args ->
                    if (call.name == "setTimeout") throw IllegalStateException("Synthetic setup failure")
                    if (call.name == "complete") completed = true
                    try { call.invoke(real, *(args ?: emptyArray())) }
                    catch (failure: InvocationTargetException) { throw failure.targetException }
                } as AsyncContext
            }
        }.apply {
            method = "PUT"; pathInfo = "/avatar"; contentType = "image/png"
            addHeader("Authorization", "Bearer $secret"); addHeader(AvatarMachineProtocol.HEADER, pin(png()))
        }
        val failed = MockHttpServletResponse(); servlet.service(broken, failed)
        assertEquals(503, failed.status); assertTrue(completed)
        val first = request(png()); val second = request(png())
        servlet.service(first, MockHttpServletResponse()); servlet.service(second, MockHttpServletResponse())
        val busy = MockHttpServletResponse(); servlet.service(request(png()), busy); assertEquals(503, busy.status)
        timeout(first); timeout(second)
    }
    @Test fun `strict headers and JSON refuse cookies origin duplicate or trailing authority claims`() {
        val ops = Ops(); val servlet = AvatarMachineServlet(ops)
        for (mutate in listOf<(Request) -> Unit>(
            { it.removeHeader("Authorization"); it.addHeader("Cookie", "JSESSIONID=synthetic") },
            { it.addHeader("Authorization", "Bearer $secret") },
            { it.addHeader("Origin", "https://example.invalid") },
            { it.removeHeader(AvatarMachineProtocol.HEADER); it.addHeader(AvatarMachineProtocol.HEADER, pin(png()) + "{}") },
            { it.removeHeader(AvatarMachineProtocol.HEADER); it.addHeader(AvatarMachineProtocol.HEADER, "{\"scope\":{},\"scope\":{}}") }
        )) {
            val request = request(png()); mutate(request)
            val response = MockHttpServletResponse(); servlet.service(request, response)
            assertTrue(response.status in listOf(400, 503)); assertEquals(0, request.source.consumed)
            assertFalse(response.contentAsString.contains(secret))
        }
        assertEquals(0, ops.preflights); assertEquals(0, ops.writes)
    }
    @Test fun `receipt route reports unknown and invokes no current read or write`() {
        val ops = Ops(); val servlet = AvatarMachineServlet(ops)
        val request = request(method = "GET", path = "/receipt").apply {
            removeHeader(AvatarMachineProtocol.HEADER)
            addHeader(AvatarMachineProtocol.HEADER, String(AvatarMachineProtocol.json(mapOf(
                "installation" to scope.installation.toString(), "operation" to UUID.randomUUID().toString(),
                "intentDigest" to "e".repeat(64), "restore" to scope.restore.toString()))))
        }
        val response = MockHttpServletResponse(); servlet.service(request, response)
        assertEquals(200, response.status); assertEquals("{\"status\":\"UNKNOWN\"}", response.contentAsString)
        assertEquals(1, ops.recoveryReads); assertEquals(0, ops.reads); assertEquals(0, ops.writes)
    }
    private fun png(): ByteArray {
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        return try { ByteArrayOutputStream().use { check(javax.imageio.ImageIO.write(image, "PNG", it)); it.toByteArray() } }
        finally { image.flush() }
    }
}
