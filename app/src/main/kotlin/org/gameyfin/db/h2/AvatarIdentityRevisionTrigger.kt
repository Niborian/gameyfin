package org.gameyfin.db.h2

import org.h2.api.Trigger
import java.sql.Connection
import java.sql.SQLException

/** Native provenance fence, not an avatar grant. Unmapped columns cannot be reset
 * by stale Hibernate User saves. Subject changes, including unlink/relink, advance
 * revision; account incarnation UUID is immutable through updates.
 */
class AvatarIdentityRevisionTrigger : Trigger {
    private var subject = -1
    private var epoch = -1
    private var revision = -1
    override fun init(connection: Connection, schemaName: String, triggerName: String, tableName: String,
                      before: Boolean, type: Int) {
        val columns = mutableMapOf<String, Int>()
        connection.metaData.getColumns(null, schemaName, tableName, null).use { rows ->
            while (rows.next()) columns[rows.getString("COLUMN_NAME").uppercase()] = rows.getInt("ORDINAL_POSITION") - 1
        }
        subject = checkNotNull(columns["OIDC_PROVIDER_ID"])
        epoch = checkNotNull(columns["AVATAR_IDENTITY_EPOCH"])
        revision = checkNotNull(columns["AVATAR_IDENTITY_REVISION"])
    }
    override fun fire(connection: Connection, oldRow: Array<Any?>?, newRow: Array<Any?>?) {
        if (oldRow == null || newRow == null) return
        val previous = (oldRow[revision] as Number).toLong()
        newRow[epoch] = oldRow[epoch]
        newRow[revision] = try {
            if (oldRow[subject] != newRow[subject]) Math.addExact(previous, 1) else previous
        } catch (_: ArithmeticException) { throw SQLException("Native avatar identity revision exhausted") }
    }
}
