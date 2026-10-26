package org.gameyfin.app.core.download.torrent

import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.gameyfin.app.core.plugins.PluginEndpointAccess
import org.gameyfin.app.core.config.JacksonConfig
import org.gameyfin.snapshot.SnapshotProtocol
import org.gameyfin.snapshot.SnapshotSourceVerification
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
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
 * on the sole owned internal network. Empty authorization callbacks represent synthetic
 * authorized requests: this proves coordinator/transport/rollback, not HTTP session auth,
 * deployed tracker policy or a complete host/JVM crash.
 */
@EnabledIfEnvironmentVariable(named = "GAMEYFIN_QB_DISTRIBUTION_PAIR_FIXTURE", matches = "true")
@DataJpaTest
@Import(OwnedSnapshotRegistry::class, QbDistributionJournal::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QbDistributionPairFixtureTest @Autowired internal constructor(private val registry: OwnedSnapshotRegistry,
    private val journal: QbDistributionJournal, private val settingsRows: OwnedTorrentSettingsRepository,
    private val requests: OwnedTorrentRequestRepository, private val owners: OwnedTorrentSharedOwnerRepository,
    private val references: OwnedTorrentSharedReferenceRepository, private val intents: QbDistributionIntentRepository,
    private val manager: PlatformTransactionManager) {
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
        var childrenBefore: Set<Pair<Long, java.time.Instant>>? = null
        try {
            childrenBefore = liveFixtureChildren()
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
            val hello = SnapshotWorkerSupervisor(Duration.ofSeconds(10)).start()
            val cacheIdentity = try {
                SnapshotProtocol.decode(hello.exchange(SnapshotProtocol.encode {
                    writeInt(SnapshotProtocol.VERSION); writeInt(SnapshotProtocol.HELLO)
                    SnapshotProtocol.text(this, cache.toString()); SnapshotProtocol.text(this, "")
                })) { SnapshotProtocol.text(this) }
            } finally { check(hello.finish()) }
            // release-5.2.3/src/webui/webapplication.h pins API_VERSION {2,15,1}.
            // Never bless an observed unknown API version simply by echoing it into policy.
            val policy = QbDistributionPolicy(UUID.randomUUID(), seedRelay.origin, expectedWebApiVersion = "2.15.1",
                qbCacheRoot = "/owned/cache", cacheIdentity = cacheIdentity, approvedTrackers = setOf("https://127.0.0.1:1/announce"),
                dedicatedReadOnlyClientConfirmed = true, privatePeerEgressConfirmed = true)
            // A spy counts literal mutations but delegates every call to the pinned real
            // client. No network, native worker, transaction or settings lock is mocked.
            val initialApi = spyk(QbDistributionApi(policy, QbDistributionCredentials("fixture", password)))
            api = initialApi
            var lifecycle = QbDistributionLifecycle(policy, initialApi, journal)
            val mapper = JacksonConfig().jsonMapperFactory().build()
            val tx = TransactionTemplate(manager)
            val config = OwnedTorrentConfiguration(true, policy.clientId.toString(), seedRelay.origin,
                hostCacheRoot = cache.toString(), qbCacheRoot = "/owned/cache", cacheIdentity = cacheIdentity,
                trackers = policy.approvedTrackers.toList(), readOnlyMappingConfirmed = true,
                privatePeerEgressConfirmed = true, byteLimit = 1024 * 1024, fileLimit = 16, reserveBytes = 0)
            tx.executeWithoutResult {
                val row = settingsRows.locked()
                row.configuration = mapper.writeValueAsString(config); row.username = "fixture"; row.password = password
                settingsRows.saveAndFlush(row)
            }
            var settings = OwnedTorrentSettings(settingsRows, requests, mapper, mockk<PluginEndpointAccess>(), manager, true)
            var sharing = OwnedTorrentSharing(settings, owners, references, requests, intents)
            val profileDigest = ownedTorrentSharedProfileDigest(mapper, config)
            val revision = settingsRows.findById(1).orElseThrow().revision
            fun request(contentIds: List<Long>): UUID = UUID.randomUUID().also { id ->
                val canonical = acquisition.resolveCanonicalSelection(game, 21, contentIds)
                val creator = SnapshotProcessIdentity.current()
                tx.executeWithoutResult {
                    requests.saveAndFlush(OwnedTorrentRequestRow(id = id, ownerKey = SnapshotProtocol.digest(id.toString().toByteArray()),
                        selection = mapper.writeValueAsString(OwnedTorrentSelection(canonical.gameId, canonical.variantId, canonical.contentIds, true)),
                        settingsRevision = revision, state = "RUNNING", profile = mapper.writeValueAsString(config),
                        profileUsername = "fixture", profilePassword = password).apply {
                        plannedSnapshotId = id
                        creatorScope = creator.scope; creatorPid = creator.pid; creatorStartedMillis = creator.startedMillis
                        hostRequestDigest = ownedTorrentHostRequestDigest(this)
                    })
                }
            }
            fun key(ids: List<Long>) = ownedTorrentSharedSelectionKey(acquisition.resolveCanonicalSelection(game, 21, ids), profileDigest)
            data class Seed(val id: UUID, val proof: QbDistributionSharedProof, val bytes: ByteArray)
            fun create(ids: List<Long>, mismatched: Set<UUID> = emptySet()): Seed {
                deadline.remaining()
                val id = request(ids)
                sharing.begin(id, key(ids), profileDigest, revision, mismatched) {}
                val request = requests.findById(id).orElseThrow()
                val capturedMembership = ownedTorrentSharedMembershipDigest(acquisition.resolveCanonicalSelection(game, 21, ids))
                val lease = acquisition.acquire(game, 21, ids, plannedSnapshotId = request.plannedSnapshotId,
                    hostRequestDigest = requireNotNull(request.hostRequestDigest)).also(leases::add)
                val expected = sourceBytes.filterKeys { it.startsWith("base-") || 2L in ids && it == "optional.bin" }.values
                assertEquals(expected.size, lease.manifest.members.size)
                assertEquals(expected.map { FixtureRuntime.sha(it) }.sorted(), lease.manifest.members.map { it.digest }.sorted())
                val row = lifecycle.reserve(lease, id)
                tx.executeWithoutResult { requests.locked(id)!!.also { it.snapshotId = lease.snapshotId; it.distributionId = row.id; requests.saveAndFlush(it) } }
                lifecycle.create(row.id, lease)
                FixtureRuntime.await(30, { deadline.remaining() }) { lifecycle.validate(row.id, lease); true }
                lifecycle.addStopped(row.id, lease)
                val addDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (true) {
                    deadline.remaining()
                    try { lifecycle.reconcileAdded(row.id, lease); break }
                    catch (_: QbDistributionPendingConfirmation) {
                        check(System.nanoTime() < addDeadline) { "Owned add confirmation deadline" }
                        Thread.sleep(1000)
                    }
                }
                lifecycle.recheck(row.id, lease)
                FixtureRuntime.await(30, { deadline.remaining() }) { lifecycle.confirmChecked(row.id, lease); true }
                try { lifecycle.start(row.id, lease) } catch (failure: Exception) {
                    if (journal.get(row.id).state != QbDistributionState.STARTING) throw failure
                }
                FixtureRuntime.await(30, { deadline.remaining() }) { lifecycle.confirmSeeding(row.id, lease); true }
                val proof = lifecycle.proveSharedSeed(id, lease)
                lease.closeAndRequireExit() // READY may not precede physical worker exit.
                sharing.publish(id, proof, capturedMembership, revision) {}
                return Seed(id, proof, sharing.metadata(id, revision) { assertEquals(capturedMembership, it) })
            }
            var transferIndex = 0
            fun transfer(resource: Seed) {
                val index = transferIndex++
                val hash = resource.proof.infoHash
                val lease = lifecycle.resume(resource.id, acquisition).also(leases::add)
                addReceiver(receiver, resource.bytes, "/downloads/case-$index")
                receiver.qb("torrents/addPeers", mapOf("hashes" to hash, "peers" to "${owned.address(seedId)}:6881"))
                FixtureRuntime.await(45, { deadline.remaining() }) {
                    val rows = FixtureRuntime.tree(receiver.qb("torrents/info?" + FixtureHttp.form(mapOf("hashes" to hash))).toString(Charsets.UTF_8))
                    rows.size() == 1 && rows[0]["progress"].doubleValue() == 1.0 && rows[0]["amount_left"].longValue() == 0L
                }
                val directory = received.resolve("case-$index/data")
                val files = Files.walk(directory).use { paths -> paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList() }
                assertEquals(lease.manifest.members.map { it.name }.toSet(), files.map { directory.relativize(it).toString() }.toSet())
                lease.manifest.members.forEach { assertEquals(it.digest, FixtureRuntime.sha(Files.readAllBytes(directory.resolve(it.name)))) }
                lifecycle.proveSharedSeed(resource.id, lease)
                lease.closeAndRequireExit()
                receiver.qb("torrents/stop", mapOf("hashes" to hash)); receiver.qb("torrents/delete", mapOf("hashes" to hash, "deleteFiles" to "false"))
                FixtureRuntime.await(10, { deadline.remaining() }) {
                    FixtureRuntime.tree(receiver.qb("torrents/info?" + FixtureHttp.form(mapOf("hashes" to hash))).toString(Charsets.UTF_8)).isEmpty
                }
            }
            fun retire(requestId: UUID, resource: Seed) {
                assertTrue(sharing.release(requestId) {}!!.last)
                val lease = lifecycle.resume(resource.id, acquisition).also(leases::add)
                lifecycle.stopForRemoval(resource.id, lease)
                FixtureRuntime.await(20, { deadline.remaining() }) {
                    val state = requireNotNull(api).find(resource.proof.infoHash)?.get("state")?.asText()
                    state in setOf("stoppedUP", "stoppedDL")
                }
                lifecycle.removeStopped(resource.id, lease) // Exactly one remove attempt.
                FixtureRuntime.await(20, { deadline.remaining() }) { lifecycle.reconcileRemoval(resource.id, lease); true }
                assertEquals(0, registry.activeLeases(lease.snapshotId))
                acquisition.retire(lease.snapshotId)
                sharing.retired(resource.id) {}
                assertFalse(Files.exists(cache.resolve(lease.snapshotId.toString()), NOFOLLOW_LINKS))
            }
            val original = create(emptyList())
            transfer(original)
            val second = request(emptyList())
            val candidate = sharing.candidates(key(emptyList())).single()
            val sharedLease = lifecycle.resume(original.id, acquisition).also(leases::add)
            val repeatedMembership = ownedTorrentSharedMembershipDigest(acquisition.resolveCanonicalSelection(game, 21, emptyList()))
            val currentSource = acquisition.verifyCurrentSources(sharedLease, game, 21, emptyList())
            assertEquals(SnapshotSourceVerification.MATCH, currentSource.result)
            val repeatedProof = lifecycle.proveSharedSeed(original.id, sharedLease)
            sharedLease.closeAndRequireExit()
            // Actual writer transaction: an authority failure must not publish a
            // reference or READY request. Reuse the same request only after proving
            // its prior failed attach left the committed state unchanged.
            assertFailsWith<IllegalStateException> {
                sharing.attach(second, candidate, repeatedProof, currentSource.sourceDigest, currentSource.result.name, repeatedMembership, revision) {
                    error("Synthetic captured actor revoked")
                }
            }
            assertFalse(references.existsById(second))
            assertEquals("RUNNING", requests.findById(second).orElseThrow().state)
            assertEquals("ACTIVE", owners.findById(original.id).orElseThrow().state)
            sharing.attach(second, candidate, repeatedProof, currentSource.sourceDigest, currentSource.result.name, repeatedMembership, revision) {}
            assertContentEquals(original.bytes, sharing.metadata(second, revision) { assertEquals(repeatedMembership, it) })
            assertEquals(1, Files.list(cache).use { entries -> entries.filter {
                Files.isDirectory(it, NOFOLLOW_LINKS) && it.fileName.toString().matches(Regex("[a-f0-9-]{36}"))
            }.count() }.toInt())
            verify(exactly = 1) { initialApi.create(any(), any()) }
            verify(exactly = 1) { initialApi.addStopped(any(), any(), any()) }
            // Reopen real services/repositories and restart the exact owned qB
            // container. This is durable table + same-token worker/API recovery,
            // not a full host/JVM crash or HTTP session-auth acceptance claim.
            initialApi.close()
            owned.docker("restart", seedId, seconds = minOf(30, deadline.remaining()))
            seed.awaitQbAuthentication(password, 30, { FixtureRuntime.tree(owned.docker("inspect", seedId))[0]["State"] })
            val restartedApi = spyk(QbDistributionApi(policy, QbDistributionCredentials("fixture", password)))
            api = restartedApi
            lifecycle = QbDistributionLifecycle(policy, restartedApi, journal)
            settings = OwnedTorrentSettings(settingsRows, requests, mapper, mockk<PluginEndpointAccess>(), manager, true)
            sharing = OwnedTorrentSharing(settings, owners, references, requests, intents)
            val reopened = lifecycle.resume(original.id, acquisition).also(leases::add)
            assertEquals(original.proof.leaseId, reopened.leaseId)
            assertEquals(original.proof.snapshotId, reopened.snapshotId)
            FixtureRuntime.await(30, { deadline.remaining() }) {
                assertEquals(original.proof, lifecycle.proveSharedSeed(original.id, reopened)); true
            }
            reopened.closeAndRequireExit()
            assertEquals(candidate, sharing.candidates(key(emptyList())).single())
            for (id in listOf(original.id, second)) {
                assertEquals("ACTIVE", references.findById(id).orElseThrow().state)
                assertEquals(original.id, references.findById(id).orElseThrow().ownerId)
                assertEquals("READY", requests.findById(id).orElseThrow().state)
                assertContentEquals(original.bytes, sharing.metadata(id, revision) { assertEquals(repeatedMembership, it) })
            }
            assertFalse(sharing.release(original.id) {}!!.last)
            verify(exactly = 0) { initialApi.stop(any()) }
            verify(exactly = 0) { initialApi.remove(any()) }
            verify(exactly = 0) { restartedApi.stop(any()) }
            verify(exactly = 0) { restartedApi.remove(any()) }
            transfer(original) // First request release did not interrupt the second user.
            val distinct = create(listOf(2))
            assertNotEquals(original.proof.infoHash, distinct.proof.infoHash)
            transfer(distinct)
            assertEquals(before, before.keys.associateWith { fingerprint(source.resolve(it)) })
            // Explicit out-of-band SYNTHETIC source change. The app never writes source
            // bytes. The old referenced swarm must remain the old immutable generation.
            val changed = sourceBytes.getValue("base-a.bin").copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            Files.write(source.resolve("base-a.bin"), changed); sourceBytes["base-a.bin"] = changed
            val afterSyntheticMutation = before.keys.associateWith { fingerprint(source.resolve(it)) }
            val oldLease = lifecycle.resume(original.id, acquisition).also(leases::add)
            assertEquals(SnapshotSourceVerification.MISMATCH, acquisition.verifyCurrentSources(oldLease, game, 21, emptyList()).result)
            assertEquals(original.proof, lifecycle.proveSharedSeed(original.id, oldLease))
            oldLease.closeAndRequireExit()
            val newGeneration = create(emptyList(), setOf(original.id))
            assertNotEquals(original.proof.infoHash, newGeneration.proof.infoHash)
            transfer(newGeneration)
            retire(second, original)
            verify(exactly = 1) { restartedApi.remove(original.proof.infoHash) }
            retire(distinct.id, distinct); retire(newGeneration.id, newGeneration)
            verify(exactly = 1) { initialApi.create(any(), any()) }
            verify(exactly = 1) { initialApi.addStopped(any(), any(), any()) }
            verify(exactly = 2) { restartedApi.create(any(), any()) }
            verify(exactly = 2) { restartedApi.addStopped(any(), any(), any()) }
            verify(exactly = 3) { restartedApi.stop(any()) }
            verify(exactly = 3) { restartedApi.remove(any()) }
            assertEquals(afterSyntheticMutation, before.keys.associateWith { fingerprint(source.resolve(it)) })
            assertTrue(FixtureRuntime.tree(seed.qb("torrents/info").toString(Charsets.UTF_8)).isEmpty)
            assertTrue(FixtureRuntime.tree(receiver.qb("torrents/info").toString(Charsets.UTF_8)).isEmpty)
            println("""{"scope":"shared-owned-qb-distribution-pair","version":"v5.2.3","requests":4,"copies":3,"createCalls":3,"addCalls":3,"removeCalls":3,"sameSelectionMetadataIdentical":true,"sharedTablesAndSameTokenQbRestartReopened":true,"revokedAttachRolledBack":true,"firstReleaseDidNotStop":true,"distinctSelectionAndChangedSourceSeparate":true,"appSourceHashesAndLinksUnchanged":true,"explicitSyntheticSourceMutation":true,"readonlyCacheMount":true,"leasesReleased":true,"retirementConfirmed":true}""")
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            // Teardown is not successful lifecycle/retention proof. End exact remote and
            // worker processes before test-only immutable-directory cleanup; DB ownership
            // is never forged or released just to make a failed fixture look successful.
            var cleanup: Throwable? = null
            fun attempt(action: () -> Unit) { try { action() } catch (failure: Throwable) { if (cleanup == null) cleanup = failure else cleanup!!.addSuppressed(failure) } }
            attempt { api?.close() }; attempt { seedRelay?.close() }; attempt { receiverRelay?.close() }
            attempt { owned.closeImmutableFixture {
                var workerFailure: Throwable? = null
                for (lease in leases) try { lease.closeAndRequireExit() } catch (failure: Throwable) {
                    if (workerFailure == null) workerFailure = failure else workerFailure.addSuppressed(failure)
                }
                workerFailure?.let { throw it }
                require(liveFixtureChildren().all { it in requireNotNull(childrenBefore) }) { "Fixture child exit remains unproven" }
            } }
            cleanup?.let { if (primary != null) primary!!.addSuppressed(it) else throw it }
        }
    }

    /** Read-only bounded census, no process discovery/kill by name or broad termination. */
    private fun liveFixtureChildren(): Set<Pair<Long, java.time.Instant>> = ProcessHandle.current().descendants().use { descendants ->
        val children = descendants.limit(129).toList()
        require(children.size <= 128) { "Fixture child census exceeds bound" }
        children.mapNotNull { child ->
            if (!child.isAlive) null else {
                val start = child.info().startInstant().orElse(null)
                if (!child.isAlive) null else child.pid() to requireNotNull(start) { "Fixture child identity unavailable" }
            }
        }.toSet()
    }

    private fun addReceiver(client: FixtureHttp, metadata: ByteArray, path: String) {
        require(path.matches(Regex("/downloads/case-[0-3]")) && metadata.size in 1..1024 * 1024)
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
