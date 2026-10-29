package org.gameyfin.app.users.avatar

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.flywaydb.core.Flyway
import org.gameyfin.app.core.security.EncryptionUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import kotlin.test.*

/** Actual migrations/native SQL/config encryption; explicit synthetic operator proofs.
 * NOT historical Main provenance, actual Hibernate/OIDC login or independent restore. */
class NativeAvatarAccountReaderTest {
    @BeforeEach fun syntheticKey() {
        mockkObject(EncryptionUtils)
        every { EncryptionUtils.getAppKey() } returns Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
    }
    @AfterEach fun clearMocks() = unmockkAll()

    private class Fixture : AutoCloseable {
        val url = "jdbc:h2:mem:avatar-identity-${UUID.randomUUID()}"
        private val keeper = DriverManager.getConnection(url, "sa", "")
        val binding = AvatarAuthority.Binding(1, "https://issuer.invalid", "fixture-client", "public", "subject-one", 1)
        init {
            Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("2.4.8.1").load().migrate()
            sql("INSERT INTO users(id,username,email,email_confirmed,enabled,oidc_provider_id) VALUES(1,'same-visible-name','same@example.invalid',TRUE,TRUE,'subject-one'),(2,'other','other@example.invalid',TRUE,TRUE,'subject-two')")
            config("sso.oidc.enabled", true)
            config("sso.oidc.issuer-url", binding.issuer)
            config("sso.oidc.client-id", binding.clientId)
            val upgrade = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate()
            assertEquals(1, upgrade.migrationsExecuted)
        }
        fun sql(value: String) = keeper.createStatement().use { it.execute(value) }
        fun config(key: String, value: Any) {
            keeper.prepareStatement("MERGE INTO app_config(\"key\",\"value\") KEY(\"key\") VALUES(?,?)").use {
                it.setString(1, key); it.setString(2, EncryptionUtils.encrypt(ObjectMapper().writeValueAsString(value)))
                it.executeUpdate()
            }
        }
        fun proof(binding: AvatarAuthority.Binding = this.binding): NativeAvatarAccountReader.Proof {
            val pair = keeper.prepareStatement("SELECT avatar_identity_epoch,avatar_identity_revision FROM users WHERE id=?").use {
                it.setLong(1, binding.nativeId)
                it.executeQuery().use { rows -> check(rows.next()); rows.getObject(1, UUID::class.java) to rows.getLong(2) }
            }
            val revision = keeper.createStatement().use { it.executeQuery("SELECT revision FROM avatar_oidc_fence WHERE id=1").use { rows ->
                check(rows.next()); rows.getLong(1)
            } }
            return NativeAvatarAccountReader.Proof(binding, pair.first, pair.second, revision)
        }
        fun <T> tx(work: (Connection) -> T): T = DriverManager.getConnection(url, "sa", "").use {
            it.autoCommit = false
            try { val result = work(it); it.commit(); result } catch (failure: Throwable) { it.rollback(); throw failure }
        }
        fun read(reader: NativeAvatarAccountReader, user: Long = 1) = tx { reader.read(it, user) }
        override fun close() { keeper.close() }
    }

    @Test fun `actual subject changes invalidate proof even when visible identity and subject return`() {
        Fixture().use { f ->
            val proof = f.proof(); val reader = NativeAvatarAccountReader(listOf(proof))
            assertEquals(f.binding, f.read(reader)?.binding)
            assertNull(f.read(reader, 2)) // No implicit second-user adoption.
            val second = f.binding.copy(nativeId = 2, subject = "subject-two")
            assertNotEquals(proof.nativeEpoch, f.proof(second).nativeEpoch)
            val wrongEpoch = NativeAvatarAccountReader.Proof(second, proof.nativeEpoch, 0, 0)
            assertNull(f.read(NativeAvatarAccountReader(listOf(wrongEpoch)), 2))
            f.sql("UPDATE users SET username='renamed',email='renamed@example.invalid',avatar_identity_revision=99,avatar_identity_epoch=RANDOM_UUID() WHERE id=1")
            assertEquals(proof.nativeEpoch, f.proof().nativeEpoch)
            assertEquals(0L, f.proof().nativeRevision)
            assertNotNull(f.read(reader)) // Profile data is not identity authority.
            f.sql("UPDATE users SET enabled=FALSE WHERE id=1")
            assertNull(f.read(reader))
            f.sql("UPDATE users SET enabled=TRUE,oidc_provider_id='wrong-subject' WHERE id=1")
            assertNull(f.read(reader))
            f.sql("UPDATE users SET oidc_provider_id='subject-one',avatar_identity_revision=0 WHERE id=1")
            assertEquals(2L, f.proof().nativeRevision)
            assertNull(f.read(reader)) // Matching name/email/subject again does not resurrect old proof.
            assertNotNull(f.read(NativeAvatarAccountReader(listOf(f.proof(f.binding.copy(generation = 2))))))
        }
    }

    @Test fun `native OIDC configuration away and back invalidates old operator proof`() {
        Fixture().use { f ->
            val reader = NativeAvatarAccountReader(listOf(f.proof()))
            assertNotNull(f.read(reader))
            f.config("sso.oidc.issuer-url", "https://other.invalid")
            assertNull(f.read(reader))
            f.config("sso.oidc.issuer-url", f.binding.issuer)
            assertTrue(f.proof().oidcRevision >= 2)
            assertNull(f.read(reader))
            assertNotNull(f.read(NativeAvatarAccountReader(listOf(f.proof()))))
            f.config("sso.oidc.enabled", false)
            assertNull(f.read(NativeAvatarAccountReader(listOf(f.proof()))))
        }
    }

    @Test fun `deleted native account ID recreation cannot inherit old incarnation proof`() {
        Fixture().use { f ->
            val original = f.proof(); val reader = NativeAvatarAccountReader(listOf(original))
            f.sql("DELETE FROM users WHERE id=1")
            assertNull(f.read(reader))
            f.sql("INSERT INTO users(id,username,email,email_confirmed,enabled,oidc_provider_id) VALUES(1,'same-visible-name','same@example.invalid',TRUE,TRUE,'subject-one')")
            assertNotEquals(original.nativeEpoch, f.proof().nativeEpoch)
            assertNull(f.read(reader))
        }
    }
}
