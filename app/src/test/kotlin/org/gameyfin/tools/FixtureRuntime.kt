package org.gameyfin.tools

import java.net.CookieManager
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Test-runtime support only. No endpoint discovery, host ports, real source paths or production authority. */
internal object FixtureRuntime {
    private val ownedRoots = java.util.concurrent.ConcurrentHashMap<Path, Any>()
    val json: JsonMapper = JsonMapper.builder().build()
    fun tree(value: String): JsonNode = json.readTree(value)
    fun sha(bytes: ByteArray, algorithm: String = "SHA-256") = MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }
    fun hosted() { require(System.getenv("GITHUB_ACTIONS") == "true") { "Disposable Actions runner required" } }
    fun repository(): Path {
        var candidate: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        repeat(4) {
            val path = candidate ?: error("Fixture repository absent")
            if (Files.isRegularFile(path.resolve("docker/exposure-fixture/compose.yml")) && Files.isRegularFile(path.resolve("app/build.gradle.kts"))) return path
            candidate = path.parent
        }
        error("Fixture repository absent")
    }
    fun command(prefix: List<String>, vararg args: String, seconds: Long = 60): String {
        require(prefix.isNotEmpty())
        val process = ProcessBuilder(prefix + args).redirectErrorStream(true).start()
        val pool = Executors.newSingleThreadExecutor()
        val output = pool.submit<ByteArray> { process.inputStream.use { input ->
            val bytes = input.readNBytes(4 * 1024 * 1024 + 1)
            require(bytes.size <= 4 * 1024 * 1024) { "Fixture process output bound" }; bytes
        } }
        try {
            check(process.waitFor(seconds, TimeUnit.SECONDS)) { "Fixture process deadline" }
            val bytes = output.get(5, TimeUnit.SECONDS)
            check(process.exitValue() == 0) { "Fixture process refused" }
            return bytes.toString(Charsets.UTF_8).trim()
        } finally {
            var terminal = !process.isAlive
            try {
                if (!terminal) {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); terminal = process.waitFor(2, TimeUnit.SECONDS) }
                    else terminal = true
                }
            } finally {
                var pipeClosed = false
                try {
                    process.inputStream.close()
                    pipeClosed = true
                } finally {
                    output.cancel(true)
                    pool.shutdownNow()
                    check(pool.awaitTermination(2, TimeUnit.SECONDS) && terminal && pipeClosed) {
                        "Fixture process cleanup uncertain"
                    }
                }
            }
        }
    }
    fun temporary(prefix: String): Path {
        require(prefix.matches(Regex("gameyfin-[a-z-]+-")))
        val root = Files.createTempDirectory(prefix).toAbsolutePath().normalize()
        ownedRoots[root] = requireNotNull(Files.readAttributes(root, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey())
        return root
    }
    fun deleteOwned(root: Path) {
        require(root.isAbsolute && root == root.normalize() && !Files.isSymbolicLink(root))
        val identity = requireNotNull(ownedRoots[root]) { "Unregistered fixture root" }
        require(Files.readAttributes(root, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey() == identity) { "Fixture root identity changed" }
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        check(!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)); ownedRoots.remove(root)
    }
    fun await(seconds: Long, deadline: (() -> Long)? = null, action: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < end) {
            deadline?.invoke()
            if (try { action() } catch (_: Exception) { false }) return
            Thread.sleep(1000)
        }
        error("Fixture readiness deadline")
    }
}

internal class FixtureDeadline(seconds: Long) {
    private val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
    fun remaining(): Long = TimeUnit.NANOSECONDS.toSeconds(end - System.nanoTime()).also { check(it >= 1) { "Fixture total deadline" } }
}

/** One exact private upstream, ephemeral IPv4 loopback listener; closes every owned connection. */
internal class FixtureRelay(private val address: String, private val upstreamPort: Int) : AutoCloseable {
    private val target = run {
        require(address.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) && upstreamPort in 1..65535)
        InetAddress.getByName(address).also { require(it.isSiteLocalAddress) }
    }
    private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
    private val pool = Executors.newFixedThreadPool(17)
    private val admission = java.util.concurrent.Semaphore(8)
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var closed = false
    val origin = "http://127.0.0.1:${server.localPort}"
    init {
        pool.submit {
            while (!closed) {
                val incoming = try { server.accept() } catch (_: Exception) { break }
                if (!admission.tryAcquire()) { incoming.close(); continue }
                sockets.add(incoming)
                pool.submit {
                    val upstream = Socket()
                    try {
                        sockets.add(upstream); upstream.connect(InetSocketAddress(target, upstreamPort), 10000)
                        incoming.soTimeout = 30000; upstream.soTimeout = 30000
                        val reverse = pool.submit { try { upstream.getInputStream().copyTo(incoming.getOutputStream()) } finally { incoming.close() } }
                        try { incoming.getInputStream().copyTo(upstream.getOutputStream()) } finally { upstream.close() }
                        reverse.get(5, TimeUnit.SECONDS)
                    } catch (_: Exception) { /* Closed or bounded synthetic connection. */ }
                    finally { incoming.close(); upstream.close(); sockets.remove(incoming); sockets.remove(upstream); admission.release() }
                }
            }
        }
    }
    override fun close() {
        closed = true; server.close(); sockets.forEach { it.close() }; pool.shutdownNow()
        check(pool.awaitTermination(5, TimeUnit.SECONDS)) { "Fixture relay cleanup uncertain" }
    }
}

internal class FixtureHttp(val origin: String, private val limit: Int = 1024 * 1024, private val deadline: (() -> Long)? = null) {
    val cookies = CookieManager()
    private val client = HttpClient.newBuilder().cookieHandler(cookies).proxy(java.net.ProxySelector.of(null)).connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    data class Reply(val status: Int, val bytes: ByteArray, val location: String?)
    fun request(path: String, body: String? = null, headers: Map<String, String> = emptyMap(), seconds: Long = 30, redirects: Int = 0): Reply {
        require(redirects <= 8)
        val base = URI(origin); val target = base.resolve(path)
        require(target.scheme == base.scheme && target.rawAuthority == base.rawAuthority && target.rawUserInfo == null) { "Fixture same-origin boundary" }
        val allowed = minOf(seconds, deadline?.invoke() ?: seconds)
        val request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(allowed))
        headers.forEach { (key, value) -> request.header(key, value) }
        request.method(if (body == null) "GET" else "POST", if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        val pending = client.sendAsync(request.build(), HttpResponse.BodyHandler { LimitedBody(limit) })
        try {
            val response = pending.get(allowed, TimeUnit.SECONDS)
            return Reply(response.statusCode(), response.body(), response.headers().firstValue("Location").orElse(null))
        } finally { if (!pending.isDone) pending.cancel(true) }
    }
    fun following(path: String, body: String? = null, headers: Map<String, String> = emptyMap(), depth: Int = 0): ByteArray {
        require(depth <= 8)
        val reply = request(path, body, headers)
        if (reply.status in listOf(301, 302, 303, 307, 308)) return following(requireNotNull(reply.location), if (reply.status in listOf(307, 308)) body else null, headers, depth + 1)
        check(reply.status == 200) { "Fixture HTTP refusal" }; return reply.bytes
    }
    companion object {
        fun form(values: Map<String, String>) = values.entries.joinToString("&") { java.net.URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + java.net.URLEncoder.encode(it.value, Charsets.UTF_8) }
    }
    internal class LimitedBody(private val limit: Int) : HttpResponse.BodySubscriber<ByteArray> {
        private val result = CompletableFuture<ByteArray>()
        private val bytes = ByteArrayOutputStream()
        private var subscription: Flow.Subscription? = null
        override fun getBody(): CompletionStage<ByteArray> = result
        override fun onSubscribe(value: Flow.Subscription) { subscription = value; value.request(1) }
        override fun onNext(items: List<ByteBuffer>) {
            try {
                for (item in items) {
                    require(item.remaining() <= limit - bytes.size()) { "Fixture HTTP byte bound" }
                    val part = ByteArray(item.remaining()); item.get(part); bytes.write(part)
                }
                subscription?.request(1)
            } catch (failure: Exception) { subscription?.cancel(); result.completeExceptionally(failure) }
        }
        override fun onError(error: Throwable) { result.completeExceptionally(error) }
        override fun onComplete() { result.complete(bytes.toByteArray()) }
    }
}

/** Creation intent exists before Docker executes, so timeout cannot orphan an unrecorded named resource. */
internal class FixtureContainers(val root: Path, private val prefix: List<String> = listOf("docker")) : AutoCloseable {
    val owner = java.util.UUID.randomUUID().toString().replace("-", "")
    val network = "gameyfin-fixture-$owner"
    private val intents = linkedMapOf<String, String>()
    private val identities = linkedMapOf<String, String>()
    private var networkAttempted = false
    fun docker(vararg args: String, seconds: Long = 60) = FixtureRuntime.command(prefix, *args, seconds = seconds)
    fun createNetwork() {
        networkAttempted = true
        docker("network", "create", "--internal", "--label", "gameyfin.fixture.owner=$owner", network)
        val row = FixtureRuntime.tree(docker("network", "inspect", network))[0]
        require(row["Internal"].booleanValue() && row["Labels"]["gameyfin.fixture.owner"].asText() == owner)
    }
    fun create(role: String, image: String, args: List<String>, command: List<String> = emptyList()): String {
        require(role.matches(Regex("[a-z]+")) && image.matches(Regex("[a-z0-9./-]+@sha256:[a-f0-9]{64}")))
        val name = "gameyfin-fixture-$owner-$role"; require(!intents.containsKey(name)); intents[name] = image
        val id = docker(*(listOf("create", "--pull", "never", "--name", name, "--label", "gameyfin.fixture.owner=$owner", "--network", network) + args + image + command).toTypedArray())
        require(id.matches(Regex("[a-f0-9]{64}"))); identities[name] = id; verified(name, image, id)
        docker("start", id); return id
    }
    private fun verified(name: String, image: String, id: String) {
        val row = FixtureRuntime.tree(docker("inspect", id))[0]
        require(row["Id"].asText() == id && row["Name"].asText() == "/$name" && row["Config"]["Image"].asText() == image && row["Config"]["Labels"]["gameyfin.fixture.owner"].asText() == owner)
        require(row["HostConfig"]["NetworkMode"].asText() == network && row["HostConfig"]["PortBindings"].let { it.isNull || it.isObject && it.isEmpty })
        for (mount in row["Mounts"]) require(mount["Type"].asText() == "bind" && Path.of(mount["Source"].asText()).toAbsolutePath().normalize().startsWith(root))
    }
    fun address(id: String): String = FixtureRuntime.tree(docker("inspect", id))[0]["NetworkSettings"]["Networks"][network]["IPAddress"].asText()
    override fun close() {
        var failed = false
        for ((name, image) in intents.entries.reversed()) {
            try {
                val ids = docker("ps", "-aq", "--no-trunc", "--filter", "name=^/$name$").lines().filter { it.isNotBlank() }
                require(ids.size <= 1)
                for (id in ids) {
                    require(identities[name] == null || identities[name] == id)
                    verified(name, image, id); docker("rm", "-f", id)
                    require(id !in docker("ps", "-aq", "--no-trunc").lines())
                }
                identities[name]?.let { known -> require(known !in docker("ps", "-aq", "--no-trunc").lines()) }
            } catch (_: Exception) { failed = true }
        }
        if (networkAttempted && !failed) {
            try {
                val ids = docker("network", "ls", "-q", "--filter", "name=^$network$").lines().filter { it.isNotBlank() }
                require(ids.size <= 1)
                if (ids.isNotEmpty()) {
                    val row = FixtureRuntime.tree(docker("network", "inspect", network))[0]
                    require(row["Name"].asText() == network && row["Internal"].booleanValue() && row["Labels"]["gameyfin.fixture.owner"].asText() == owner && row["Containers"].isEmpty)
                    docker("network", "rm", network)
                    require(docker("network", "ls", "-q", "--filter", "name=^$network$").isBlank())
                }
            } catch (_: Exception) { failed = true }
        }
        check(!failed) { "Fixture resource cleanup uncertain; configuration retained" }
        FixtureRuntime.deleteOwned(root)
    }
}
