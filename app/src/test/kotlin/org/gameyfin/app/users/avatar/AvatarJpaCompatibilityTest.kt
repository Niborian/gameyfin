package org.gameyfin.app.users.avatar

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.Runs
import io.mockk.just
import io.mockk.mockk
import org.gameyfin.app.config.ConfigProperties
import org.gameyfin.app.config.ConfigService
import org.gameyfin.app.core.security.SsoAuthenticationSuccessHandler
import org.gameyfin.app.users.RoleService
import org.gameyfin.app.users.UserService
import org.gameyfin.app.users.persistence.UserRepository
import org.gameyfin.app.util.EntityManagerHolder
import org.gameyfin.app.util.EventPublisherHolder
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.hierarchicalroles.RoleHierarchy
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.gameyfin.app.requests.GameRequestService
import org.gameyfin.app.requests.dto.GameRequestEvent
import org.gameyfin.app.requests.entities.GameRequest
import org.gameyfin.app.requests.entities.GameRequestEntityListener
import org.gameyfin.app.requests.status.GameRequestStatus
import org.gameyfin.pluginapi.gamemetadata.Platform
import org.springframework.context.annotation.Import
import java.time.Instant
import jakarta.persistence.EntityManager
import org.gameyfin.app.core.Role
import org.gameyfin.app.core.security.EncryptionUtils
import org.gameyfin.app.media.Image
import org.gameyfin.app.media.ImageType
import org.gameyfin.app.users.entities.User
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

/** Actual Hibernate commits + Flyway fences. Synthetic encryption only; no HTTP,
 * native binding/provider grant, listener-cleanup or process-crash acceptance. */
@DataJpaTest(properties = ["spring.jpa.show-sql=false", "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
    "gameyfin.avatar.overlay.enabled=true"])
@Import(NativeAvatarState::class, GameRequestEntityListener::class, EntityManagerHolder::class, EventPublisherHolder::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AvatarJpaCompatibilityTest @Autowired constructor(
    private val em: EntityManager, private val transactions: PlatformTransactionManager,
    private val dataSource: DataSource, private val users: UserRepository
) {
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `committed stale User merge preserves independent avatar and native subject revision`() {
        mockkObject(EncryptionUtils)
        every { EncryptionUtils.encrypt(any()) } answers { firstArg() }
        every { EncryptionUtils.decrypt(any()) } answers { firstArg() }
        try {
            val tx = TransactionTemplate(transactions)
            val ids = tx.execute {
                val original = Image(type = ImageType.AVATAR, contentId = UUID.randomUUID().toString())
                em.persist(original)
                val user = User(username = "avatar-jpa", email = "avatar-jpa@example.invalid", enabled = true,
                    oidcProviderId = "original-subject", avatar = original, roles = listOf(Role.USER))
                em.persist(user); em.flush()
                Pair(requireNotNull(user.id), requireNotNull(original.id))
            }!!
            val stale = tx.execute {
                val loaded = em.find(User::class.java, ids.first)
                loaded.avatar?.contentId // Initialize original relation before detach/commit.
                em.detach(loaded)
                loaded
            }!!
            val originalContentId = stale.avatar!!.contentId
            val canonical = png()
            val ledger = AvatarLedger()
            dataSource.connection.use { c ->
                c.autoCommit = false
                try {
                    ledger.prepareExistingUser(c, ids.first)
                    ledger.ordinarySetPreparedAvatar(c, ids.first, null) {}
                    val scope = AvatarLedger.Scope(UUID.randomUUID(), UUID.randomUUID(), 1,
                        "sha256:" + "a".repeat(64), "b".repeat(64), UUID.randomUUID(), 1,
                        "c".repeat(64), 1, ids.first)
                    ledger.apply(c, AvatarLedger.Intent(scope, UUID.randomUUID(), AvatarLedger.Action.REPLACE,
                        1, null, 1, canonical.sha256), canonical) {}
                    c.commit()
                } catch (failure: Throwable) { c.rollback(); throw failure }
            }
            // Detached general profile/role merge carries a stale Image object.
            stale.emailConfirmed = true; stale.roles = listOf(Role.USER, Role.ADMIN)
            stale.avatar!!.contentId = UUID.randomUUID().toString()
            tx.execute { em.merge(stale); em.flush() }
            tx.execute {
                val saved = em.find(User::class.java, ids.first)
                assertTrue(saved.emailConfirmed); assertTrue(Role.ADMIN in saved.roles)
                assertEquals(originalContentId, em.find(Image::class.java, ids.second).contentId)
            }
            dataSource.connection.use { c ->
                c.autoCommit = false
                assertEquals(2L, ledger.current(c, ids.first)!!.version)
                assertContentEquals(canonical.copyBytes(), ledger.verifiedPng(c, ids.first)!!.copyBytes())
                c.commit()
            }
            fun identity(): Pair<UUID, Long> = dataSource.connection.use { c ->
                c.prepareStatement("SELECT avatar_identity_epoch,avatar_identity_revision FROM users WHERE id=?").use { sql ->
                    sql.setLong(1, ids.first); sql.executeQuery().use { rows ->
                        assertTrue(rows.next()); Pair(rows.getObject(1, UUID::class.java), rows.getLong(2))
                    }
                }
            }
            val before = identity()
            for (subject in listOf("different-subject", "original-subject")) {
                tx.execute {
                    val detached = em.find(User::class.java, ids.first)
                    em.detach(detached); detached.oidcProviderId = subject
                    em.merge(detached); em.flush()
                }
            }
            val after = identity()
            assertEquals(before.first, after.first)
            assertEquals(before.second + 2, after.second)
        } finally { unmockkObject(EncryptionUtils) }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `actual Hibernate request listener reads current participant avatars only after successful commit`() {
        mockkObject(EncryptionUtils, GameRequestService.Companion)
        every { EncryptionUtils.encrypt(any()) } answers { firstArg() }
        every { EncryptionUtils.decrypt(any()) } answers { firstArg() }
        val events = mutableListOf<GameRequestEvent>()
        every { GameRequestService.emit(any()) } answers { events += firstArg<GameRequestEvent>() }
        try {
            val tx = TransactionTemplate(transactions)
            val ids = tx.execute {
                (1..2).map { number ->
                    val original = Image(type = ImageType.AVATAR, contentId = UUID.randomUUID().toString())
                    em.persist(original)
                    val user = User(username = "request-avatar-$number", email = "request-avatar-$number@example.invalid",
                        enabled = true, avatar = original, roles = listOf(Role.USER))
                    em.persist(user); em.flush(); requireNotNull(user.id)
                }
            }!!
            val native = NativeAvatarState(dataSource)
            ids.forEach { native.setPrepared(it, null) }
            val png = png()
            dataSource.connection.use { c ->
                c.autoCommit = false
                val scope = AvatarLedger.Scope(UUID.randomUUID(), UUID.randomUUID(), 1,
                    "sha256:" + "a".repeat(64), "b".repeat(64), UUID.randomUUID(), 1, "c".repeat(64), 1, ids[1])
                AvatarLedger().apply(c, AvatarLedger.Intent(scope, UUID.randomUUID(), AvatarLedger.Action.REPLACE,
                    1, null, 1, png.sha256), png) {}
                c.commit()
            }
            fun persist(title: String) {
                val request = GameRequest(title = title, release = Instant.parse("2024-01-01T00:00:00Z"),
                    platform = Platform.PC_MICROSOFT_WINDOWS, status = GameRequestStatus.PENDING,
                    requester = em.find(User::class.java, ids[0]), voters = mutableSetOf(em.find(User::class.java, ids[1])))
                em.persist(request); em.flush()
            }
            tx.execute { persist("Committed avatar request"); assertTrue(events.isEmpty()) }
            val committed = events.filterIsInstance<GameRequestEvent.Created>().single().gameRequest
            assertFalse(committed.requester!!.hasAvatar); assertNull(committed.requester.avatarId)
            assertEquals(1L, committed.requester.avatarVersion)
            assertTrue(committed.voters.single().hasAvatar); assertNull(committed.voters.single().avatarId)
            assertEquals(2L, committed.voters.single().avatarVersion)
            val count = events.size
            tx.execute { status -> persist("Rolled back avatar request"); status.setRollbackOnly() }
            assertEquals(count, events.size)
        } finally { unmockkObject(EncryptionUtils, GameRequestService.Companion) }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `actual OIDC success handler and native account deletion preserve avatar receipt and original Image`() {
        mockkObject(EncryptionUtils)
        every { EncryptionUtils.encrypt(any()) } answers { firstArg() }
        every { EncryptionUtils.decrypt(any()) } answers { firstArg() }
        try {
            val tx = TransactionTemplate(transactions)
            val ids = tx.execute {
                val original = Image(type = ImageType.AVATAR, contentId = UUID.randomUUID().toString())
                em.persist(original)
                val user = User(username = "oidc-native-avatar", email = "old-oidc-avatar@example.invalid",
                    oidcProviderId = "verified-native-subject", enabled = true, avatar = original, roles = listOf(Role.USER))
                em.persist(user); em.flush()
                Pair(requireNotNull(user.id), requireNotNull(original.id))
            }!!
            val state = NativeAvatarState(dataSource)
            state.setPrepared(ids.first, null)
            val canonical = png()
            val ledger = AvatarLedger()
            val scope = AvatarLedger.Scope(UUID.randomUUID(), UUID.randomUUID(), 1,
                "sha256:" + "a".repeat(64), "b".repeat(64), UUID.randomUUID(), 1,
                "c".repeat(64), 1, ids.first)
            val intent = AvatarLedger.Intent(scope, UUID.randomUUID(), AvatarLedger.Action.REPLACE,
                1, null, 1, canonical.sha256)
            val receipt = dataSource.connection.use { connection ->
                connection.autoCommit = false
                val result = ledger.apply(connection, intent, canonical) {}
                connection.commit(); result
            }
            val config = mockk<ConfigService>()
            every { config.get(ConfigProperties.SSO.OIDC.UsernameClaim) } returns "preferred_username"
            val roles = mockk<RoleService>()
            every { roles.extractGrantedAuthorities(any()) } returns emptyList()
            every { roles.authoritiesToRoles(any()) } returns listOf(Role.USER)
            val hierarchy = RoleHierarchy { authorities -> authorities }
            val nativeUsers = UserService(users, mockk(), mockk(), roles, mockk(), mockk(), config, mockk(), state)
            val handler = SsoAuthenticationSuccessHandler(nativeUsers, roles, config, hierarchy)
            val now = Instant.now()
            val principal = DefaultOidcUser(emptyList(), OidcIdToken("synthetic-id-token", now, now.plusSeconds(60),
                mapOf("sub" to "verified-native-subject", "preferred_username" to "oidc-native-avatar",
                    "email" to "new-oidc-avatar@example.invalid")), "preferred_username")
            val response = MockHttpServletResponse()
            // The actual success handler has no surrounding transaction: repository
            // reads return detached users and save owns its merge transaction.
            handler.onAuthenticationSuccess(MockHttpServletRequest(), response,
                UsernamePasswordAuthenticationToken(principal, "synthetic-only", emptyList()))
            assertEquals("/", response.redirectedUrl)
            tx.execute {
                val current = users.findById(ids.first).orElseThrow()
                assertEquals("new-oidc-avatar@example.invalid", current.email)
                assertEquals("verified-native-subject", current.oidcProviderId)
                assertEquals(ids.second, current.avatar!!.id)
            }
            assertEquals(2L, state.metadata(ids.first)!!.version)
            assertContentEquals(canonical.copyBytes(), state.content(ids.first)!!.png!!.copyBytes())
            // Actual native service delete + repository locking and Hibernate commit.
            RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest()))
            tx.execute { nativeUsers.deleteUser("oidc-native-avatar") }
            assertNull(state.metadata(ids.first))
            tx.execute {
                assertFalse(users.existsById(ids.first))
                assertNotNull(em.find(Image::class.java, ids.second))
            }
            dataSource.connection.use { connection ->
                assertEquals(receipt, ledger.receipt(connection, scope.installation, intent.operation))
            }
        } finally {
            SecurityContextHolder.clearContext()
            RequestContextHolder.resetRequestAttributes()
            unmockkObject(EncryptionUtils)
        }
    }

    private fun png(): CanonicalAvatarPng {
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        val bytes = try { ByteArrayOutputStream().use {
            check(javax.imageio.ImageIO.write(image, "PNG", it)); it.toByteArray()
        } } finally { image.flush() }
        return CanonicalAvatarPng.validate(bytes,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
    }
}
