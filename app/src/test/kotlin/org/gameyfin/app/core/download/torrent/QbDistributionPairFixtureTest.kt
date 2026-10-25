package org.gameyfin.app.core.download.torrent

import org.gameyfin.app.core.download.files.DownloadPathLeases
import org.gameyfin.app.core.download.files.DownloadSelectionResolver
import org.gameyfin.app.games.entities.*
import org.gameyfin.app.libraries.entities.Library
import org.gameyfin.tools.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Explicit disposable Actions-only acceptance; never an ordinary test or production probe.
 * Uses the real host selection/acquisition/worker/journals/lifecycle and real paired clients.
 * A private tracker URL is deliberately closed loopback; exact peers are supplied explicitly
 * on the sole owned internal network. This proves transfer, not a deployed tracker policy.
 */
@EnabledIfEnvironmentVariable(named = "GAMEYFIN_QB_DISTRIBUTION_PAIR_FIXTURE", matches = "true")
@DataJpaTest
@Import(OwnedSnapshotRegistry::class, QbDistributionJournal::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QbDistributionPairFixtureTest @Autowired internal constructor(private val registry: OwnedSnapshotRegistry,
    private val journal: QbDistributionJournal) {
    @Test @Timeout(value = 8, unit = TimeUnit.MINUTES)
    fun `real readonly snapshot seeder transfers exact selection to isolated second client`() {
        FixtureRuntime.hosted()
        require(System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") == "amd64")
        val root = FixtureRuntime.temporary("gameyfin-qb-distribution-", Path.of(requireNotNull(System.getenv("HOME"))).toAbsolutePath().normalize())
        val owned = FixtureContainers(root)
        var seedRelay: FixtureRelay? = null; var receiverRelay: FixtureRelay? = null
        var api: QbDistributionApi? = null
        val leases = mutableListOf<OwnedSnapshotLease>()
        var primary: Throwable? = null
        try {
            // Rejected parents/deletion cannot create an unregistered fixture or erase a
            // sibling. These checks share the exact actual hosted ownership helper.
            val home = root.parent
            assertFailsWith<IllegalArgumentException> { FixtureRuntime.temporary("gameyfin-qb-rejected-", requireNotNull(home.parent)) }
            val alias = Files.createSymbolicLink(root.resolve("home-alias"), home)
            assertFailsWith<IllegalArgumentException> { FixtureRuntime.temporary("gameyfin-qb-rejected-", alias) }
            assertFailsWith<IllegalArgumentException> { FixtureRuntime.deleteOwned(root.resolve("unregistered")) }
            val deadline = FixtureDeadline(420)
            val source = Files.createDirectory(root.resolve("source")); val cache = Files.createDirectory(root.resolve("cache"))
            val received = Files.createDirectory(root.resolve("received"))
            val sourceBytes = linkedMapOf("base-a.bin" to ByteArray(32771) { (it % 233).toByte() },
                "base-b.bin" to ByteArray(18003) { (it % 211).toByte() }, "optional.bin" to ByteArray(12001) { (it % 199).toByte() },
                "excluded.bin" to "never selected".toByteArray())
            sourceBytes.forEach { (name, bytes) -> Files.write(source.resolve(name), bytes) }
            Files.createLink(source.resolve("base-alias.bin"), source.resolve("base-a.bin"))
            fun fingerprint(path: Path) = Triple(FixtureRuntime.sha(Files.readAllBytes(path)),
                Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey(), Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS))
            val before = (sourceBytes.keys + "base-alias.bin").associateWith { fingerprint(source.resolve(it)) }
            val library = Library(id = 42, name = "Synthetic owned selection")
            val game = Game(id = 84, library = library, title = "Synthetic only", metadata = GameMetadata(path = source.toString()))
            val variant = GameVariant(id = 21, game = game, path = source.resolve("base-a.bin").toString(), isDefault = true)
            game.variants += variant
            variant.contents += VariantContent(id = 1, variant = variant, name = "Required grouped base", path = source.resolve("base-a.bin").toString(),
                required = true, paths = mutableListOf(source.resolve("base-a.bin").toString(), source.resolve("base-b.bin").toString()))
            variant.contents += VariantContent(id = 2, variant = variant, name = "Optional", path = source.resolve("optional.bin").toString())
            variant.contents += VariantContent(id = 3, variant = variant, name = "Excluded", path = source.resolve("excluded.bin").toString())
            val pathLeases = DownloadPathLeases()
            // Same real host membership gate used by ZIP and selected-provider downloads.
            val acquisition = OwnedSnapshotAcquisitionService(DownloadSelectionResolver(), pathLeases, registry,
                SnapshotWorkerSupervisor(Duration.ofSeconds(40)), OwnedSnapshotSettings(cache, 1024 * 1024, 16, 0))
            owned.docker("image", "inspect", "--format", "{{.Id}}", IMAGE).also { require(it.matches(Regex("sha256:[a-f0-9]{64}"))) }
            owned.createNetwork()
            val uid = FixtureRuntime.command(listOf("id"), "-u"); val gid = FixtureRuntime.command(listOf("id"), "-g")
            val password = fixtureSecret()
            fun create(role: String, mounts: List<String>): String {
                val config = Files.createDirectories(root.resolve("$role/qBittorrent/config"))
                Files.writeString(config.resolve("qBittorrent.conf"), qbConfiguration(password, secureCookie = false))
                Files.writeString(root.resolve("$role/ipfilter.dat"), "")
                return owned.create(role, IMAGE, listOf("--memory", "512m", "--cpus", "1", "--pids-limit", "128", "--user", "$uid:$gid",
                    "--mount", "type=bind,src=${root.resolve(role)},dst=/config", "-e", "XDG_CONFIG_HOME=/config", "-e", "HOME=/config",
                    "--entrypoint", "/app/qbittorrent-nox-lib2") + mounts,
                    listOf("--confirm-legal-notice", "--profile=/config", "--webui-port=8080"))
            }
            val seedId = create("seed", listOf("--mount", "type=bind,src=$cache,dst=/owned/cache,readonly"))
            val receiveId = create("receiver", listOf("--mount", "type=bind,src=$received,dst=/downloads"))
            for (id in listOf(seedId, receiveId)) {
                val actual = FixtureRuntime.tree(owned.docker("inspect", id))[0]
                assertEquals("$uid:$gid", actual["Config"]["User"].asText())
                assertEquals(1_000_000_000L, actual["HostConfig"]["NanoCpus"].longValue())
                assertEquals(512L * 1024 * 1024, actual["HostConfig"]["Memory"].longValue())
                assertEquals(1, actual["NetworkSettings"]["Networks"].size())
                assertTrue(actual["Mounts"].none { it["Source"].asText() == source.toString() })
            }
            val mounted = FixtureRuntime.tree(owned.docker("inspect", seedId))[0]["Mounts"].single { it["Destination"].asText() == "/owned/cache" }
            assertEquals(cache.toString(), mounted["Source"].asText()); assertFalse(mounted["RW"].booleanValue())
            seedRelay = FixtureRelay(owned.address(seedId), 8080); receiverRelay = FixtureRelay(owned.address(receiveId), 8080)
            val seed = FixtureHttp(seedRelay.origin, deadline = { deadline.remaining() })
            val receiver = FixtureHttp(receiverRelay.origin, deadline = { deadline.remaining() })
            for ((client, id) in listOf(seed to seedId, receiver to receiveId)) {
                client.awaitQbAuthentication(password, 30, { FixtureRuntime.tree(owned.docker("inspect", id))[0]["State"] })
                assertEquals("v5.2.3", client.qb("app/version").toString(Charsets.UTF_8))
                client.qb("app/setPreferences", mapOf("json" to """{"dht":false,"pex":false,"lsd":false,"upnp":false,"random_port":false,"ip_filter_enabled":true,"ip_filter_path":"/config/ipfilter.dat","listen_port":6881}"""))
            }
            val first = acquisition.acquire(game, variant.id, emptyList()); leases += first
            // release-5.2.3/src/webui/webapplication.h pins API_VERSION {2,15,1}.
            // Never bless an observed unknown API version simply by echoing it into policy.
            val policy = QbDistributionPolicy(UUID.randomUUID(), seedRelay.origin, expectedWebApiVersion = "2.15.1",
                qbCacheRoot = "/owned/cache", cacheIdentity = first.manifest.cacheIdentity, approvedTrackers = setOf("https://127.0.0.1:1/announce"),
                dedicatedReadOnlyClientConfirmed = true, privatePeerEgressConfirmed = true)
            val initialApi = QbDistributionApi(policy, QbDistributionCredentials("fixture", password))
            api = initialApi
            var lifecycle = QbDistributionLifecycle(policy, initialApi, journal)
            for (index in 0..1) {
                deadline.remaining()
                var lease = if (index == 0) first else acquisition.acquire(game, variant.id, listOf(2)).also(leases::add)
                val expected = sourceBytes.filterKeys { it.startsWith("base-") || index == 1 && it == "optional.bin" }.values
                assertEquals(expected.size, lease.manifest.members.size)
                assertEquals(expected.map { FixtureRuntime.sha(it) }.sorted(), lease.manifest.members.map { it.digest }.sorted())
                val row = lifecycle.reserve(lease); lifecycle.create(row.id, lease)
                FixtureRuntime.await(30, { deadline.remaining() }) { lifecycle.validate(row.id, lease); true }
                lifecycle.addStopped(row.id, lease); lifecycle.recheck(row.id, lease)
                FixtureRuntime.await(30, { deadline.remaining() }) { lifecycle.confirmChecked(row.id, lease); true }
                try { lifecycle.start(row.id, lease) } catch (failure: Exception) {
                    if (journal.get(row.id).state != QbDistributionState.STARTING) throw failure
                }
                FixtureRuntime.await(30, { deadline.remaining() }) { lifecycle.confirmSeeding(row.id, lease); true }
                val current = journal.get(row.id); val hash = requireNotNull(current.infoHash)
                addReceiver(receiver, requireNotNull(current.metadata), "/downloads/case-$index")
                receiver.qb("torrents/addPeers", mapOf("hashes" to hash, "peers" to "${owned.address(seedId)}:6881"))
                FixtureRuntime.await(45, { deadline.remaining() }) {
                    val rows = FixtureRuntime.tree(receiver.qb("torrents/info?" + FixtureHttp.form(mapOf("hashes" to hash))).toString(Charsets.UTF_8))
                    rows.size() == 1 && rows[0]["progress"].doubleValue() == 1.0 && rows[0]["amount_left"].longValue() == 0L
                }
                val directory = received.resolve("case-$index/data")
                val files = Files.walk(directory).use { paths -> paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList() }
                assertEquals(lease.manifest.members.map { it.name }.toSet(), files.map { directory.relativize(it).toString() }.toSet())
                lease.manifest.members.forEach { assertEquals(it.digest, FixtureRuntime.sha(Files.readAllBytes(directory.resolve(it.name)))) }
                lifecycle.confirmSeeding(row.id, lease)
                if (index == 0) {
                    // Physical qB restart plus host service/worker reopen, not a full host
                    // JVM crash test. Preserve the exact committed ownership/lease token.
                    val retained = lease.leaseId
                    lease.close()
                    initialApi.close()
                    owned.docker("restart", seedId, seconds = minOf(30, deadline.remaining()))
                    seed.awaitQbAuthentication(password, 30, { FixtureRuntime.tree(owned.docker("inspect", seedId))[0]["State"] })
                    val restartedApi = QbDistributionApi(policy, QbDistributionCredentials("fixture", password))
                    api = restartedApi
                    lifecycle = QbDistributionLifecycle(policy, restartedApi, journal)
                    lease = lifecycle.resume(row.id, acquisition).also(leases::add)
                    assertEquals(retained, lease.leaseId)
                    assertEquals(row.snapshotId, lease.snapshotId)
                    FixtureRuntime.await(30, { deadline.remaining() }) { lifecycle.confirmSeeding(row.id, lease); true }
                }
                receiver.qb("torrents/stop", mapOf("hashes" to hash)); receiver.qb("torrents/delete", mapOf("hashes" to hash, "deleteFiles" to "false"))
                lifecycle.stopForRemoval(row.id, lease)
                FixtureRuntime.await(20, { deadline.remaining() }) {
                    val state = requireNotNull(api).find(hash)?.get("state")?.asText()
                    state in setOf("stoppedUP", "stoppedDL")
                }
                lifecycle.removeStopped(row.id, lease) // Exactly one remove attempt.
                FixtureRuntime.await(20, { deadline.remaining() }) { lifecycle.reconcileRemoval(row.id, lease); true }
                assertEquals(0, registry.activeLeases(lease.snapshotId))
                acquisition.retire(lease.snapshotId)
                assertFalse(Files.exists(cache.resolve(lease.snapshotId.toString()), NOFOLLOW_LINKS))
            }
            assertEquals(before, before.keys.associateWith { fingerprint(source.resolve(it)) })
            assertTrue(FixtureRuntime.tree(seed.qb("torrents/info").toString(Charsets.UTF_8)).isEmpty)
            assertTrue(FixtureRuntime.tree(receiver.qb("torrents/info").toString(Charsets.UTF_8)).isEmpty)
            println("""{"scope":"owned-qb-distribution-pair","version":"v5.2.3","exactSelectionCases":2,"originalHashesAndLinksUnchanged":true,"readonlyCacheMount":true,"seederDownloadedBytes":0,"sameTokenQbRestartAndWorkerReopen":true,"leasesReleased":true,"retirementConfirmed":true}""")
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            // Container removal first physically ends all remote readers. Failed lifecycle
            // ownership/retirement is never papered over by releasing DB leases or chmod.
            var cleanup: Throwable? = null
            fun attempt(action: () -> Unit) { try { action() } catch (failure: Throwable) { if (cleanup == null) cleanup = failure else cleanup!!.addSuppressed(failure) } }
            attempt { api?.close() }; attempt { seedRelay?.close() }; attempt { receiverRelay?.close() }
            leases.forEach { attempt { it.close() } }
            attempt { owned.close() }
            cleanup?.let { if (primary != null) primary!!.addSuppressed(it) else throw it }
        }
    }

    private fun addReceiver(client: FixtureHttp, metadata: ByteArray, path: String) {
        require(path.matches(Regex("/downloads/case-[01]")) && metadata.size in 1..1024 * 1024)
        val boundary = "fixture" + UUID.randomUUID().toString().replace("-", "")
        val prefix = "--$boundary\r\nContent-Disposition: form-data; name=\"savepath\"\r\n\r\n$path\r\n--$boundary\r\nContent-Disposition: form-data; name=\"torrents\"; filename=\"fixture.torrent\"\r\nContent-Type: application/x-bittorrent\r\n\r\n"
        val body = prefix.toByteArray() + metadata + "\r\n--$boundary--\r\n".toByteArray()
        HttpClient.newBuilder().cookieHandler(client.cookies).proxy(java.net.ProxySelector.of(null)).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build().use { http ->
            val request = HttpRequest.newBuilder(URI(client.origin).resolve("/api/v2/torrents/add")).timeout(Duration.ofSeconds(10))
                .header("Referer", client.origin).header("Content-Type", "multipart/form-data; boundary=$boundary").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()
            val pending = http.sendAsync(request, HttpResponse.BodyHandler { FixtureHttp.LimitedBody(4096) })
            try { assertTrue(pending.get(11, TimeUnit.SECONDS).statusCode() in 200..299) } finally { if (!pending.isDone) pending.cancel(true) }
        }
    }
    companion object { const val IMAGE = "ghcr.io/hotio/qbittorrent@sha256:91d59985ed65fe3504b405b51e6d586524c2189a6cffd56324101b70fe094bde" }
}
