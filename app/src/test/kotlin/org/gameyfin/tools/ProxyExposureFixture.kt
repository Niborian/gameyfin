package org.gameyfin.tools

import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

/** Opt-in synthetic acceptance, not an application endpoint or production configurator. */
object ProxyExposureFixture {
    private const val LIMIT = 1024 * 1024
    internal fun downloadPath(value: String): String {
        val uri = URI(value)
        require(!uri.isAbsolute && uri.rawAuthority == null && value.startsWith("/download/") && !value.startsWith("//"))
        require(!uri.path.contains('\\') && uri.path.split('/').none { it == "." || it == ".." })
        return value
    }
    internal fun csrf(html: String): String = requireNotNull(Regex("<meta name=\"_csrf\" content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)) { "Fixture CSRF absent" }
    internal fun archive(bytes: ByteArray, expected: Map<String, String>) {
        require(bytes.size <= LIMIT && expected.isNotEmpty() && expected.size <= 4096)
        val seen = mutableSetOf<String>(); var expanded = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val member = zip.nextEntry ?: break
                require(seen.add(member.name) && member.name in expected && !member.isDirectory) { "Fixture archive membership" }
                val body = zip.readNBytes(LIMIT - expanded + 1); expanded += body.size
                require(expanded <= LIMIT) { "Fixture expanded byte bound" }
                val digest = requireNotNull(expected[member.name]); require(digest.matches(Regex("[a-f0-9]{64}")))
                require(FixtureRuntime.sha(body) == digest) { "Fixture member digest" }
                zip.closeEntry()
            }
        }
        require(seen == expected.keys) { "Fixture missing member" }
    }
    internal fun runtimeBindings(value: tools.jackson.databind.JsonNode, port: Int) {
        require(value.isObject && value.size() == 1 && value.has("8080/tcp"))
        val bindings = value["8080/tcp"]
        require(bindings.isArray && bindings.size() == 1 && bindings[0]["HostIp"].asText() == "127.0.0.1" && bindings[0]["HostPort"].asText() == port.toString()) { "Fixture proxy exact loopback binding" }
    }
    internal fun loggedOut(status: Int, location: String?, origin: String) {
        val base = URI(origin); val target = base.resolve(requireNotNull(location))
        require(status == 302 && target.scheme == base.scheme && target.rawAuthority == base.rawAuthority && target.path == "/login") { "Fixture logout anonymous redirect absent" }
    }
    internal fun context(value: String): tools.jackson.databind.JsonNode {
        require(value.toByteArray().size <= LIMIT) { "Fixture context byte bound" }
        return FixtureRuntime.tree(value)
    }
    internal fun internalNetwork(value: tools.jackson.databind.JsonNode) {
        require(value["Internal"].let { it != null && it.isBoolean && it.booleanValue() })
    }
    internal fun smoke(origin: String, cases: List<Pair<String, Map<String, String>>>, username: String, password: String, deadline: FixtureDeadline) {
        val base = URI(origin)
        require(base.rawPath.orEmpty().isEmpty() && base.rawQuery == null && base.rawFragment == null && base.rawUserInfo == null)
        require(base.scheme == "https" || base.scheme == "http" && base.host in listOf("localhost", "127.0.0.1", "[::1]", "::1"))
        require(cases.size in 2..8 && username.isNotBlank() && password.isNotBlank())
        cases.forEach { downloadPath(it.first) }
        val client = FixtureHttp(origin, deadline = { deadline.remaining() })
        var token = csrf(client.following("/login").toString(Charsets.UTF_8))
        client.following("/login", FixtureHttp.form(mapOf("username" to username, "password" to password, "_csrf" to token)), mapOf("Content-Type" to "application/x-www-form-urlencoded"))
        token = csrf(client.following("/").toString(Charsets.UTF_8))
        val identity = client.request("/connect/UserEndpoint/getUserInfo", "{}", mapOf("Content-Type" to "application/json", "X-CSRF-TOKEN" to token))
        require(identity.status == 200 && FixtureRuntime.tree(identity.bytes.toString(Charsets.UTF_8))["username"].asText() == username)
        for ((path, members) in cases) archive(client.following(path), members)
        client.following("/logout", FixtureHttp.form(mapOf("_csrf" to token)), mapOf("Content-Type" to "application/x-www-form-urlencoded"))
        val anonymous = client.request("/"); loggedOut(anonymous.status, anonymous.location, origin)
        deadline.remaining()
    }
    @JvmStatic fun run(context: String, username: String, password: String, dockerPrefix: List<String>): String {
        FixtureRuntime.hosted()
        var deadline = FixtureDeadline(180)
        require(username.isNotBlank() && password.isNotBlank())
        val input = ProxyExposureFixture.context(context)
        val backend = input["backend"].asText(); val network = input["network"].asText(); val image = input["proxyImage"].asText()
        require(backend.matches(Regex("gameyfin-scan-[a-f0-9]{16}")) && network.matches(Regex("gameyfin-scan-net-[a-f0-9]{16}")))
        require(image.matches(Regex("sha256:[a-f0-9]{64}")))
        val manifest = input["fixtureManifest"]; require(manifest["scope"].asText() == "synthetic offline fixture")
        val game = manifest["games"][0]; val provider = input["provider"].asText()
        fun docker(vararg args: String) = FixtureRuntime.command(dockerPrefix, *args, seconds = minOf(60, deadline.remaining()))
        val backendState = FixtureRuntime.tree(docker("inspect", backend))[0]
        require(backendState["HostConfig"]["PortBindings"].let { it.isNull || it.isObject && it.isEmpty })
        require(backendState["NetworkSettings"]["Networks"].has(network))
        internalNetwork(FixtureRuntime.tree(docker("network", "inspect", network))[0])
        val root = FixtureRuntime.temporary("gameyfin-proxy-smoke-")
        val name = "gameyfin-proxy-smoke-" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        var container: String? = null
        var creationAttempted = false
        var relay: FixtureRelay? = null
        var removed = false
        try {
            val config = FixtureRuntime.repository().resolve("docker/exposure-fixture/nginx.conf")
            require(Files.isRegularFile(config) && !Files.isSymbolicLink(config))
            val copied = root.resolve("nginx.conf")
            Files.writeString(copied, Files.readString(config).replace("http://gameyfin:8080", "http://$backend:8080"))
            creationAttempted = true
            container = docker("create", "--pull", "never", "--name", name, "--label", "gameyfin.fixture.owner=$name", "--network", network, "--restart", "no", "--cpus", "0.5", "--memory", "128m", "--memory-swap", "128m", "--mount", "type=bind,src=$copied,dst=/etc/nginx/conf.d/default.conf,readonly", image)
            require(container.matches(Regex("[a-f0-9]{64}")))
            val state = FixtureRuntime.tree(docker("inspect", container))[0]
            require(state["Name"].asText() == "/$name" && state["Image"].asText() == image)
            require(state["HostConfig"]["PortBindings"].let { it.isNull || it.isObject && it.isEmpty })
            docker("start", container)
            val started = FixtureRuntime.tree(docker("inspect", container))[0]
            relay = FixtureRelay(started["NetworkSettings"]["Networks"][network]["IPAddress"].asText(), 8080)
            val client = FixtureHttp(relay.origin, deadline = { deadline.remaining() })
            FixtureRuntime.await(30, { deadline.remaining() }) { client.request("/login", seconds = 2).status == 200 }
            val cases = listOf(false, true).map { optional ->
                val required = game["requiredContentId"].asText(); val ids = if (optional) required + "," + game["optionalContentId"].asText() else required
                val query = FixtureHttp.form(mapOf("provider" to provider, "variantId" to game["variantId"].asText(), "contentIds" to ids, "explicitSelection" to "true"))
                val expected = linkedMapOf<String, String>()
                val requiredMapping = game["requiredArchiveMembers"]
                require(requiredMapping.isObject && !requiredMapping.isEmpty)
                for ((archiveName, member) in requiredMapping.properties()) expected[archiveName] = manifest["sha256"][member.asText()].asText()
                if (optional) {
                    val optionalMapping = game["optionalArchiveMembers"]; require(optionalMapping.isObject && optionalMapping.size() == 1)
                    for ((archiveName, member) in optionalMapping.properties()) require(expected.put(archiveName, manifest["sha256"][member.asText()].asText()) == null)
                }
                downloadPath("/download/" + game["gameId"].asText() + "?" + query) to expected.toMap()
            }
            smoke(relay.origin, cases, username, password, deadline)
        } finally {
            deadline = FixtureDeadline(40)
            try { relay?.close() } finally {
                if (creationAttempted) {
                    val matches = docker("ps", "-aq", "--no-trunc", "--filter", "name=^/$name$").lines().filter { it.isNotBlank() }
                    require(matches.size <= 1)
                    for (id in matches) {
                        require(id.matches(Regex("[a-f0-9]{64}")) && (container == null || container == id))
                        val state = FixtureRuntime.tree(docker("inspect", id))[0]
                        require(state["Id"].asText() == id && state["Name"].asText() == "/$name" && state["Image"].asText() == image && state["Config"]["Labels"]["gameyfin.fixture.owner"].asText() == name && state["HostConfig"]["NetworkMode"].asText() == network)
                        val mounts = state["Mounts"]; require(mounts.size() == 1 && mounts[0]["Source"].asText() == root.resolve("nginx.conf").toString() && !mounts[0]["RW"].booleanValue())
                        docker("rm", "-f", id)
                        require(id !in docker("ps", "-aq", "--no-trunc").lines()) { "Exact fixture proxy removal unconfirmed" }
                    }
                    if (container != null) require(container !in docker("ps", "-aq", "--no-trunc").lines()) { "Known fixture proxy identity remains; configuration retained" }
                }
                removed = true
                FixtureRuntime.deleteOwned(root)
            }
        }
        require(removed)
        return FixtureRuntime.json.writeValueAsString(mapOf("proxyDownloadSmoke" to "PASS", "caseCount" to 2, "exactMembersAndHashes" to true, "authenticatedIdentity" to true, "logoutAnonymous" to true, "proxyImage" to image, "ownedResourcesRemoved" to true))
    }
}
