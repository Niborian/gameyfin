package org.gameyfin.app.users.avatar

import io.mockk.every
import io.mockk.mockk
import org.flywaydb.core.Flyway
import org.gameyfin.app.core.plugins.PluginService
import org.gameyfin.app.media.Image
import org.gameyfin.app.media.ImageEndpoint
import org.gameyfin.app.media.ImageService
import org.gameyfin.app.media.ImageType
import org.gameyfin.app.media.FileStorageService
import org.gameyfin.app.users.UserService
import org.gameyfin.app.users.entities.User
import org.gameyfin.app.users.persistence.UserRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import jakarta.servlet.http.HttpServletRequest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationTargetException
import java.sql.Connection
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.HexFormat
import java.util.Optional
import java.util.UUID
import javax.sql.DataSource
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

/** Actual Flyway + native state/UserService/ImageEndpoint, synthetic identity repository.
 * Not a Hibernate listener, HTTP security, OIDC login or provider acceptance test. */
class NativeAvatarStateTest {
    private class Fixture : AutoCloseable {
        val url = "jdbc:h2:mem:native-avatar-${UUID.randomUUID()}"
        private val keeper = DriverManager.getConnection(url, "sa", "")
        val dataSource = Proxy.newProxyInstance(DataSource::class.java.classLoader,
            arrayOf(DataSource::class.java)) { _, method, _ ->
            check(method.name == "getConnection"); DriverManager.getConnection(url, "sa", "")
        } as DataSource
        val state: NativeAvatarState
        val user = User(id = 1, username = "fixture", email = "fixture@example.invalid", enabled = true,
            avatar = Image(id = 456, type = ImageType.AVATAR))
        val images = mockk<ImageService>()
        val repository = mockk<UserRepository>()
        val users: UserService
        init {
            Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("2.4.7.5").load().migrate()
            keeper.createStatement().use {
                it.execute("INSERT INTO image(id,type) VALUES(456,3),(789,3)")
                it.execute("INSERT INTO users(id,username,email,email_confirmed,enabled,avatar_id) VALUES(1,'fixture','fixture@example.invalid',TRUE,TRUE,456)")
            }
            val upgrade = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("2.4.8.1").load().migrate()
            assertEquals(1, upgrade.migrationsExecuted)
            state = NativeAvatarState(dataSource)
            every { repository.findByUsername("fixture") } returns user
            every { repository.findById(1L) } returns Optional.of(user)
            users = UserService(repository, images, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), state)
        }
        fun connection() = DriverManager.getConnection(url, "sa", "")
        override fun close() { keeper.close() }
    }

    @Test fun `legacy JPEG exact hash gates service replacement and original file remains`(@TempDir directory: Path) {
        Fixture().use { f ->
            val storage = FileStorageService(directory.toString())
            val jpegImage = BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB)
            val originalBytes = try { ByteArrayOutputStream().use {
                check(javax.imageio.ImageIO.write(jpegImage, "JPEG", it)); it.toByteArray()
            } } finally { jpegImage.flush() }
            val contentId = storage.saveFile(originalBytes.inputStream())
            fun contentId(value: String) = f.connection().use { connection ->
                connection.prepareStatement("UPDATE image SET content_id=? WHERE id=456").use {
                    it.setString(1, value); it.executeUpdate()
                }
            }
            contentId(contentId)
            val binding = AvatarAuthority.Binding(1, "https://issuer.invalid", "client", "public", "subject", 1)
            val scope = AvatarLedger.Scope(UUID.randomUUID(), UUID.randomUUID(), 1, "sha256:" + "a".repeat(64),
                "b".repeat(64), UUID.randomUUID(), 1, binding.digest, 1, 1)
            val secret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 23 })
            val authority = AvatarAuthority()
            authority.install(AvatarAuthority.TrustedConfiguration(listOf(AvatarAuthority.CurrentGrant(
                scope, binding, AvatarAuthority.Verifier.fromSecret(secret))), emptyList()))
            val legacy = AvatarLegacyReader(storage)
            val owner = AvatarTransactions(f.dataSource, authority, AvatarLedger(), legacy::hash) { connection, id ->
                connection.prepareStatement("SELECT enabled FROM users WHERE id=? FOR UPDATE").use { sql ->
                    sql.setLong(1, id); sql.executeQuery().use { rows ->
                        if (rows.next()) AvatarAuthority.NativeAccount(binding, rows.getBoolean(1)) else null
                    }
                }
            }
            val before = owner.current(secret, scope)
            val originalHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(originalBytes))
            assertEquals(AvatarLedger.Kind.LEGACY, before.kind); assertEquals(originalHash, before.hash)
            assertEquals(456L, before.legacyImageId); assertEquals(0L, before.version)
            contentId("../not-a-native-content-id")
            assertFailsWith<AvatarLedger.UnsupportedLegacy> { owner.current(secret, scope) }
            contentId(contentId)
            val replacement = png()
            val command = AvatarLedger.Intent(scope, UUID.randomUUID(), AvatarLedger.Action.REPLACE,
                before.version, originalHash, 1, replacement.sha256)
            assertFailsWith<AvatarLedger.Conflict> { owner.apply(secret, command.copy(expectedHash = "d".repeat(64)), replacement) }
            assertEquals(before, owner.current(secret, scope))
            val originalReceipt = owner.apply(secret, command, replacement)
            assertEquals(originalReceipt, owner.apply(secret, command, replacement))
            assertEquals(replacement.sha256, owner.current(secret, scope).hash)
            assertEquals(1L, owner.current(secret, scope).version)
            assertContentEquals(originalBytes, Files.readAllBytes(directory.resolve(contentId)))
            assertEquals(456L, f.user.avatar?.id)
            assertContentEquals(replacement.copyBytes(), f.users.getAvatarContent("fixture")?.png?.copyBytes())
        }
    }

    @Test fun `native ordinary writes and profile projections ignore stale User avatar and retain originals`() {
        Fixture().use { f ->
            assertNull(f.state.metadata(1)) // Additive upgrade does not rewrite legacy pointers.
            assertEquals(456L, f.users.getAvatarContent("fixture")?.current?.legacyImageId)
            assertNull(f.state.metadata(1)) // Legacy GET snapshot does not initialize/write state.
            f.users.updateAvatar("fixture", Image(id = 789, type = ImageType.AVATAR))
            val profile = assertNotNull(f.users.getUserById(1))
            assertEquals(789L, profile.avatarId); assertEquals(1L, profile.avatarVersion)
            assertEquals(456L, f.user.avatar?.id) // General/OIDC entity still contains legacy original.
            assertEquals(789L, f.users.getAvatarContent("fixture")?.current?.legacyImageId)
            f.users.deleteAvatar("fixture")
            assertFalse(f.users.hasAvatar("fixture")); assertNull(f.users.getAvatar("fixture"))
            val removed = assertNotNull(f.users.getUserById(1))
            assertFalse(removed.hasAvatar); assertNull(removed.avatarId); assertEquals(2L, removed.avatarVersion)
            assertEquals(AvatarLedger.Kind.ABSENT, f.users.getAvatarContent("fixture")?.current?.kind)
            f.connection().use { c -> c.createStatement().use { sql ->
                sql.executeQuery("SELECT COUNT(*) FROM image WHERE id IN (456,789)").use { rows ->
                    rows.next(); assertEquals(2, rows.getInt(1))
                }
                sql.executeQuery("SELECT avatar_id FROM users WHERE id=1").use { rows ->
                    rows.next(); assertEquals(456L, rows.getLong(1))
                }
            } }
        }
    }

    @Test fun `native PNG endpoint uses actual content hash instead of repeated version after restore`() {
        Fixture().use { f ->
            val png = png()
            val scope = AvatarLedger.Scope(UUID.randomUUID(), UUID.randomUUID(), 1, "sha256:" + "a".repeat(64),
                "b".repeat(64), UUID.randomUUID(), 1, "c".repeat(64), 1, 1)
            f.connection().use { c ->
                c.autoCommit = false
                val ledger = AvatarLedger(); ledger.prepareExistingUser(c, 1)
                // Owned empty-overlay fixture: service legacy replacement itself is still unsupported.
                ledger.ordinarySetPreparedAvatar(c, 1, null) {}
                ledger.apply(c, AvatarLedger.Intent(scope, UUID.randomUUID(), AvatarLedger.Action.REPLACE,
                    1, null, 1, png.sha256), png) {}
                c.commit()
            }
            val endpoint = ImageEndpoint(f.images, f.users, mockk<PluginService>())
            val stale = mockk<HttpServletRequest> { every { getHeader(HttpHeaders.IF_NONE_MATCH) } returns "\"avatar-2\"" }
            val response = assertNotNull(endpoint.getAvatarByUsername("fixture", stale))
            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals("\"avatar-sha256-${png.sha256}\"", response.headers.eTag)
            assertTrue(assertNotNull(response.headers.cacheControl).contains("no-cache"))
            assertContentEquals(png.copyBytes(), assertNotNull(response.body).inputStream.use { it.readBytes() })
            val unchanged = mockk<HttpServletRequest> {
                every { getHeader(HttpHeaders.IF_NONE_MATCH) } returns response.headers.eTag
            }
            assertEquals(HttpStatus.NOT_MODIFIED, endpoint.getAvatarByUsername("fixture", unchanged)?.statusCode)
            assertEquals(2L, f.users.getUserById(1)?.avatarVersion)
            assertNull(f.users.getUserById(1)?.avatarId) // No fabricated Image ID for a DB PNG.
        }
    }

    @Test fun `metadata batches avoid blob reads and preserve absent PNG legacy and missing projections`() {
        Fixture().use { f ->
            f.connection().use { c -> c.createStatement().use { sql ->
                sql.executeUpdate("INSERT INTO users(id,username,email,email_confirmed,enabled) " +
                    "SELECT X,CONCAT('fixture-',X),CONCAT('fixture-',X,'@example.invalid'),TRUE,TRUE FROM SYSTEM_RANGE(2,258)")
                sql.executeUpdate("INSERT INTO avatar_state(user_id,avatar_version,kind) SELECT id,0,'ABSENT' FROM users WHERE id>=2")
            } }
            f.state.setPrepared(1, Image(id = 456, type = ImageType.AVATAR))
            val png = png()
            f.connection().use { c ->
                c.autoCommit = false
                val scope = AvatarLedger.Scope(UUID.randomUUID(), UUID.randomUUID(), 1, "sha256:" + "a".repeat(64),
                    "b".repeat(64), UUID.randomUUID(), 1, "c".repeat(64), 1, 3)
                AvatarLedger().apply(c, AvatarLedger.Intent(scope, UUID.randomUUID(), AvatarLedger.Action.REPLACE,
                    0, null, 1, png.sha256), png) {}
                c.commit()
            }
            var connections = 0
            val queries = mutableListOf<String>()
            val instrumented = Proxy.newProxyInstance(DataSource::class.java.classLoader,
                arrayOf(DataSource::class.java)) { _, method, _ ->
                check(method.name == "getConnection"); connections++
                val connection = f.connection()
                Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, call, args ->
                    if (call.name == "prepareStatement") queries += args!![0] as String
                    try { call.invoke(connection, *(args ?: emptyArray())) }
                    catch (failure: InvocationTargetException) { throw failure.targetException }
                }
            } as DataSource
            val state = NativeAvatarState(instrumented)
            assertTrue(state.metadata(emptyList()).isEmpty()); assertEquals(0, connections)
            assertFailsWith<IllegalArgumentException> { state.metadata(listOf(0)) }
            val result = state.metadata((1L..260L).toList() + listOf(1L, 3L))
            assertEquals(1, connections); assertEquals(2, queries.size)
            assertTrue(queries.all { !it.contains("png_bytes") && !it.contains("*") })
            assertEquals(258, result.size); assertNull(result[260])
            assertEquals(456L, result[1]?.legacyImageId)
            assertEquals(AvatarLedger.Kind.ABSENT, result[2]?.kind)
            assertEquals(png.sha256, result[3]?.hash)
            every { f.repository.findAll() } returns listOf(f.user)
            f.users.deleteAvatar("fixture")
            val profile = f.users.getAllUsers().single()
            assertFalse(profile.hasAvatar); assertNull(profile.avatarId); assertEquals(2L, profile.avatarVersion)
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
