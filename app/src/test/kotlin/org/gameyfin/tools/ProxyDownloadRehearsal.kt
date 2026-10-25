package org.gameyfin.tools

import java.nio.file.Files
import java.nio.file.Path

/** Explicit staging-only utility; never seeds data, accepts credentials only from the process environment. */
object ProxyDownloadRehearsal {
    @JvmStatic fun main(args: Array<String>) {
        val values = linkedMapOf<String, String>(); var index = 0; var acknowledged = false
        while (index < args.size) when (val name = args[index++]) {
            "--isolated-synthetic-staging" -> { require(!acknowledged); acknowledged = true }
            "--base-url", "--manifest" -> { require(index < args.size && !values.containsKey(name)); values[name] = args[index++] }
            else -> error("Unknown synthetic proxy option")
        }
        require(acknowledged && values.size == 2)
        val path = Path.of(requireNotNull(values["--manifest"]))
        require(Files.isRegularFile(path) && !Files.isSymbolicLink(path))
        val bytes = Files.newInputStream(path).use { it.readNBytes(65537) }; require(bytes.size <= 65536)
        val manifest = FixtureRuntime.tree(bytes.toString(Charsets.UTF_8))
        val rows = manifest["cases"]; require(rows.isArray && rows.size() in 2..8)
        val cases = rows.map { row ->
            val members = row["members"]; require(members.isObject && !members.isEmpty)
            row["path"].asText() to members.properties().associate { it.key to it.value.asText() }
        }
        ProxyExposureFixture.smoke(requireNotNull(values["--base-url"]).trimEnd('/'), cases, requireNotNull(System.getenv("GAMEYFIN_SMOKE_USERNAME")), requireNotNull(System.getenv("GAMEYFIN_SMOKE_PASSWORD")), FixtureDeadline(180))
        println("PASS: synthetic grouped/optional exact members and SHA256, authenticated identity and anonymous logout")
    }
}
