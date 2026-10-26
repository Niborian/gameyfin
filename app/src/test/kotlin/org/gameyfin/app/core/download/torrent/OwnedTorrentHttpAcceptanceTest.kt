package org.gameyfin.app.core.download.torrent

import com.vaadin.hilla.EndpointController
import com.vaadin.hilla.EndpointRegistry
import io.mockk.mockk
import org.gameyfin.app.config.ConfigService
import org.gameyfin.app.core.Role
import org.gameyfin.app.core.config.JacksonConfig
import org.gameyfin.app.core.download.files.DownloadPathLeases
import org.gameyfin.app.core.download.files.DownloadSelectionResolver
import org.gameyfin.app.core.exceptions.EndpointExceptionHandler
import org.gameyfin.app.core.plugins.PluginAccountPolicy
import org.gameyfin.app.core.plugins.PluginEndpointAccess
import org.gameyfin.app.core.security.*
import org.gameyfin.app.games.GameService
import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.games.entities.GameMetadata
import org.gameyfin.app.games.entities.GameVariant
import org.gameyfin.app.games.entities.VariantContent
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.libraries.LibraryRepository
import org.gameyfin.app.libraries.entities.Library
import org.gameyfin.app.users.UserService
import org.gameyfin.app.users.entities.User
import org.gameyfin.app.users.persistence.UserRepository
import org.gameyfin.snapshot.SnapshotCapture
import org.gameyfin.snapshot.SnapshotManifest
import org.gameyfin.snapshot.SnapshotProtocol
import org.gameyfin.snapshot.SnapshotSelection
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.session.SessionRegistry
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.FilterChainProxy
import org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import jakarta.servlet.http.Cookie
import kotlin.test.*

/** Real HTTP/Hilla/security/session/account/facade + migrated H2. Prepared rows below
 * are synthetic READ fixtures, not claims that a native worker or qB ran here. Actual
 * creation/transfer/sharing proof is QbDistributionPairFixtureTest. No valid create is
 * queued, no network client is mocked, and no worker/lease exit is fabricated.
 */
@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "GAMEYFIN_OWNED_TORRENT_HTTP_FIXTURE", matches = "synthetic-only")
@SpringBootTest(classes = [OwnedTorrentHttpAcceptanceTest.HttpConfiguration::class], properties = [
    "spring.datasource.url=jdbc:h2:mem:owned-torrent-http-112;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false", "spring.sql.init.mode=never",
    "gameyfin.owned-torrent.core-parity-enabled=true", "vaadin.productionMode=true", "vaadin.launch-browser=false",
])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OwnedTorrentHttpAcceptanceTest @Autowired internal constructor(
    private val context: WebApplicationContext, private val users: UserRepository,
    private val passwords: PasswordEncoder, private val sessions: SessionRegistry,
    private val games: GameRepository, private val libraries: LibraryRepository,
    private val settingsRows: OwnedTorrentSettingsRepository, private val requests: OwnedTorrentRequestRepository,
    private val registry: OwnedSnapshotRegistry, private val journal: QbDistributionJournal,
    private val sharing: OwnedTorrentSharing, private val mapper: ObjectMapper,
    private val resolver: DownloadSelectionResolver, private val paths: DownloadPathLeases,
    private val manager: PlatformTransactionManager,
) {
    @TempDir lateinit var root: Path
    private lateinit var mvc: MockMvc
    private lateinit var accountA: User
    private lateinit var accountB: User

    @BeforeEach fun setup() {
        require(System.getProperty("os.arch") == "amd64") // Keep the real platform contract.
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters<DefaultMockMvcBuilder>(context.getBean(FilterChainProxy::class.java)).build()
        val endpointRegistry = context.getBean(EndpointRegistry::class.java)
        if (endpointRegistry.endpoints.isEmpty()) context.getBean(EndpointController::class.java).registerEndpoints()
        assertTrue(endpointRegistry.endpoints.keys.any { it.equals("OwnedTorrentEndpoint", ignoreCase = true) })
        val suffix = UUID.randomUUID().toString()
        accountA = users.saveAndFlush(User(username = "http-a-$suffix", email = "a-$suffix@example.invalid",
            password = passwords.encode(PASSWORD), enabled = true, roles = listOf(Role.USER)))
        accountB = users.saveAndFlush(User(username = "http-b-$suffix", email = "b-$suffix@example.invalid",
            password = passwords.encode(PASSWORD), enabled = true, roles = listOf(Role.USER)))
    }

    @Test fun `owning persisted session uses actual Hilla status and exact no-store metadata route`() {
        val session = login(accountA)
        val prepared = prepare(session)
        val status = rpc("status", mapOf("id" to prepared.id.toString()), session)
        assertEquals(200, status.response.status)
        val body = mapper.readTree(status.response.contentAsString)
        assertEquals(prepared.id.toString(), body["id"].asText())
        assertEquals("READY", body["state"].asText())
        val result = mvc.perform(get(prepared.url).session(session)).andReturn()
        assertEquals(200, result.response.status)
        assertContentEquals(prepared.metadata, result.response.contentAsByteArray)
        assertEquals("no-store", result.response.getHeader("Cache-Control"))
        assertEquals("application/x-bittorrent", result.response.contentType)
    }

    @Test fun `unrelated anonymous and expired sessions cannot obtain owner status or metadata`() {
        val owner = login(accountA); val other = login(accountB); val prepared = prepare(owner)
        denied(rpc("status", mapOf("id" to prepared.id.toString()), other), prepared)
        denied(mvc.perform(get(prepared.url).session(other)).andReturn(), prepared)
        val secondSession = login(accountA)
        assertNotEquals(owner.id, secondSession.id)
        denied(rpc("status", mapOf("id" to prepared.id.toString()), secondSession), prepared)
        denied(mvc.perform(get(prepared.url).session(secondSession)).andReturn(), prepared)
        denied(rpc("status", mapOf("id" to prepared.id.toString()), null), prepared)
        denied(mvc.perform(get(prepared.url)).andReturn(), prepared)
        requireNotNull(sessions.getSessionInformation(owner.id)).expireNow()
        val expired = mvc.perform(get(prepared.url).session(owner)).andReturn()
        // Spring's default ConcurrentSessionFilter may write its expiration notice with
        // HTTP200. It must invalidate the session and must NOT serve the torrent route.
        assertTrue(owner.isInvalid)
        assertFalse(prepared.metadata.contentEquals(expired.response.contentAsByteArray))
        assertNotEquals("application/x-bittorrent", expired.response.contentType)
    }

    @Test fun `real logout invalidates the owning session and metadata remains unavailable`() {
        val owner = login(accountA); val prepared = prepare(owner); val oldId = owner.id
        val logout = mvc.perform(post("/logout").session(owner).with(csrf())).andReturn()
        assertTrue(logout.response.status in 200..399)
        assertTrue(owner.isInvalid)
        denied(mvc.perform(get(prepared.url).cookie(Cookie("JSESSIONID", oldId))).andReturn(), prepared)
    }

    @Test fun `ordinary user settings and actual create CSRF admission fail without queue side effects`() {
        val owner = login(accountA); val prepared = prepare(owner)
        denied(rpc("getSettings", emptyMap<String, Any>(), owner), prepared)
        val before = requests.count()
        val invalid = mapOf("selection" to mapOf("gameId" to -1, "variantId" to null,
            "contentIds" to emptyList<Long>(), "explicitSelection" to true))
        denied(rpc("create", invalid, owner, token = false), prepared)
        assertEquals(before, requests.count())
        // With real CSRF/Hilla dispatch the invalid selection reaches normal facade
        // validation, never acquisition. This is NOT a positive create-to-READY test.
        val refused = rpc("create", invalid, owner)
        assertTrue(refused.response.status in 400..499)
        assertFalse(refused.response.contentAsString.contains("QUEUED"))
        assertEquals(before, requests.count())
    }

    private fun login(account: User): MockHttpSession {
        val result = mvc.perform(post("/login").param("username", account.username)
            .param("password", PASSWORD).with(csrf())).andReturn()
        assertTrue(result.response.status in 200..399)
        val session = result.request.getSession(false) as? MockHttpSession ?: error("Login did not persist a session")
        val auth = (session.getAttribute(SPRING_SECURITY_CONTEXT_KEY) as? SecurityContext)?.authentication
            ?: error("Login did not persist authentication")
        assertEquals(account.username, auth.name)
        val information = requireNotNull(sessions.getSessionInformation(session.id))
        assertFalse(information.isExpired); assertSame(auth.principal, information.principal)
        return session
    }

    private fun rpc(method: String, body: Any, session: MockHttpSession?, token: Boolean = true): MvcResult {
        val request = post("/connect/OwnedTorrentEndpoint/$method").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(body))
        if (session != null) request.session(session)
        if (token) request.with(csrf())
        return mvc.perform(request).andReturn()
    }

    private fun denied(result: MvcResult, prepared: Prepared) {
        assertTrue(result.response.status in setOf(400, 401, 403) || result.response.status in 300..399,
            "Unexpected denial status ${result.response.status}")
        assertFalse(prepared.metadata.contentEquals(result.response.contentAsByteArray))
        assertFalse(result.response.contentAsString.contains("\"state\":\"READY\""))
    }

    private data class Prepared(val id: UUID, val metadata: ByteArray) {
        val url get() = "/owned-torrent/$id/metadata"
    }

    private fun prepare(session: MockHttpSession): Prepared {
        val source = Files.createDirectory(root.resolve("source")); val cache = Files.createDirectory(root.resolve("cache"))
        val payload = byteArrayOf(1, 2, 3); val file = source.resolve("base.bin"); Files.write(file, payload)
        val library = libraries.saveAndFlush(Library(name = "HTTP synthetic ${UUID.randomUUID()}"))
        val game = Game(library = library, title = "HTTP synthetic", metadata = GameMetadata(path = source.toString()))
        val variant = GameVariant(game = game, path = file.toString(), isDefault = true)
        variant.contents.add(VariantContent(variant = variant, name = "Required", path = file.toString(), required = true))
        game.variants.add(variant); games.saveAndFlush(game)
        val gameId = requireNotNull(game.id); val variantId = requireNotNull(variant.id)
        val config = OwnedTorrentConfiguration(true, UUID.randomUUID().toString(), "https://127.0.0.1:1",
            hostCacheRoot = cache.toString(), qbCacheRoot = "/owned/cache", cacheIdentity = "http-synthetic-cache",
            trackers = listOf(TRACKER), readOnlyMappingConfirmed = true, privatePeerEgressConfirmed = true,
            byteLimit = 1024, fileLimit = 8, reserveBytes = 0)
        val tx = TransactionTemplate(manager)
        tx.executeWithoutResult {
            val row = settingsRows.locked(); row.configuration = mapper.writeValueAsString(config)
            row.username = "synthetic-unused-client"; row.password = "synthetic-unused-password"
            settingsRows.saveAndFlush(row)
        }
        val host = OwnedSnapshotAcquisitionService(resolver, paths, registry, SnapshotWorkerSupervisor(Duration.ofSeconds(5)),
            OwnedSnapshotSettings(cache, 1024, 8, 0)) // Construction starts no worker.
        val canonical = host.resolveCanonicalSelection(game, variantId, null)
        val id = UUID.randomUUID()
        val request = OwnedTorrentRequestRow(id = id, ownerKey = SnapshotProtocol.digest(
            listOf(accountA.id.toString(), accountA.username, "", session.id).joinToString("\n").toByteArray()),
            selection = mapper.writeValueAsString(OwnedTorrentSelection(gameId, variantId, canonical.contentIds, true)),
            state = "RUNNING", profile = mapper.writeValueAsString(config), profileUsername = "synthetic-unused-client",
            profilePassword = "synthetic-unused-password")
        request.hostRequestDigest = ownedTorrentHostRequestDigest(request)
        requests.saveAndFlush(request)
        sharing.begin(id, ownedTorrentSharedSelectionKey(canonical, ownedTorrentSharedProfileDigest(mapper, config)),
            ownedTorrentSharedProfileDigest(mapper, config), 0, emptySet()) {}
        val capture = SnapshotCapture(id, UUID.randomUUID(), source.toString(), cache.toString(), config.cacheIdentity,
            canonical.selections, 1024, 8, 0)
        val reserved = registry.reserve(id, capture.operation, gameId, variantId, capture.bytes())
        registry.begin(id)
        val digest = SnapshotProtocol.digest(payload)
        val manifest = SnapshotManifest(id, capture.operation, reserved.requestDigest, config.cacheIdentity, "synthetic-read-fixture",
            listOf(org.gameyfin.snapshot.SnapshotMember("content-1/base.bin", 3, digest, "synthetic-copy", "synthetic-source")))
        val ready = registry.ready(id, manifest.bytes()); val leaseId = registry.lease(id)
        val info = mapOf("files" to listOf(mapOf("length" to 3L, "path" to listOf("content-1", "base.bin"))),
            "name" to "data", "piece length" to 16384L, "pieces" to MessageDigest.getInstance("SHA-1").digest(payload), "private" to 1L)
        val metadata = bencode(mapOf("announce" to TRACKER, "info" to info))
        val validated = TorrentMetadataValidator(TorrentNetworkPolicy(setOf(TRACKER), true)).validate(metadata.inputStream(),
            listOf(SnapshotMember("content-1/base.bin", 3, digest)), OwnedSnapshotReader { payload.inputStream() })
        val intent = journal.reserve(UUID.fromString(config.clientId), id, leaseId, requireNotNull(ready.manifestDigest),
            QbDistributionPolicy(UUID.fromString(config.clientId), config.apiOrigin, expectedWebApiVersion = config.webApiVersion, qbCacheRoot = config.qbCacheRoot,
                cacheIdentity = config.cacheIdentity, approvedTrackers = setOf(TRACKER),
                dedicatedReadOnlyClientConfirmed = true, privatePeerEgressConfirmed = true).mappingDigest, id)
        journal.transition(id, setOf(intent.state), QbDistributionState.SEEDING, taskId = "synthetic-read-fixture",
            infoHash = validated.infoHash, metadata = metadata)
        tx.executeWithoutResult { val row = requireNotNull(requests.locked(id)); row.snapshotId = id; row.distributionId = id; requests.saveAndFlush(row) }
        sharing.publish(id, QbDistributionSharedProof(id, intent.clientId, id, leaseId, intent.manifestDigest,
            intent.mappingDigest, validated.infoHash, SnapshotProtocol.digest(metadata)), ownedTorrentSharedMembershipDigest(canonical), 0) {}
        return Prepared(id, metadata)
    }

    private fun bencode(value: Any): ByteArray = ByteArrayOutputStream().also { output ->
        fun write(item: Any) {
            when (item) {
                is String -> write(item.toByteArray(Charsets.UTF_8))
                is ByteArray -> { output.write("${item.size}:".toByteArray()); output.write(item) }
                is Long -> output.write("i${item}e".toByteArray())
                is List<*> -> { output.write('l'.code); item.forEach { write(requireNotNull(it)) }; output.write('e'.code) }
                is Map<*, *> -> { output.write('d'.code); item.entries.sortedBy { it.key as String }.forEach { write(it.key as String); write(requireNotNull(it.value)) }; output.write('e'.code) }
                else -> error("Unsupported fixture bencode value")
            }
        }
        write(value)
    }.toByteArray()

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan("org.gameyfin.app")
    @EnableJpaRepositories("org.gameyfin.app")
    @Import(SecurityConfig::class, SessionRegistryConfig::class, PasswordEncoderConfig::class,
        RoleHierarchyConfig::class, AuthenticationProviderConfig::class, JacksonConfig::class,
        ConfigService::class, PluginAccountPolicy::class, PluginEndpointAccess::class,
        OwnedTorrentFacade::class, OwnedTorrentSettings::class, OwnedTorrentSharing::class,
        OwnedTorrentEndpoint::class, OwnedTorrentMetadataEndpoint::class,
        OwnedSnapshotRegistry::class, QbDistributionJournal::class, DownloadSelectionResolver::class,
        DownloadPathLeases::class, EndpointExceptionHandler::class)
    class HttpConfiguration {
        @Bean fun ssoHandler(): SsoAuthenticationSuccessHandler = mockk() // OIDC is disabled, not exercised.
        @Bean fun users(repository: UserRepository, encoder: PasswordEncoder, config: ConfigService) =
            UserService(repository, mockk(), encoder, mockk(), mockk(), mockk(), config, mockk())
        @Bean fun games(repository: GameRepository, config: ConfigService, users: UserService, libraries: LibraryRepository) =
            GameService(repository, mockk(), mockk(), config, mockk(), users, mockk(), mockk(), libraries)
    }

    private companion object {
        const val PASSWORD = "synthetic-http-only-112"
        const val TRACKER = "https://127.0.0.1:1/announce"
    }
}
