package org.gameyfin.app.users.avatar

import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncContext
import jakarta.servlet.AsyncListener
import jakarta.servlet.ReadListener
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.ByteArrayOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** No self-registration; companion HTTP configuration requires explicit machine
 * AND native overlay enablement plus trusted operations. Both default off.
 * Trusted bootstrap and real connector/exposure acceptance remain required.
 * No sessions/cookies/remote fetch/provisioning. Body reception is nonblocking;
 * connector header/output deadlines still need actual container acceptance.
 */
class AvatarMachineServlet(private val operations: AvatarMachineOperations) : HttpServlet() {
    private val slots = Semaphore(2)
    private class Expired : IllegalStateException()

    public override fun service(request: HttpServletRequest, response: HttpServletResponse) {
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("X-Content-Type-Options", "nosniff")
        if (!slots.tryAcquire()) { error(response, 503); return }
        val slotReleased = AtomicBoolean(false)
        fun releaseSlot() { if (slotReleased.compareAndSet(false, true)) slots.release() }
        var ownedContext: AsyncContext? = null
        val started = System.nanoTime()
        try {
            require(request.queryString == null && request.getHeader("Origin") == null)
            require(request.getHeader("Content-Encoding") == null)
            val secret = header(request, "Authorization").let {
                require(it.matches(Regex("Bearer [A-Za-z0-9_-]{43}"))); it.substring(7)
            }
            val pin = AvatarMachineProtocol.parse(header(request, AvatarMachineProtocol.HEADER))
            val route = request.pathInfo
            require(route in setOf("/current", "/avatar", "/receipt"))
            require(request.isAsyncSupported)
            val context = request.startAsync()
            ownedContext = context
            context.timeout = 10_000
            val cancelled = AtomicBoolean(false)
            val monitor = Any()
            var active = 0
            var finished = false
            var released = false
            fun releaseIfIdle() {
                if (finished && active == 0 && !released) { released = true; releaseSlot() }
            }
            fun finish(status: Int, body: ByteArray) {
                val won = synchronized(monitor) {
                    if (finished) false else { finished = true; active++; true }
                }
                if (!won) return
                try {
                    response.status = status
                    response.contentType = "application/json"
                    response.setContentLength(body.size)
                    response.outputStream.write(body)
                } catch (_: Exception) {
                    // Lost response is unknown; original receipt remains the only recovery authority.
                } finally {
                    try { context.complete() } catch (_: Exception) { }
                    synchronized(monitor) { active--; releaseIfIdle() }
                }
            }
            fun refused(failure: Throwable) {
                val status = when (failure) {
                    is Expired -> 504
                    is AvatarAuthority.Denied -> 403
                    is AvatarLedger.Conflict, is AvatarLedger.UnsupportedLegacy -> 409
                    is IllegalArgumentException -> 400
                    else -> 503 // Includes uncertain JDBC commit; never echo cause or retry.
                }
                finish(status, REFUSED)
            }
            val fence = {
                if (cancelled.get() || System.nanoTime() - started >= 10_000_000_000L) throw Expired()
            }
            fun callback(work: () -> Unit) {
                val admitted = synchronized(monitor) { if (finished) false else { active++; true } }
                if (!admitted) return
                try { fence(); work() } catch (failure: Exception) { refused(failure) }
                finally { synchronized(monitor) { active--; releaseIfIdle() } }
            }
            context.addListener(object : AsyncListener {
                override fun onTimeout(event: AsyncEvent) { cancelled.set(true); finish(504, REFUSED) }
                override fun onError(event: AsyncEvent) { cancelled.set(true); finish(503, REFUSED) }
                override fun onComplete(event: AsyncEvent) {
                    cancelled.set(true)
                    synchronized(monitor) { finished = true; releaseIfIdle() }
                }
                override fun onStartAsync(event: AsyncEvent) { cancelled.set(true); finish(503, REFUSED) }
            })
            callback {
                when {
                    route == "/current" && request.method == "GET" -> {
                        noBody(request)
                        val current = operations.current(secret, AvatarMachineProtocol.scope(pin), fence)
                        finish(200, AvatarMachineProtocol.json(mapOf("version" to current.version,
                            "kind" to current.kind.name, "hash" to current.hash)))
                    }
                    route == "/receipt" && request.method == "GET" -> {
                        noBody(request)
                        val recovery = AvatarMachineProtocol.recovery(pin)
                        val receipt = operations.originalReceipt(secret, recovery.installation, recovery.operation,
                            recovery.intentDigest, recovery.restore, fence)
                        finish(200, AvatarMachineProtocol.json(if (receipt == null) mapOf("status" to "UNKNOWN")
                            else mapOf("status" to "COMMITTED", "receipt" to AvatarMachineProtocol.receipt(receipt))))
                    }
                    route == "/avatar" && request.method == "DELETE" -> {
                        noBody(request)
                        val intent = AvatarMachineProtocol.intent(pin, AvatarLedger.Action.REMOVE)
                        val receipt = operations.apply(secret, intent, null, fence)
                        finish(200, AvatarMachineProtocol.json(AvatarMachineProtocol.receipt(receipt)))
                    }
                    route == "/avatar" && request.method == "PUT" -> {
                        require(request.contentType == "image/png")
                        require(request.contentLengthLong == -1L || request.contentLengthLong in 1..CanonicalAvatarPng.MAX_BYTES.toLong())
                        val intent = AvatarMachineProtocol.intent(pin, AvatarLedger.Action.REPLACE)
                        operations.preflight(secret, intent.scope, fence) // No body bytes before native admission.
                        val input = request.inputStream
                        val body = ByteArrayOutputStream(4096)
                        val buffer = ByteArray(4096)
                        input.setReadListener(object : ReadListener {
                            override fun onDataAvailable() = callback {
                                while (input.isReady && !input.isFinished) {
                                    fence()
                                    val limit = minOf(buffer.size, CanonicalAvatarPng.MAX_BYTES + 1 - body.size())
                                    require(limit > 0)
                                    val count = input.read(buffer, 0, limit)
                                    if (count == -1) break
                                    if (count == 0) break
                                    body.write(buffer, 0, count)
                                    require(body.size() <= CanonicalAvatarPng.MAX_BYTES)
                                }
                            }
                            override fun onAllDataRead() = callback {
                                require(request.contentLengthLong < 0 || request.contentLengthLong == body.size().toLong())
                                fence()
                                val png = CanonicalAvatarPng.validate(body.toByteArray(), requireNotNull(intent.desiredHash))
                                fence()
                                val receipt = operations.apply(secret, intent, png, fence)
                                finish(200, AvatarMachineProtocol.json(AvatarMachineProtocol.receipt(receipt)))
                            }
                            override fun onError(failure: Throwable) { cancelled.set(true); finish(400, REFUSED) }
                        })
                    }
                    else -> finish(405, REFUSED)
                }
            }
        } catch (failure: Exception) {
            // Only setup before async ownership. No token/header/parser detail in response/log.
            try { error(response, if (failure is IllegalArgumentException) 400 else 503) }
            finally {
                try { ownedContext?.complete() } catch (_: Exception) { }
                releaseSlot()
            }
        }
    }

    private fun header(request: HttpServletRequest, name: String): String {
        val values = request.getHeaders(name)
        require(values.hasMoreElements())
        val value = values.nextElement()
        require(!values.hasMoreElements() && value.length in 1..if (name == "Authorization") 50 else 4096)
        return value
    }
    private fun noBody(request: HttpServletRequest) {
        require(request.contentLengthLong == 0L && request.getHeader("Transfer-Encoding") == null)
    }
    private fun error(response: HttpServletResponse, status: Int) {
        response.status = status; response.contentType = "application/json"
        response.setContentLength(REFUSED.size); response.outputStream.write(REFUSED)
    }
    companion object { private val REFUSED = "{\"status\":\"REFUSED\"}".toByteArray(Charsets.US_ASCII) }
}
