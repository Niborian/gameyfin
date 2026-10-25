package org.gameyfin.tools

/** Read-only inspection of the explicit synthetic Compose fixture; never starts or configures services. */
object ExposureFixtureRehearsal {
    internal fun configuration(value: tools.jackson.databind.JsonNode): Int {
        val backend = value["services"]["gameyfin"]; val proxy = value["services"]["proxy"]
        require(backend["ports"].let { it == null || it.isNull || it.isEmpty }) { "Fixture backend must not publish ports" }
        val ports = proxy["ports"]
        require(ports.isArray && ports.size() == 1 && ports[0]["host_ip"].asText() == "127.0.0.1")
        val port = ports[0]["published"].asText().toInt(); require(port in 1..65535)
        require(backend["volumes"].all { it["type"].asText() == "volume" }) { "Fixture backend named volumes only" }
        return port
    }
    @JvmStatic fun main(args: Array<String>) {
        require(args.isEmpty() || args.contentEquals(arrayOf("--configuration-only")))
        require(System.getenv("GAMEYFIN_EXPOSURE_FIXTURE_ACK") == "true") { "Explicit isolated fixture acknowledgement required" }
        val compose = arrayOf("compose", "-f", FixtureRuntime.repository().resolve("docker/exposure-fixture/compose.yml").toString())
        fun docker(vararg tail: String) = FixtureRuntime.command(listOf("docker"), *tail)
        val config = FixtureRuntime.tree(docker(*compose, "config", "--format", "json"))
        val port = configuration(config)
        if (args.isNotEmpty()) { println("PASS: isolated named volumes, no backend ports, loopback-only proxy configuration"); return }
        fun service(name: String): tools.jackson.databind.JsonNode {
            val id = docker(*compose, "ps", "-q", name); require(id.matches(Regex("[a-f0-9]{64}")))
            return FixtureRuntime.tree(docker("inspect", id))[0]
        }
        require(service("gameyfin")["HostConfig"]["PortBindings"].let { it.isNull || it.isObject && it.isEmpty })
        ProxyExposureFixture.runtimeBindings(service("proxy")["HostConfig"]["PortBindings"], port)
        val client = FixtureHttp("http://127.0.0.1:$port")
        require(client.request("/login", seconds = 15).status == 200)
        require(client.request("/", seconds = 15).status == 302)
        for (path in listOf("/actuator/health", "/actuator/metrics", "/actuator/env")) require(client.request(path, seconds = 15).status == 404)
        println("PASS: login reachable, anonymous redirect, management blocked, backend unpublished")
    }
}
