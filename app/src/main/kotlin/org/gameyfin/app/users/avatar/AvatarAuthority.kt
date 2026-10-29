package org.gameyfin.app.users.avatar

import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * INERT trusted-loader primitive, not a Spring bean, provisioning API or native
 * account authentication. Nothing is installed by default. The loader must use
 * independently durable restore/configuration epochs, never just restored DB state.
 * A current lease must surround the caller-owned DB transaction INCLUDING COMMIT.
 * Account/binding eligibility also needs native transaction locks through COMMIT;
 * this in-process lock only serializes THIS authority's install/revoke/rotation.
 * Never retain a lease across body/network I/O: optional preflight acquires and
 * closes a lease, bounded I/O follows, then reacquire for the short transaction.
 * Revocation during I/O therefore denies the final acquire; revocation racing
 * COMMIT linearizes after the transaction already holding its lease.
 */
class AvatarAuthority {
    class Denied : SecurityException("Avatar authority unavailable")

    /** Accept only canonical random-32-byte bearer syntax. Randomness is the operator's duty. */
    class Verifier private constructor(private val digest: ByteArray) {
        internal fun accepts(secret: String): Boolean {
            val bytes = decode(secret) ?: return false
            return MessageDigest.isEqual(digest, sha(bytes))
        }
        override fun toString() = "AvatarSecretVerifier[redacted]"
        companion object {
            /** Trusted provisioning loads a verifier, never requires a plaintext operator bearer. */
            fun fromSha256(value: String): Verifier {
                if (!value.matches(HASH)) throw Denied()
                return Verifier(HexFormat.of().parseHex(value))
            }
            /** Synthetic-test/operator conversion convenience; never logs or retains plaintext. */
            fun fromSecret(secret: String): Verifier = Verifier(sha(decode(secret) ?: throw Denied()))
        }
    }

    data class Binding(val nativeId: Long, val issuer: String, val clientId: String,
                       val subjectMode: String, val subject: String, val generation: Long) {
        init {
            require(nativeId > 0 && generation > 0)
            listOf(issuer, clientId, subjectMode, subject).forEach {
                require(it.isNotEmpty() && it.length <= 1024 && it.none { c -> c.isISOControl() })
            }
        }
        /** v1 ordered UTF-8 fields, int32 BE byte-length prefixed; no delimiter ambiguity. */
        val digest: String = canonicalDigest(listOf("gameyfin.avatar.binding.v1", nativeId.toString(),
            issuer, clientId, subjectMode, subject, generation.toString()))
        override fun toString() = "AvatarBinding[redacted]"
    }
    data class NativeAccount(val binding: Binding, val eligible: Boolean)
    class CurrentGrant(val scope: AvatarLedger.Scope, val binding: Binding, val verifier: Verifier) {
        init {
            require(scope.userId == binding.nativeId && scope.bindingGeneration == binding.generation &&
                scope.bindingDigest == binding.digest)
        }
        override fun toString() = "AvatarCurrentGrant[redacted]"
    }
    class HistoricalGrant(val installation: UUID, val operation: UUID, val intentDigest: String,
                          val historicalRestore: UUID, val verifier: Verifier) {
        init { require(intentDigest.matches(HASH)) }
        override fun toString() = "AvatarHistoricalGrant[redacted]"
    }
    class TrustedConfiguration(current: List<CurrentGrant>, historical: List<HistoricalGrant>) {
        internal val current = current.toList()
        internal val historical = historical.toList()
        init {
            require(this.current.size <= 1024 && this.historical.size <= 1024)
            require(this.current.map { it.scope.userId }.distinct().size == this.current.size)
            require(this.current.map { listOf(it.binding.issuer, it.binding.clientId, it.binding.subjectMode, it.binding.subject) }
                .distinct().size == this.current.size)
            require(this.current.map { it.scope.let { s -> listOf(s.installation, s.restore, s.configuration,
                s.imageDigest, s.buildChecksum, s.grant, s.grantGeneration) } }.distinct().size <= 1)
            require(this.historical.map { listOf(it.installation, it.operation, it.intentDigest, it.historicalRestore) }
                .distinct().size == this.historical.size)
        }
        override fun toString() = "AvatarTrustedConfiguration[redacted]"
    }

    private val lock = ReentrantReadWriteLock(true)
    private var installed: TrustedConfiguration? = null

    /** Trusted local loader only. Must not be exposed to an HTTP caller/admin cookie. */
    fun install(configuration: TrustedConfiguration?) {
        check(lock.readHoldCount == 0) { "Close authority lease before rotation" }
        lock.writeLock().lock()
        try { installed = configuration } finally { lock.writeLock().unlock() }
    }
    fun revoke() = install(null)

    fun acquireCurrent(secret: String, scope: AvatarLedger.Scope,
                       nativeAccount: () -> NativeAccount?): CurrentLease =
        acquireCurrentTransaction(secret, scope, nativeAccount) {}

    /** Roll back locks acquired by a failed native reader BEFORE releasing authority. */
    fun acquireCurrentTransaction(secret: String, scope: AvatarLedger.Scope,
                                  nativeAccount: () -> NativeAccount?, rollback: () -> Unit): CurrentLease {
        lock.readLock().lock()
        try {
            val grant = installed?.current?.singleOrNull { it.scope == scope && it.verifier.accepts(secret) }
                ?: throw Denied()
            fun verify() {
                val account = nativeAccount() ?: throw Denied()
                if (!account.eligible || account.binding != grant.binding) throw Denied()
            }
            verify()
            return CurrentLease(::verify)
        } catch (failure: Throwable) {
            try { rollback() } catch (rollbackFailure: Throwable) { failure.addSuppressed(rollbackFailure) }
            finally { lock.readLock().unlock() }
            throw failure
        }
    }

    fun acquireHistorical(secret: String, installation: UUID, operation: UUID,
                          intentDigest: String, historicalRestore: UUID): HistoricalLease {
        if (!intentDigest.matches(HASH)) throw Denied()
        lock.readLock().lock()
        try {
            installed?.historical?.singleOrNull {
                it.installation == installation && it.operation == operation && it.intentDigest == intentDigest &&
                    it.historicalRestore == historicalRestore && it.verifier.accepts(secret)
            } ?: throw Denied()
            return HistoricalLease(installation, operation, intentDigest, historicalRestore)
        } catch (failure: Throwable) { lock.readLock().unlock(); throw failure }
    }

    inner class CurrentLease internal constructor(private val verify: () -> Unit) : AutoCloseable {
        private val owner = Thread.currentThread()
        private var closed = false
        fun checkCurrent() { check(Thread.currentThread() === owner && !closed); verify() }
        override fun close() {
            check(Thread.currentThread() === owner)
            if (!closed) { closed = true; lock.readLock().unlock() }
        }
        override fun toString() = "AvatarCurrentLease[redacted]"
    }
    /** Cannot authorize current reads/writes; only checks an exact original receipt. */
    inner class HistoricalLease internal constructor(private val installation: UUID, private val operation: UUID,
        private val digest: String, private val restore: UUID) : AutoCloseable {
        private val owner = Thread.currentThread()
        private var closed = false
        fun checkHistorical(receipt: AvatarLedger.Receipt) {
            check(Thread.currentThread() === owner && !closed)
            if (receipt.scope.installation != installation || receipt.operation != operation ||
                receipt.intentDigest != digest || receipt.scope.restore != restore) throw Denied()
        }
        override fun close() {
            check(Thread.currentThread() === owner)
            if (!closed) { closed = true; lock.readLock().unlock() }
        }
        override fun toString() = "AvatarHistoricalLease[redacted]"
    }
    companion object {
        private val HASH = Regex("[0-9a-f]{64}")
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        private fun canonicalDigest(fields: List<String>): String {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out -> fields.forEach {
                val encoded = it.toByteArray(Charsets.UTF_8); out.writeInt(encoded.size); out.write(encoded)
            } }
            return HexFormat.of().formatHex(sha(bytes.toByteArray()))
        }
        private fun decode(secret: String): ByteArray? {
            if (!secret.matches(Regex("[A-Za-z0-9_-]{43}"))) return null
            val bytes = try { Base64.getUrlDecoder().decode(secret) } catch (_: IllegalArgumentException) { return null }
            return bytes.takeIf { it.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(it) == secret }
        }
    }
}
