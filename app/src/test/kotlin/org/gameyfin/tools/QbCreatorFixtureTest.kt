package org.gameyfin.tools

import java.nio.file.Files
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** Synthetic creator capability only: no add, tracker, peer, seeding or operator paths. */
@EnabledIfEnvironmentVariable(named = "GAMEYFIN_QB_CREATOR_FIXTURE", matches = "true")
class QbCreatorFixtureTest {
    @Test fun `pinned real creator returns exact selections and known missing hidden member is refused`() {
        FixtureRuntime.hosted()
        val root = FixtureRuntime.temporary("gameyfin-qb-creator-")
        val owned = FixtureContainers(root); var relay: FixtureRelay? = null
        try {
            val password = fixtureSecret()
            val config = Files.createDirectories(root.resolve("config/qBittorrent/config"))
            Files.writeString(config.resolve("qBittorrent.conf"), qbConfiguration(password))
            val fixtures = Files.createDirectory(root.resolve("fixtures")); val originals = Files.createDirectory(root.resolve("originals"))
            val members = linkedMapOf("Grouped/base-a.bin" to "first synthetic base".toByteArray(), "Grouped/base-b.bin" to "second synthetic base".toByteArray(), "Grouped/.hidden.bin" to "selected hidden content".toByteArray(), "Optional/soundtrack.bin" to "synthetic soundtrack".toByteArray())
            for ((name, data) in members) { val path = originals.resolve(name); Files.createDirectories(path.parent); Files.write(path, data) }
            val hashes = members.mapValues { FixtureRuntime.sha(it.value) }
            val visible = members.filterKeys { !it.endsWith("/.hidden.bin") }
            val cases = listOf(visible.filterKeys { it.startsWith("Grouped/") }, visible, members)
            cases.forEachIndexed { index, selected -> for ((name, data) in selected) { val path = fixtures.resolve("$index/$name"); Files.createDirectories(path.parent); Files.write(path, data) } }
            val imageId = owned.docker("image", "inspect", "--format", "{{.Id}}", IMAGE); require(imageId.matches(Regex("sha256:[a-f0-9]{64}")))
            owned.createNetwork()
            val user = FixtureRuntime.command(listOf("id"), "-u"); val group = FixtureRuntime.command(listOf("id"), "-g")
            val id = owned.create("creator", IMAGE, listOf("--memory", "512m", "--cpus", "1", "--user", "$user:$group", "--mount", "type=bind,src=${root.resolve("config")},dst=/config", "--mount", "type=bind,src=$fixtures,dst=/fixture,readonly", "-e", "XDG_CONFIG_HOME=/config", "-e", "HOME=/config", "--entrypoint", "/app/qbittorrent-nox-lib2"), listOf("--confirm-legal-notice", "--profile=/config", "--webui-port=8080"))
            val state = FixtureRuntime.tree(owned.docker("inspect", id))[0]
            require(state["NetworkSettings"]["Networks"].size() == 1)
            relay = FixtureRelay(owned.address(id), 8080)
            val client = FixtureHttp(relay.origin)
            FixtureRuntime.await(30) {
                client.qb("auth/login", mapOf("username" to "fixture", "password" to password))
                client.cookies.cookieStore.cookies.any { it.name == "SID" || it.name.matches(Regex("QBT_SID_[1-9][0-9]{0,4}")) }
            }
            val version = client.qb("app/version").toString(Charsets.UTF_8); require(version.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")))
            cases.forEachIndexed { index, selected ->
                val task = FixtureRuntime.tree(client.qb("torrentcreator/addTask", mapOf("sourcePath" to "/fixture/$index", "private" to "true", "format" to "v1", "startSeeding" to "false", "ignoreDotfiles" to "false", "torrentFilePath" to "/config/fixture-$index.torrent")).toString(Charsets.UTF_8))["taskID"].asText()
                require(task.matches(Regex("[A-Za-z0-9-]{1,80}")))
                FixtureRuntime.await(30) {
                    val status = FixtureRuntime.tree(client.qb("torrentcreator/status?" + FixtureHttp.form(mapOf("taskID" to task))).toString(Charsets.UTF_8))[0]
                    check(status["status"].asText() != "Failed") { "Synthetic creator failed" }
                    (status["status"].asText() == "Finished").also { if (it) require(status["format"].asText() == "v1") }
                }
                val metadata = client.qb("torrentcreator/torrentFile?" + FixtureHttp.form(mapOf("taskID" to task)))
                if (index < 2) CreatorMetadata.verify(metadata, selected, index.toString())
                else {
                    val failure = try { CreatorMetadata.verify(metadata, selected, index.toString()); null } catch (error: IllegalArgumentException) { error }
                    require(failure?.message == "Selected members missing") { "Pinned hidden-member behavior changed; review fixture" }
                }
            }
            require(FixtureRuntime.tree(client.qb("torrents/info").toString(Charsets.UTF_8)).isEmpty) { "Creation unexpectedly added torrent" }
            for ((name, bytes) in members) require(FixtureRuntime.sha(Files.readAllBytes(originals.resolve(name))) == hashes[name])
            cases.forEachIndexed { index, selected -> for ((name, bytes) in selected) require(Files.readAllBytes(fixtures.resolve("$index/$name")).contentEquals(bytes)) }
            println(FixtureRuntime.json.writeValueAsString(mapOf("image" to IMAGE, "imageId" to imageId, "version" to version, "exactSelectionCases" to 2, "omittedHiddenMemberRejected" to true, "originalHashesUnchanged" to true, "noTorrentsAdded" to true)))
        } finally { try { relay?.close() } finally { owned.close() } }
    }
    companion object { const val IMAGE = "ghcr.io/hotio/qbittorrent@sha256:91d59985ed65fe3504b405b51e6d586524c2189a6cffd56324101b70fe094bde" }
}
