package org.gameyfin.db.h2

import org.h2.api.Trigger
import java.sql.Connection

/** Every OIDC configuration write requires explicit re-verification, even if a
 * value changes away and back. Reads no configuration values or secrets. */
class AvatarOidcRevisionTrigger : Trigger {
    private var key = -1
    override fun init(connection: Connection, schemaName: String, triggerName: String, tableName: String,
                      before: Boolean, type: Int) {
        connection.metaData.getColumns(null, schemaName, tableName, null).use { rows ->
            while (rows.next()) if (rows.getString("COLUMN_NAME").equals("key", true))
                key = rows.getInt("ORDINAL_POSITION") - 1
        }
        check(key >= 0)
    }
    override fun fire(connection: Connection, oldRow: Array<Any?>?, newRow: Array<Any?>?) {
        if (listOf(oldRow?.get(key), newRow?.get(key)).any { it is String && it.startsWith("sso.oidc.") }) {
            connection.prepareStatement("UPDATE avatar_oidc_fence SET revision=revision+1 WHERE id=1").use {
                check(it.executeUpdate() == 1) { "Native avatar OIDC fence unavailable" }
            }
        }
    }
}
