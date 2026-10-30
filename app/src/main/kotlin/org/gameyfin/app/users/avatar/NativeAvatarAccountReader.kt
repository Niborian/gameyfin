package org.gameyfin.app.users.avatar

import org.gameyfin.app.core.security.EncryptionUtils
import tools.jackson.databind.ObjectMapper
import java.sql.Connection
import java.util.UUID

/** Nonregistered native account reader. Proofs come only from the future trusted
 * local operator loader after Main verifies historical provenance; never an HTTP
 * body/name/email. DB fences are NOT independent restore authority: external
 * deployment epoch/descriptor checks remain mandatory before enabling dispatch.
 */
class NativeAvatarAccountReader(proofs: Collection<Proof>) {
    class Proof(val binding: AvatarAuthority.Binding, val nativeEpoch: UUID,
                val nativeRevision: Long, val oidcRevision: Long) {
        init { require(nativeRevision >= 0 && oidcRevision >= 0) }
        override fun toString() = "NativeAvatarAccountProof[redacted]"
    }
    private val proofs = proofs.associateBy { it.binding.nativeId }.also {
        require(it.size == proofs.size && it.size <= 1024)
    }
    private val mapper = ObjectMapper()

    fun read(connection: Connection, userId: Long): AvatarAuthority.NativeAccount? {
        check(!connection.autoCommit)
        val proof = proofs[userId] ?: return null
        val actual = inspect(connection, userId) ?: return null
        return if (actual.issuer != proof.binding.issuer || actual.clientId != proof.binding.clientId ||
            actual.subject != proof.binding.subject || actual.nativeEpoch != proof.nativeEpoch ||
            actual.nativeRevision != proof.nativeRevision || actual.oidcRevision != proof.oidcRevision) null
        else AvatarAuthority.NativeAccount(proof.binding, true)
    }

    /** Current native observation for the private local operator, NOT historical
     * identity provenance, subject-mode attestation, a grant or a binding decision. */
    internal class Inventory(val userId: Long, val nativeEpoch: UUID, val nativeRevision: Long,
        val oidcRevision: Long, val issuer: String, val clientId: String, val subject: String) {
        override fun toString() = "NativeAvatarInventory[redacted]"
    }

    internal fun inspect(connection: Connection, userId: Long): Inventory? {
        check(!connection.autoCommit)
        require(userId > 0)
        // Lock native configuration rows before its revision fence. Config writers
        // also obtain their row then the trigger's fence. Deadlock/timeout must
        // fail/rollback, never be silently retried as a different operation.
        val configuration = mutableMapOf<String, String>()
        connection.prepareStatement("SELECT \"key\",\"value\" FROM app_config WHERE \"key\" IN (?,?,?) ORDER BY \"key\" FOR UPDATE").use { sql ->
            sql.queryTimeout = 5
            listOf(ENABLED, ISSUER, CLIENT).forEachIndexed { index, value -> sql.setString(index + 1, value) }
            sql.executeQuery().use { rows -> while (rows.next()) {
                val encoded = rows.getCharacterStream(2)?.use { it.readTextBounded() } ?: return null
                configuration[rows.getString(1)] = EncryptionUtils.decrypt(encoded)
            } }
        }
        if (configuration.keys != setOf(ENABLED, ISSUER, CLIENT)) return null
        val enabled = mapper.readTree(configuration.getValue(ENABLED))
        val issuer = mapper.readTree(configuration.getValue(ISSUER))
        val client = mapper.readTree(configuration.getValue(CLIENT))
        if (!enabled.isBoolean || !enabled.booleanValue() || !issuer.isTextual || !client.isTextual ||
            !issuer.asText().isBindingText() || !client.asText().isBindingText()) return null
        val oidcRevision = connection.prepareStatement("SELECT revision FROM avatar_oidc_fence WHERE id=1 FOR UPDATE").use { sql ->
            sql.queryTimeout = 5
            sql.executeQuery().use { rows -> if (!rows.next()) return null else rows.getLong(1) }
        }
        if (oidcRevision < 0) return null
        return connection.prepareStatement("SELECT enabled,oidc_provider_id,avatar_identity_epoch,avatar_identity_revision FROM users WHERE id=? FOR UPDATE").use { sql ->
            sql.queryTimeout = 5; sql.setLong(1, userId)
            sql.executeQuery().use { rows ->
                if (!rows.next() || !rows.getBoolean(1)) return null
                val subject = rows.getString(2) ?: return null
                val epoch = rows.getObject(3, UUID::class.java) ?: return null
                val revision = rows.getLong(4)
                if (!subject.isBindingText() || revision < 0) null
                else Inventory(userId, epoch, revision, oidcRevision, issuer.asText(), client.asText(), subject)
            }
        }
    }

    private fun String.isBindingText() = isNotEmpty() && length <= 1024 && none { it.isISOControl() }

    private fun java.io.Reader.readTextBounded(): String {
        val buffer = CharArray(16385)
        var count = 0
        while (count < buffer.size) {
            val read = read(buffer, count, buffer.size - count)
            if (read < 0) break
            count += read
        }
        if (count > 16384) throw AvatarAuthority.Denied()
        return String(buffer, 0, count)
    }
    private companion object {
        const val ENABLED = "sso.oidc.enabled"
        const val ISSUER = "sso.oidc.issuer-url"
        const val CLIENT = "sso.oidc.client-id"
    }
}
