package org.gameyfin.app.users.avatar

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import org.gameyfin.app.media.Image
import org.gameyfin.app.media.ImageType

/**
 * Inert persistence foundation, NOT a service grant or HTTP capability. No Spring
 * registration or production migration exists yet. Caller owns the transaction,
 * rollback on ANY failure, and the independently provisioned authority lock held
 * through COMMIT. A callback supplied by an HTTP caller is never authority.
 * Ordinary User/Image writers are NOT integrated yet: do not enable dispatch.
 */
class AvatarLedger {
    data class Scope(
        val installation: UUID, val restore: UUID, val configuration: Long,
        val imageDigest: String, val buildChecksum: String,
        val grant: UUID, val grantGeneration: Long,
        val bindingDigest: String, val bindingGeneration: Long, val userId: Long
    ) {
        init {
            require(configuration > 0 && grantGeneration > 0 && bindingGeneration > 0 && userId > 0)
            require(imageDigest.matches(Regex("sha256:[0-9a-f]{64}")))
            require(buildChecksum.matches(HASH) && bindingDigest.matches(HASH))
        }
    }
    enum class Action { REPLACE, REMOVE }
    enum class Kind { ABSENT, PNG, LEGACY }
    data class Current(val version: Long, val kind: Kind, val hash: String?, val legacyImageId: Long?)
    data class Intent(
        val scope: Scope, val operation: UUID, val action: Action,
        val expectedVersion: Long, val expectedHash: String?,
        val sourceGeneration: Long, val desiredHash: String?
    ) {
        init {
            require(expectedVersion >= 0 && sourceGeneration > 0)
            require(expectedHash == null || expectedHash.matches(HASH))
            require(if (action == Action.REPLACE) desiredHash?.matches(HASH) == true else desiredHash == null)
        }
        /** v1: ordered UTF-8 fields, each prefixed by an unsigned-sized int32 BE length. */
        fun digest(): String {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                listOf("gameyfin.avatar.intent.v1", scope.installation, scope.restore, scope.configuration,
                    scope.imageDigest, scope.buildChecksum, scope.grant, scope.grantGeneration,
                    scope.bindingDigest, scope.bindingGeneration, scope.userId, operation, action.name,
                    expectedVersion, expectedHash ?: "", sourceGeneration, desiredHash ?: "")
                    .forEach { field ->
                        val encoded = field.toString().toByteArray(Charsets.UTF_8)
                        out.writeInt(encoded.size); out.write(encoded)
                    }
            }
            return hash(bytes.toByteArray())
        }
    }
    data class Receipt(val scope: Scope, val operation: UUID, val intentDigest: String,
                       val action: Action, val resultingVersion: Long, val resultingHash: String?,
                       val sourceGeneration: Long, val committedAt: Instant)
    class Conflict : IllegalStateException("Avatar operation conflict")
    class UnsupportedLegacy : IllegalStateException("Legacy avatar compatibility not established")

    /** Locks an existing native user, never creates/adopts an account or changes its avatar. */
    fun prepareExistingUser(connection: Connection, userId: Long) {
        transactionRequired(connection)
        require(userId > 0)
        val legacy = connection.prepareStatement("SELECT avatar_id FROM users WHERE id=? FOR UPDATE").use { sql ->
            sql.setLong(1, userId)
            sql.executeQuery().use { rows ->
                check(rows.next()) { "Native avatar target unavailable" }
                rows.getLong(1).let { if (rows.wasNull()) null else it }
            }
        }
        if (current(connection, userId) != null) return
        connection.prepareStatement("INSERT INTO avatar_state(user_id,avatar_version,kind,legacy_image_id) VALUES(?,0,?,?)").use { sql ->
            sql.setLong(1, userId); sql.setString(2, if (legacy == null) Kind.ABSENT.name else Kind.LEGACY.name)
            sql.setObject(3, legacy); sql.executeUpdate()
        }
    }

    /** Metadata only, does not fetch a blob on ordinary catalogue reads. */
    fun current(connection: Connection, userId: Long): Current? = selectCurrent(connection, userId, false)

    fun lockedCurrent(connection: Connection, userId: Long): Current? {
        transactionRequired(connection)
        return selectCurrent(connection, userId, true)
    }

    /**
     * Ordinary upload/remove persistence primitive. Image must already be fully
     * prepared by the authenticated native upload path, never a request-selected
     * arbitrary Image ID. Uses the same row/version boundary as service writes.
     * Keeps User.avatar (legacy original) untouched: stale general User merges
     * cannot overwrite the independent current state. UI/read/cleanup integration
     * is still REQUIRED before this state becomes authoritative in the real app.
     */
    fun ordinarySetPreparedAvatar(connection: Connection, userId: Long, image: Image?,
                                  authorityFence: () -> Unit): Current {
        transactionRequired(connection)
        require(image == null || (image.type == ImageType.AVATAR && (image.id ?: 0) > 0))
        authorityFence()
        val before = selectCurrent(connection, userId, true) ?: throw Conflict()
        image?.let { prepared ->
            connection.prepareStatement("SELECT type FROM image WHERE id=?").use { sql ->
                sql.setLong(1, checkNotNull(prepared.id))
                sql.executeQuery().use { rows ->
                    check(rows.next() && rows.getInt(1) == ImageType.AVATAR.ordinal && !rows.wasNull()) {
                        "Prepared avatar unavailable"
                    }
                }
            }
        }
        val result = Current(Math.addExact(before.version, 1),
            if (image == null) Kind.ABSENT else Kind.LEGACY, null, image?.id)
        connection.prepareStatement("UPDATE avatar_state SET avatar_version=?,kind=?,content_hash=NULL,png_bytes=NULL,legacy_image_id=? WHERE user_id=? AND avatar_version=?").use { sql ->
            sql.setLong(1, result.version); sql.setString(2, result.kind.name)
            sql.setObject(3, result.legacyImageId); sql.setLong(4, userId); sql.setLong(5, before.version)
            if (sql.executeUpdate() != 1) throw Conflict()
        }
        authorityFence()
        return result
    }

    /** Actual bounded bytes/hash validation under the same row lock, not a stored-hash assertion. */
    fun verifiedPng(connection: Connection, userId: Long): CanonicalAvatarPng? {
        transactionRequired(connection)
        val state = selectCurrent(connection, userId, true) ?: throw Conflict()
        if (state.kind == Kind.LEGACY) throw UnsupportedLegacy()
        if (state.kind == Kind.ABSENT) return null
        return connection.prepareStatement("SELECT png_bytes FROM avatar_state WHERE user_id=?").use { sql ->
            sql.setLong(1, userId)
            sql.executeQuery().use { rows ->
                check(rows.next())
                rows.getBinaryStream(1).use { input ->
                    checkNotNull(input) { "Avatar content unavailable" }
                    CanonicalAvatarPng.validate(input, checkNotNull(state.hash))
                }
            }
        }
    }

    /** Never commits. A failed final fence MUST cause the owner to roll back the whole transaction. */
    fun apply(connection: Connection, intent: Intent, png: CanonicalAvatarPng?, authorityFence: () -> Unit): Receipt {
        return applyWithLegacy(connection, intent, png, null, authorityFence)
    }

    /** Legacy hash reader is trusted native I/O under the same locks, never a caller assertion. */
    fun applyWithLegacy(connection: Connection, intent: Intent, png: CanonicalAvatarPng?,
                        legacyHash: ((Long) -> String)?, authorityFence: () -> Unit): Receipt {
        transactionRequired(connection)
        require(if (intent.action == Action.REPLACE) png?.sha256 == intent.desiredHash else png == null)
        authorityFence()
        val digest = intent.digest()
        receipt(connection, intent.scope.installation, intent.operation)?.let { original ->
            if (original.intentDigest != digest) throw Conflict()
            authorityFence()
            return original // Immutable original outcome; NOT current avatar readback.
        }
        val before = selectCurrent(connection, intent.scope.userId, true) ?: throw Conflict()
        // A duplicate may have committed while we waited for this user's lock.
        // Original receipt identity wins over today's changed version/hash.
        receipt(connection, intent.scope.installation, intent.operation)?.let { original ->
            if (original.intentDigest != digest) throw Conflict()
            authorityFence()
            return original
        }
        val actualHash = if (before.kind == Kind.LEGACY) {
            (legacyHash ?: throw UnsupportedLegacy())(checkNotNull(before.legacyImageId)).also {
                if (!it.matches(HASH)) throw UnsupportedLegacy()
            }
        } else before.hash
        if (before.version != intent.expectedVersion || actualHash != intent.expectedHash) throw Conflict()
        val version = Math.addExact(before.version, 1)
        val bytes = png?.copyBytes()
        connection.prepareStatement("UPDATE avatar_state SET avatar_version=?,kind=?,content_hash=?,png_bytes=?,legacy_image_id=NULL WHERE user_id=? AND avatar_version=?").use { sql ->
            sql.setLong(1, version); sql.setString(2, if (png == null) Kind.ABSENT.name else Kind.PNG.name)
            sql.setString(3, png?.sha256); sql.setBytes(4, bytes)
            sql.setLong(5, intent.scope.userId); sql.setLong(6, before.version)
            if (sql.executeUpdate() != 1) throw Conflict()
        }
        val result = Receipt(intent.scope, intent.operation, digest, intent.action, version, png?.sha256,
            intent.sourceGeneration, Instant.ofEpochMilli(System.currentTimeMillis()))
        connection.prepareStatement("""INSERT INTO avatar_receipt(installation_id,operation_id,intent_hash,
            restore_id,configuration_revision,image_digest,build_checksum,grant_id,grant_generation,
            binding_digest,binding_generation,user_id,action,result_version,result_hash,source_generation,committed_ms)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""").use { sql ->
            val s = result.scope
            listOf(s.installation.toString(), result.operation.toString(), result.intentDigest, s.restore.toString(),
                s.configuration, s.imageDigest, s.buildChecksum, s.grant.toString(), s.grantGeneration,
                s.bindingDigest, s.bindingGeneration, s.userId, result.action.name, result.resultingVersion,
                result.resultingHash, result.sourceGeneration, result.committedAt.toEpochMilli())
                .forEachIndexed { index, value -> sql.setObject(index + 1, value) }
            sql.executeUpdate()
        }
        authorityFence()
        return result
    }

    /** Storage primitive only. Future HTTP recovery MUST enforce its separate exact-operation grant. */
    fun receipt(connection: Connection, installation: UUID, operation: UUID): Receipt? =
        connection.prepareStatement("SELECT * FROM avatar_receipt WHERE installation_id=? AND operation_id=?").use { sql ->
            sql.setString(1, installation.toString()); sql.setString(2, operation.toString())
            sql.executeQuery().use { rows -> if (rows.next()) receiptRow(rows) else null }
        }

    private fun selectCurrent(connection: Connection, userId: Long, locked: Boolean): Current? =
        connection.prepareStatement("SELECT avatar_version,kind,content_hash,legacy_image_id FROM avatar_state WHERE user_id=?" + if (locked) " FOR UPDATE" else "").use { sql ->
            sql.setLong(1, userId)
            sql.executeQuery().use { rows ->
                if (!rows.next()) null else {
                    val legacy = rows.getLong(4).let { if (rows.wasNull()) null else it }
                    Current(rows.getLong(1), Kind.valueOf(rows.getString(2)), rows.getString(3), legacy)
                }
            }
        }
    private fun receiptRow(row: ResultSet): Receipt = Receipt(
        Scope(UUID.fromString(row.getString("installation_id")), UUID.fromString(row.getString("restore_id")),
            row.getLong("configuration_revision"), row.getString("image_digest"), row.getString("build_checksum"),
            UUID.fromString(row.getString("grant_id")), row.getLong("grant_generation"), row.getString("binding_digest"),
            row.getLong("binding_generation"), row.getLong("user_id")),
        UUID.fromString(row.getString("operation_id")), row.getString("intent_hash"), Action.valueOf(row.getString("action")),
        row.getLong("result_version"), row.getString("result_hash"), row.getLong("source_generation"),
        Instant.ofEpochMilli(row.getLong("committed_ms")))

    private fun transactionRequired(connection: Connection) {
        check(!connection.autoCommit) { "Avatar ledger requires an owned transaction" }
    }
    companion object {
        private val HASH = Regex("[0-9a-f]{64}")
        private fun hash(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
