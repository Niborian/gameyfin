package org.gameyfin.app.users.avatar

import org.junit.jupiter.api.Test
import java.time.Instant
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Synthetic in-process authority only, not trusted provisioning/native auth acceptance. */
class AvatarAuthorityTest {
    private val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 17 })
    private val other = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 29 })
    private val binding = AvatarAuthority.Binding(42, "https://issuer.invalid", "client", "public", "subject", 1)
    private val scope = AvatarLedger.Scope(UUID.randomUUID(), UUID.randomUUID(), 1, "sha256:" + "a".repeat(64),
        "b".repeat(64), UUID.randomUUID(), 1, binding.digest, 1, 42)
    private fun grant() = AvatarAuthority.CurrentGrant(scope, binding, AvatarAuthority.Verifier.fromSecret(secret))
    private fun install(a: AvatarAuthority) = a.install(AvatarAuthority.TrustedConfiguration(listOf(grant()), emptyList()))
    private fun account() = AvatarAuthority.NativeAccount(binding, true)

    @Test fun `no default authority and wrong malformed or stale pins fail closed`() {
        val a = AvatarAuthority()
        assertFailsWith<AvatarAuthority.Denied> { a.acquireCurrent(secret, scope, ::account) }
        install(a)
        for (bad in listOf(other, "", secret + "=", "x".repeat(10000)))
            assertFailsWith<AvatarAuthority.Denied> { a.acquireCurrent(bad, scope, ::account) }
        for (stale in listOf(scope.copy(restore = UUID.randomUUID()), scope.copy(configuration = 2),
            scope.copy(imageDigest = "sha256:" + "d".repeat(64)), scope.copy(buildChecksum = "e".repeat(64)),
            scope.copy(grantGeneration = 2), scope.copy(bindingGeneration = 2)))
            assertFailsWith<AvatarAuthority.Denied> { a.acquireCurrent(secret, stale, ::account) }
        a.acquireCurrent(secret, scope, ::account).use { it.checkCurrent() }
        // Preflight must end before body/network I/O. Revocation in that gap
        // invalidates the final transaction acquisition, not just a later write.
        a.acquireCurrent(secret, scope, ::account).close()
        a.revoke()
        assertFailsWith<AvatarAuthority.Denied> { a.acquireCurrent(secret, scope, ::account) }
    }
    @Test fun `native immutable binding and eligibility rechecked without name email adoption`() {
        val a = AvatarAuthority(); install(a)
        for (wrong in listOf(binding.copy(subject = "other"), binding.copy(clientId = "other"),
            binding.copy(issuer = "https://other.invalid"), binding.copy(nativeId = 43), binding.copy(generation = 2)))
            assertFailsWith<AvatarAuthority.Denied> {
                a.acquireCurrent(secret, scope) { AvatarAuthority.NativeAccount(wrong, true) }
            }
        var current: AvatarAuthority.NativeAccount? = account()
        a.acquireCurrent(secret, scope) { current }.use { lease ->
            current = null
            assertFailsWith<AvatarAuthority.Denied> { lease.checkCurrent() }
        }
        assertFailsWith<AvatarAuthority.Denied> { a.acquireCurrent(secret, scope) { AvatarAuthority.NativeAccount(binding, false) } }
    }
    @Test fun `rotation waits for caller commit lease then disables old authority`() {
        val a = AvatarAuthority(); install(a)
        val executor = Executors.newSingleThreadExecutor(); val entered = CountDownLatch(1)
        val lease = a.acquireCurrent(secret, scope, ::account)
        try {
            val rotation = executor.submit { entered.countDown(); a.revoke() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(rotation.isDone)
            lease.checkCurrent() // Caller still holds native transaction/COMMIT boundary.
            lease.close()
            rotation.get(2, TimeUnit.SECONDS)
            assertFailsWith<AvatarAuthority.Denied> { a.acquireCurrent(secret, scope, ::account) }
            assertFailsWith<IllegalStateException> { lease.checkCurrent() }
        } finally { lease.close(); executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)) }
    }
    @Test fun `historical recovery exact operation works without account but cannot grant current access`() {
        val a = AvatarAuthority(); val operation = UUID.randomUUID(); val digest = "d".repeat(64)
        a.install(AvatarAuthority.TrustedConfiguration(emptyList(), listOf(AvatarAuthority.HistoricalGrant(
            scope.installation, operation, digest, scope.restore, AvatarAuthority.Verifier.fromSecret(other)))))
        assertFailsWith<AvatarAuthority.Denied> { a.acquireCurrent(other, scope, ::account) }
        val receipt = AvatarLedger.Receipt(scope, operation, digest, AvatarLedger.Action.REMOVE, 1, null, 1, Instant.EPOCH)
        a.acquireHistorical(other, scope.installation, operation, digest, scope.restore).use { lease ->
            lease.checkHistorical(receipt)
            assertFailsWith<AvatarAuthority.Denied> { lease.checkHistorical(receipt.copy(operation = UUID.randomUUID())) }
            assertFailsWith<AvatarAuthority.Denied> { lease.checkHistorical(receipt.copy(scope = scope.copy(restore = UUID.randomUUID()))) }
        }
        assertFailsWith<AvatarAuthority.Denied> { a.acquireHistorical(other, scope.installation, UUID.randomUUID(), digest, scope.restore) }
    }
    @Test fun `configuration copies caller lists and secret representations are redacted`() {
        val a = AvatarAuthority(); val grants = mutableListOf(grant())
        val config = AvatarAuthority.TrustedConfiguration(grants, emptyList()); a.install(config); grants.clear()
        a.acquireCurrent(secret, scope, ::account).use { assertFalse(it.toString().contains(secret)) }
        assertFalse(config.toString().contains(secret)); assertFalse(grant().toString().contains(secret))
        assertFalse(AvatarAuthority.Verifier.fromSecret(secret).toString().contains(secret))
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Base64.getUrlDecoder().decode(secret)))
        val verifier = AvatarAuthority.Verifier.fromSha256(digest)
        assertTrue(verifier.accepts(secret)); assertFalse(verifier.accepts(other))
        for (bad in listOf("", digest.uppercase(), digest + "00", "z".repeat(64)))
            assertFailsWith<AvatarAuthority.Denied> { AvatarAuthority.Verifier.fromSha256(bad) }
        val second = binding.copy(nativeId = 43, subject = "second")
        fun config(secondScope: AvatarLedger.Scope, secondBinding: AvatarAuthority.Binding = second) =
            AvatarAuthority.TrustedConfiguration(listOf(grant(), AvatarAuthority.CurrentGrant(secondScope,
                secondBinding, verifier)), emptyList())
        val secondScope = scope.copy(userId = 43, bindingDigest = second.digest)
        config(secondScope)
        for (mixed in listOf(secondScope.copy(restore = UUID.randomUUID()), secondScope.copy(configuration = 2),
            secondScope.copy(imageDigest = "sha256:" + "f".repeat(64)), secondScope.copy(grantGeneration = 2)))
            assertFailsWith<IllegalArgumentException> { config(mixed) }
        assertFailsWith<IllegalArgumentException> { AvatarAuthority.TrustedConfiguration(listOf(grant(), grant()), emptyList()) }
        val reusedIdentity = binding.copy(nativeId = 43)
        assertFailsWith<IllegalArgumentException> { config(secondScope.copy(bindingDigest = reusedIdentity.digest), reusedIdentity) }
        assertNotEquals(binding.digest, binding.copy(subject = "second").digest)
        assertNotEquals(binding.digest, binding.copy(generation = 2).digest)
        assertFailsWith<IllegalArgumentException> { binding.copy(subject = "x".repeat(1025)) }
    }
}
