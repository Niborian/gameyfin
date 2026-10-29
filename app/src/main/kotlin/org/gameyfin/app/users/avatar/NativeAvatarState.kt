package org.gameyfin.app.users.avatar

import org.gameyfin.app.media.Image
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.sql.Connection
import javax.sql.DataSource

/**
 * Native ordinary-writer/read adapter. Off by default; machine authority is NOT
 * enabled by this switch. Keep disabled until all native compatibility gates pass.
 * No eager BLOB loading for DTO/catalogue projections and no old image deletion.
 */
@Service
@ConditionalOnProperty(prefix = "gameyfin.avatar.overlay", name = ["enabled"], havingValue = "true")
class NativeAvatarState(private val dataSource: DataSource) {
    private val ledger = AvatarLedger()
    data class Content(val current: AvatarLedger.Current, val png: CanonicalAvatarPng?)

    fun metadata(userId: Long): AvatarLedger.Current? = dataSource.connection.use { ledger.current(it, userId) }

    /** Bounded metadata-only batches, never one connection/query per listed user.
     * Missing state remains missing so callers preserve the native legacy fallback. */
    fun metadata(userIds: Collection<Long>): Map<Long, AvatarLedger.Current> {
        if (userIds.isEmpty()) return emptyMap()
        require(userIds.all { it > 0 })
        val ids = userIds.distinct()
        return dataSource.connection.use { connection ->
            buildMap {
                for (batch in ids.chunked(256)) {
                    val placeholders = batch.joinToString(",") { "?" }
                    connection.prepareStatement("SELECT user_id,avatar_version,kind,content_hash,legacy_image_id " +
                        "FROM avatar_state WHERE user_id IN ($placeholders)").use { sql ->
                        sql.queryTimeout = 5
                        batch.forEachIndexed { index, id -> sql.setLong(index + 1, id) }
                        sql.executeQuery().use { rows ->
                            while (rows.next()) {
                                val legacy = rows.getLong(5).let { if (rows.wasNull()) null else it }
                                put(rows.getLong(1), AvatarLedger.Current(rows.getLong(2),
                                    AvatarLedger.Kind.valueOf(rows.getString(3)), rows.getString(4), legacy))
                            }
                        }
                    }
                }
            }
        }
    }

    /** Locks native user then state: even the uninitialized legacy fallback is
     * one atomic snapshot, not a second read racing a newly created PNG pointer. */
    fun content(userId: Long): Content? = transaction { connection ->
        val legacy = connection.prepareStatement("SELECT avatar_id FROM users WHERE id=? FOR UPDATE").use { sql ->
            sql.setLong(1, userId)
            sql.executeQuery().use { rows ->
                if (!rows.next()) throw AvatarLedger.Conflict()
                rows.getLong(1).let { if (rows.wasNull()) null else it }
            }
        }
        val current = ledger.lockedCurrent(connection, userId)
            ?: AvatarLedger.Current(0, if (legacy == null) AvatarLedger.Kind.ABSENT else AvatarLedger.Kind.LEGACY,
                null, legacy)
        Content(current, if (current.kind == AvatarLedger.Kind.PNG) ledger.verifiedPng(connection, userId) else null)
    }

    /** Called only with the actual authenticated/admin-resolved native user ID. */
    fun setPrepared(userId: Long, image: Image?): AvatarLedger.Current = transaction { connection ->
        ledger.prepareExistingUser(connection, userId)
        ledger.ordinarySetPreparedAvatar(connection, userId, image) {}
    }.also { AvatarInvalidations.changed(userId) }

    private fun <T> transaction(work: (Connection) -> T): T = dataSource.connection.use { connection ->
        check(connection.autoCommit) { "Native avatar adapter requires a fresh owned connection" }
        connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        connection.autoCommit = false
        try {
            val result = work(connection)
            connection.commit()
            result
        } catch (failure: Throwable) {
            try { connection.rollback() } catch (rollbackFailure: Throwable) { failure.addSuppressed(rollbackFailure) }
            throw failure
        }
    }
}
