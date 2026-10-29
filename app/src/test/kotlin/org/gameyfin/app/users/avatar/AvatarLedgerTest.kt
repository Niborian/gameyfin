package org.gameyfin.app.users.avatar

import org.junit.jupiter.api.Test
import org.gameyfin.app.media.Image
import org.gameyfin.app.media.ImageType
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlin.test.*

/** Real owned H2 transactions, not native auth/OIDC/API/restore acceptance. */
class AvatarLedgerTest {
    private fun dataSource(url: String, failCommit: Boolean = false, loseCommitAck: Boolean = false,
                           beforeRollback: () -> Unit = {}): DataSource =
        Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, method, _ ->
            check(method.name == "getConnection")
            val connection = DriverManager.getConnection(url)
            Proxy.newProxyInstance(Connection::class.java.classLoader,
                arrayOf(Connection::class.java)) { _, called, arguments ->
                if (called.name == "commit" && failCommit) throw java.sql.SQLException("Synthetic commit refusal")
                if (called.name == "commit" && loseCommitAck) {
                    connection.commit() // Real durable outcome precedes the simulated lost acknowledgement.
                    throw java.sql.SQLException("Synthetic commit acknowledgement lost")
                }
                if (called.name == "rollback") beforeRollback()
                try { called.invoke(connection, *(arguments ?: emptyArray())) }
                catch (failure: InvocationTargetException) { throw failure.targetException }
            }
        } as DataSource

    @Test fun `UI invalidation follows durable acknowledged commit and never a failed commit`() {
        Fixture().use { f ->
            val authority = AvatarAuthority()
            val secret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 31 })
            val binding = AvatarAuthority.Binding(1, "https://issuer.invalid", "client", "public", "subject", 1)
            val image = png(0xff748596.toInt())
            val command = intent(image).let { it.copy(scope = it.scope.copy(bindingDigest = binding.digest)) }
            authority.install(AvatarAuthority.TrustedConfiguration(listOf(AvatarAuthority.CurrentGrant(
                command.scope, binding, AvatarAuthority.Verifier.fromSecret(secret))), emptyList()))
            val account: (Connection, Long) -> AvatarAuthority.NativeAccount? = { _, _ -> AvatarAuthority.NativeAccount(binding, true) }
            mockkObject(AvatarInvalidations)
            try {
                every { AvatarInvalidations.changed(1) } answers {
                    f.tx { c -> assertEquals(1L, f.ledger.current(c, 1)!!.version)
                        assertNotNull(f.ledger.receipt(c, installation, command.operation)) }
                }
                AvatarTransactions(dataSource(f.url), authority, f.ledger, nativeAccount = account).apply(secret, command, image)
                val remove = command.copy(operation = UUID.randomUUID(), action = AvatarLedger.Action.REMOVE,
                    expectedVersion = 1, expectedHash = image.sha256, desiredHash = null)
                assertFailsWith<java.sql.SQLException> {
                    AvatarTransactions(dataSource(f.url, failCommit = true), authority, f.ledger, nativeAccount = account).apply(secret, remove, null)
                }
                verify(exactly = 1) { AvatarInvalidations.changed(1) }
                f.tx { assertEquals(1L, f.ledger.current(it, 1)!!.version); assertNull(f.ledger.receipt(it, installation, remove.operation)) }
            } finally { unmockkObject(AvatarInvalidations) }
        }
    }

    @Test fun `expiry during final native verification rolls back rather than committing after stale deadline check`() {
        for (mode in listOf("preflight", "current", "apply")) Fixture().use { f ->
            val authority = AvatarAuthority()
            val secret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 26 })
            val binding = AvatarAuthority.Binding(1, "https://issuer.invalid", "client", "public", "subject", 1)
            val image = png(0xff748596.toInt())
            val command = intent(image).let { it.copy(scope = it.scope.copy(bindingDigest = binding.digest)) }
            authority.install(AvatarAuthority.TrustedConfiguration(listOf(AvatarAuthority.CurrentGrant(
                command.scope, binding, AvatarAuthority.Verifier.fromSecret(secret))), emptyList()))
            var reads = 0; var expired = false
            val owner = AvatarTransactions(dataSource(f.url), authority, f.ledger) { connection, id ->
                connection.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE").use { sql ->
                    sql.setLong(1, id); sql.executeQuery().use { check(it.next()) }
                }
                // Deterministic expiry while the final native check owns SQL locks, no sleeps.
                if (++reads == if (mode == "apply") 5 else 2) expired = true
                AvatarAuthority.NativeAccount(binding, true)
            }
            val fence = { if (expired) throw java.util.concurrent.CancellationException("Synthetic deadline") }
            assertFailsWith<java.util.concurrent.CancellationException> {
                when (mode) {
                    "preflight" -> owner.preflight(secret, command.scope, fence)
                    "current" -> owner.current(secret, command.scope, fence)
                    else -> owner.apply(secret, command, image, fence)
                }
            }
            f.tx { connection ->
                assertEquals(0L, f.ledger.current(connection, 1)!!.version)
                assertNull(f.ledger.receipt(connection, installation, command.operation))
                assertNull(f.ledger.verifiedPng(connection, 1))
            }
        }
    }

    @Test fun `preflight releases native locks and revocation during body gap denies later apply`() {
        Fixture().use { f ->
            val authority = AvatarAuthority()
            val secret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 25 })
            val binding = AvatarAuthority.Binding(1, "https://issuer.invalid", "client", "public", "subject", 1)
            val image = png(0xff314253.toInt())
            val command = intent(image).let { it.copy(scope = it.scope.copy(bindingDigest = binding.digest)) }
            authority.install(AvatarAuthority.TrustedConfiguration(listOf(AvatarAuthority.CurrentGrant(
                command.scope, binding, AvatarAuthority.Verifier.fromSecret(secret))), emptyList()))
            val owner = AvatarTransactions(dataSource(f.url), authority, f.ledger) { connection, id ->
                connection.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE").use { sql ->
                    sql.setLong(1, id); sql.executeQuery().use { check(it.next()) }
                }
                AvatarAuthority.NativeAccount(binding, true)
            }
            owner.preflight(secret, command.scope)
            val executor = Executors.newSingleThreadExecutor()
            try {
                executor.submit {
                    // Different thread and connection prove neither lease nor native row lock survives preflight.
                    authority.revoke()
                    f.tx { connection -> connection.createStatement().use { sql ->
                        sql.execute("SET LOCK_TIMEOUT 1000")
                        sql.executeUpdate("UPDATE users SET avatar_id=avatar_id WHERE id=1")
                    } }
                }.get(3, TimeUnit.SECONDS)
                assertFailsWith<AvatarAuthority.Denied> { owner.apply(secret, command, image) }
                f.tx { connection ->
                    assertNull(f.ledger.receipt(connection, installation, command.operation))
                    assertEquals(0L, f.ledger.current(connection, 1)!!.version)
                }
            } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)) }
        }
    }

    @Test fun `simulated lost JDBC commit acknowledgement recovers exact committed original without redispatch`() {
        Fixture().use { f ->
            val authority = AvatarAuthority()
            val secret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 21 })
            val recovery = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 22 })
            val binding = AvatarAuthority.Binding(1, "https://issuer.invalid", "client", "public", "subject", 1)
            val image = png(0xff215243.toInt())
            val command = intent(image).let { it.copy(scope = it.scope.copy(bindingDigest = binding.digest)) }
            val currentGrant = AvatarAuthority.CurrentGrant(command.scope, binding, AvatarAuthority.Verifier.fromSecret(secret))
            authority.install(AvatarAuthority.TrustedConfiguration(listOf(currentGrant), emptyList()))
            val account: (Connection, Long) -> AvatarAuthority.NativeAccount? = { connection, id ->
                connection.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE").use { sql ->
                    sql.setLong(1, id); sql.executeQuery().use { rows ->
                        if (rows.next()) AvatarAuthority.NativeAccount(binding, true) else null
                    }
                }
            }
            val uncertain = AvatarTransactions(dataSource(f.url, loseCommitAck = true), authority, f.ledger, nativeAccount = account)
            assertFailsWith<java.sql.SQLException> { uncertain.apply(secret, command, image) }
            val committed = f.tx { connection ->
                assertEquals(1L, f.ledger.current(connection, 1)!!.version)
                assertContentEquals(image.copyBytes(), f.ledger.verifiedPng(connection, 1)!!.copyBytes())
                assertNotNull(f.ledger.receipt(connection, installation, command.operation))
            }
            val normal = AvatarTransactions(dataSource(f.url), authority, f.ledger, nativeAccount = account)
            assertEquals(committed, normal.apply(secret, command, image)) // Exact replay, no new version.
            assertFailsWith<AvatarLedger.Conflict> {
                normal.apply(secret, command.copy(sourceGeneration = command.sourceGeneration + 1), image)
            }
            authority.install(AvatarAuthority.TrustedConfiguration(emptyList(), listOf(AvatarAuthority.HistoricalGrant(
                installation, command.operation, command.digest(), command.scope.restore,
                AvatarAuthority.Verifier.fromSecret(recovery)))))
            assertEquals(committed, normal.originalReceipt(recovery, installation, command.operation,
                command.digest(), command.scope.restore))
            assertFailsWith<AvatarAuthority.Denied> { normal.apply(recovery, command, image) }
            f.tx { connection ->
                assertEquals(1L, f.ledger.current(connection, 1)!!.version)
                connection.createStatement().use { sql -> sql.executeQuery("SELECT COUNT(*) FROM avatar_receipt").use {
                    assertTrue(it.next()); assertEquals(1, it.getInt(1))
                } }
            }
            // This is lost JDBC acknowledgement simulation, NOT process crash,
            // database restore, remote HTTP lost reply or provider custody proof.
        }
    }

    @Test fun `revocation cannot finish before rollback for acquisition denial or commit refusal`() {
        for (acquisitionDenied in listOf(true, false)) Fixture().use { f ->
            val authority = AvatarAuthority()
            val secret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 14 })
            val binding = AvatarAuthority.Binding(1, "https://issuer.invalid", "client", "public", "subject", 1)
            val image = png(0xff506070.toInt())
            val command = intent(image).let { it.copy(scope = it.scope.copy(bindingDigest = binding.digest)) }
            authority.install(AvatarAuthority.TrustedConfiguration(listOf(AvatarAuthority.CurrentGrant(
                command.scope, binding, AvatarAuthority.Verifier.fromSecret(secret))), emptyList()))
            val rollingBack = CountDownLatch(1); val allowRollback = CountDownLatch(1)
            val rotating = CountDownLatch(1); val executor = Executors.newFixedThreadPool(2)
            val owner = AvatarTransactions(dataSource(f.url, !acquisitionDenied) {
                rollingBack.countDown(); check(allowRollback.await(3, TimeUnit.SECONDS))
            }, authority, f.ledger) { connection, id ->
                connection.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE").use { sql ->
                    sql.setLong(1, id); sql.executeQuery().use { check(it.next()) }
                }
                if (acquisitionDenied) null else AvatarAuthority.NativeAccount(binding, true)
            }
            try {
                val writing = executor.submit<Throwable?> { runCatching { owner.apply(secret, command, image) }.exceptionOrNull() }
                assertTrue(rollingBack.await(3, TimeUnit.SECONDS))
                val rotation = executor.submit { rotating.countDown(); authority.revoke() }
                assertTrue(rotating.await(3, TimeUnit.SECONDS))
                assertFailsWith<java.util.concurrent.TimeoutException> { rotation.get(100, TimeUnit.MILLISECONDS) }
                allowRollback.countDown()
                assertNotNull(writing.get(3, TimeUnit.SECONDS))
                rotation.get(3, TimeUnit.SECONDS)
                f.tx { assertNull(f.ledger.receipt(it, installation, command.operation)) }
            } finally {
                allowRollback.countDown(); executor.shutdownNow()
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun `transaction owner commits original outcome and historical recovery needs no current account`() {
        Fixture().use { f ->
            val authority = AvatarAuthority()
            val secret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 11 })
            val recovery = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 12 })
            val binding = AvatarAuthority.Binding(1, "https://issuer.invalid", "client", "public", "subject", 1)
            val image = png(0xff102030.toInt())
            val command = intent(image).let { it.copy(scope = it.scope.copy(bindingDigest = binding.digest)) }
            authority.install(AvatarAuthority.TrustedConfiguration(listOf(AvatarAuthority.CurrentGrant(
                command.scope, binding, AvatarAuthority.Verifier.fromSecret(secret))), emptyList()))
            val owner = AvatarTransactions(dataSource(f.url), authority, f.ledger) { connection, id ->
                connection.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE").use { sql ->
                    sql.setLong(1, id)
                    sql.executeQuery().use { rows ->
                        if (rows.next()) AvatarAuthority.NativeAccount(binding, true) else null
                    }
                }
            }
            val original = owner.apply(secret, command, image)
            f.tx { assertEquals(original, f.ledger.receipt(it, installation, command.operation)) }
            f.tx { it.createStatement().use { sql -> sql.executeUpdate("DELETE FROM users WHERE id=1") } }
            authority.install(AvatarAuthority.TrustedConfiguration(emptyList(), listOf(AvatarAuthority.HistoricalGrant(
                installation, command.operation, command.digest(), command.scope.restore,
                AvatarAuthority.Verifier.fromSecret(recovery)))))
            assertEquals(original, owner.originalReceipt(recovery, installation, command.operation,
                command.digest(), command.scope.restore))
            assertFailsWith<AvatarAuthority.Denied> { owner.apply(secret, command, image) }
        }
    }

    @Test fun `transaction owner never returns receipt after failed commit and rolls back state`() {
        Fixture().use { f ->
            val authority = AvatarAuthority()
            val secret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 13 })
            val binding = AvatarAuthority.Binding(1, "https://issuer.invalid", "client", "public", "subject", 1)
            val image = png(0xff405060.toInt())
            val command = intent(image).let { it.copy(scope = it.scope.copy(bindingDigest = binding.digest)) }
            authority.install(AvatarAuthority.TrustedConfiguration(listOf(AvatarAuthority.CurrentGrant(
                command.scope, binding, AvatarAuthority.Verifier.fromSecret(secret))), emptyList()))
            val owner = AvatarTransactions(dataSource(f.url, true), authority, f.ledger) { _, _ ->
                AvatarAuthority.NativeAccount(binding, true)
            }
            assertFailsWith<java.sql.SQLException> { owner.apply(secret, command, image) }
            f.tx {
                assertEquals(AvatarLedger.Kind.ABSENT, f.ledger.current(it, 1)!!.kind)
                assertNull(f.ledger.receipt(it, installation, command.operation))
            }
            authority.revoke() // Failure closed the lease; rotation is not left blocked.
        }
    }
    private class Fixture : AutoCloseable {
        val url = "jdbc:h2:mem:avatar-${UUID.randomUUID()};LOCK_TIMEOUT=3000"
        private val keeper = DriverManager.getConnection(url)
        val ledger = AvatarLedger()
        init {
            keeper.createStatement().use { sql ->
                sql.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,avatar_id BIGINT)")
                sql.execute("CREATE TABLE image(id BIGINT PRIMARY KEY,type TINYINT)")
                sql.execute("INSERT INTO image VALUES(456,3),(789,3)")
                sql.execute("INSERT INTO users VALUES(1,NULL),(2,NULL),(3,456)")
                val ddl = checkNotNull(javaClass.getResourceAsStream("/avatar-ledger-draft.sql"))
                    .bufferedReader().use { it.readText() }
                ddl.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                    .split(';').filter { it.isNotBlank() }.forEach { sql.execute(it) }
            }
            tx { ledger.prepareExistingUser(it, 1); ledger.prepareExistingUser(it, 2); ledger.prepareExistingUser(it, 3) }
        }
        fun <T> tx(work: (Connection) -> T): T = DriverManager.getConnection(url).use { connection ->
            connection.autoCommit = false
            try { val result = work(connection); connection.commit(); result }
            catch (failure: Throwable) { connection.rollback(); throw failure }
        }
        override fun close() { keeper.close() } // Last connection: in-memory fixture disappears.
    }
    private val installation = UUID.randomUUID()
    private fun scope(user: Long = 1) = AvatarLedger.Scope(installation, UUID(0, 1), 1,
        "sha256:" + "a".repeat(64), "b".repeat(64), UUID(0, 2), 1, "c".repeat(64), 1, user)
    private fun png(color: Int): CanonicalAvatarPng {
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        val bytes = try {
            image.setRGB(0, 0, color)
            ByteArrayOutputStream().use { out -> check(javax.imageio.ImageIO.write(image, "PNG", out)); out.toByteArray() }
        } finally { image.flush() }
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        return CanonicalAvatarPng.validate(bytes, hash)
    }
    private fun intent(png: CanonicalAvatarPng?, version: Long = 0, previous: String? = null,
                       operation: UUID = UUID.randomUUID(), user: Long = 1) =
        AvatarLedger.Intent(scope(user), operation, if (png == null) AvatarLedger.Action.REMOVE else AvatarLedger.Action.REPLACE,
            version, previous, version + 1, png?.sha256)

    @Test fun `atomic content receipt and immutable original replay remain distinct from current state`() {
        Fixture().use { f ->
            val first = png(0x80402010.toInt()); val second = png(0xffee1122.toInt())
            val command = intent(first)
            val original = f.tx { f.ledger.apply(it, command, first) {} }
            assertEquals(1, original.resultingVersion)
            f.tx {
                assertContentEquals(first.copyBytes(), f.ledger.verifiedPng(it, 1)!!.copyBytes())
                assertEquals(AvatarLedger.Kind.ABSENT, f.ledger.current(it, 2)!!.kind)
                f.ledger.apply(it, intent(second, 1, first.sha256), second) {}
            }
            f.tx {
                assertEquals(original, f.ledger.apply(it, command, first) {})
                assertEquals(second.sha256, f.ledger.verifiedPng(it, 1)!!.sha256)
                assertFailsWith<AvatarLedger.Conflict> {
                    f.ledger.apply(it, command.copy(sourceGeneration = 99), first) {}
                }
                assertEquals(2, f.ledger.current(it, 1)!!.version)
            }
            f.tx { f.ledger.apply(it, intent(null, 2, second.sha256), null) {} }
            f.tx {
                assertNull(f.ledger.verifiedPng(it, 1))
                assertEquals(original, f.ledger.receipt(it, installation, command.operation))
            }
        }
    }
    @Test fun `failed final authority fence rolls back both pointer bytes and receipt`() {
        Fixture().use { f ->
            val first = png(0xff102030.toInt()); val replacement = png(0xff506070.toInt())
            f.tx { f.ledger.apply(it, intent(first), first) {} }
            val denied = intent(replacement, 1, first.sha256); val checks = AtomicInteger()
            assertFailsWith<SecurityException> {
                f.tx { f.ledger.apply(it, denied, replacement) {
                    if (checks.incrementAndGet() == 2) throw SecurityException("Synthetic grant revoked")
                } }
            }
            f.tx {
                assertEquals(1, f.ledger.current(it, 1)!!.version)
                assertContentEquals(first.copyBytes(), f.ledger.verifiedPng(it, 1)!!.copyBytes())
                assertNull(f.ledger.receipt(it, installation, denied.operation))
            }
        }
    }
    @Test fun `legacy avatars remain untouched and missing users are not created`() {
        Fixture().use { f ->
            val image = png(0xff123456.toInt())
            assertFailsWith<AvatarLedger.UnsupportedLegacy> {
                f.tx { f.ledger.apply(it, intent(image, user = 3), image) {} }
            }
            assertFailsWith<IllegalStateException> { f.tx { f.ledger.prepareExistingUser(it, 999) } }
            f.tx { c ->
                assertEquals(456, f.ledger.current(c, 3)!!.legacyImageId)
                c.createStatement().use { sql -> sql.executeQuery("SELECT COUNT(*),MAX(avatar_id) FROM users").use {
                    assertTrue(it.next()); assertEquals(3, it.getInt(1)); assertEquals(456, it.getLong(2))
                } }
                assertNull(f.ledger.receipt(c, installation, UUID.randomUUID()))
            }
        }
    }
    @Test fun `two concurrent expected-version writers serialize with one conflict and no orphan receipt`() {
        Fixture().use { f ->
            val pool = Executors.newFixedThreadPool(2); val ready = CountDownLatch(2)
            try {
                val commands = listOf(png(0xff010203.toInt()), png(0xff040506.toInt())).map { it to intent(it) }
                val futures = commands.map { (image, command) -> pool.submit<Boolean> {
                    val checks = AtomicInteger()
                    try {
                        f.tx { f.ledger.apply(it, command, image) {
                            if (checks.incrementAndGet() == 1) { ready.countDown(); check(ready.await(3, TimeUnit.SECONDS)) }
                        } }; true
                    } catch (_: AvatarLedger.Conflict) { false }
                } }
                assertEquals(1, futures.count { it.get(6, TimeUnit.SECONDS) })
                f.tx { c ->
                    assertEquals(1, f.ledger.current(c, 1)!!.version)
                    assertEquals(1, commands.count { f.ledger.receipt(c, installation, it.second.operation) != null })
                    assertNotNull(f.ledger.verifiedPng(c, 1))
                }
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)) }
        }
    }
    @Test fun `terminal receipt survives account deletion but content readback detects corrupted bytes`() {
        Fixture().use { f ->
            val original = png(0xff102938.toInt()); val altered = png(0xff283746.toInt()); val command = intent(original)
            val receipt = f.tx { f.ledger.apply(it, command, original) {} }
            f.tx { c -> c.prepareStatement("UPDATE avatar_state SET png_bytes=? WHERE user_id=1").use {
                it.setBytes(1, altered.copyBytes()); it.executeUpdate()
            } }
            assertFailsWith<IllegalArgumentException> { f.tx { f.ledger.verifiedPng(it, 1) } }
            f.tx { c -> c.createStatement().use { it.executeUpdate("DELETE FROM users WHERE id=1") } }
            f.tx {
                assertNull(f.ledger.current(it, 1))
                assertEquals(receipt, f.ledger.receipt(it, installation, command.operation))
            }
        }
    }
    @Test fun `concurrent identical operations both return one original receipt after waiting for row lock`() {
        Fixture().use { f ->
            val image = png(0xff314159.toInt()); val command = intent(image)
            val pool = Executors.newFixedThreadPool(2); val beforeLock = CountDownLatch(2)
            try {
                val futures = (1..2).map { pool.submit<AvatarLedger.Receipt> {
                    f.tx { connection ->
                        // Both real receipt reads must finish before either real
                        // row-lock query starts. No sleep or production test hook.
                        val synchronizedConnection = Proxy.newProxyInstance(Connection::class.java.classLoader,
                            arrayOf(Connection::class.java)) { _, method, args ->
                            if (method.name == "prepareStatement" && (args?.firstOrNull() as? String)?.let {
                                it.contains("FROM avatar_state") && it.endsWith("FOR UPDATE")
                            } == true) {
                                beforeLock.countDown(); check(beforeLock.await(3, TimeUnit.SECONDS))
                            }
                            try { method.invoke(connection, *(args ?: emptyArray())) }
                            catch (failure: InvocationTargetException) { throw failure.targetException }
                        } as Connection
                        f.ledger.apply(synchronizedConnection, command, image) {}
                    }
                } }
                val receipts = futures.map { it.get(6, TimeUnit.SECONDS) }
                assertEquals(receipts[0], receipts[1])
                f.tx { connection ->
                    assertEquals(1, f.ledger.current(connection, 1)!!.version)
                    assertEquals(receipts[0], f.ledger.receipt(connection, installation, command.operation))
                    connection.createStatement().use { sql -> sql.executeQuery("SELECT COUNT(*) FROM avatar_receipt").use {
                        assertTrue(it.next()); assertEquals(1, it.getInt(1))
                    } }
                }
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)) }
        }
    }
    @Test fun `ordinary pointer update shares version fence but stale general User merge cannot undo service state`() {
        Fixture().use { f ->
            val image = png(0xffadbeef.toInt()); val command = intent(image)
            f.tx { f.ledger.apply(it, command, image) {} }
            // Real SQL models a stale general User save carrying a different old
            // pointer. It must not touch the dedicated avatar state/version/blob.
            f.tx { c -> c.createStatement().use { it.executeUpdate("UPDATE users SET avatar_id=456 WHERE id=1") } }
            f.tx {
                assertEquals(1, f.ledger.current(it, 1)!!.version)
                assertEquals(image.sha256, f.ledger.verifiedPng(it, 1)!!.sha256)
                val ordinary = f.ledger.ordinarySetPreparedAvatar(it, 1, Image(id = 789, type = ImageType.AVATAR)) {}
                assertEquals(2, ordinary.version); assertEquals(789, ordinary.legacyImageId)
            }
            assertFailsWith<AvatarLedger.UnsupportedLegacy> {
                f.tx { f.ledger.apply(it, intent(null, 1, image.sha256), null) {} }
            }
            f.tx { c ->
                val absent = f.ledger.ordinarySetPreparedAvatar(c, 1, null) {}
                assertEquals(3, absent.version); assertEquals(AvatarLedger.Kind.ABSENT, absent.kind)
                assertEquals(command.digest(), f.ledger.receipt(c, installation, command.operation)!!.intentDigest)
                c.createStatement().use { sql -> sql.executeQuery("SELECT COUNT(*) FROM image").use {
                    assertTrue(it.next()); assertEquals(2, it.getInt(1)) // No legacy image removal.
                } }
            }
        }
    }
    @Test fun `ordinary final fence rollback retains previous avatar and rejects non avatar image`() {
        Fixture().use { f ->
            val image = png(0xff102938.toInt())
            f.tx { f.ledger.apply(it, intent(image), image) {} }
            val checks = AtomicInteger()
            assertFailsWith<SecurityException> {
                f.tx { f.ledger.ordinarySetPreparedAvatar(it, 1, null) {
                    if (checks.incrementAndGet() == 2) throw SecurityException("Synthetic ordinary permission lost")
                } }
            }
            assertFailsWith<IllegalArgumentException> {
                f.tx { f.ledger.ordinarySetPreparedAvatar(it, 1, Image(id = 789, type = ImageType.COVER)) {} }
            }
            f.tx {
                assertEquals(1, f.ledger.current(it, 1)!!.version)
                assertEquals(image.sha256, f.ledger.verifiedPng(it, 1)!!.sha256)
            }
        }
    }
}
