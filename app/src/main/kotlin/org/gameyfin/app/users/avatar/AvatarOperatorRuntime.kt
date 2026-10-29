package org.gameyfin.app.users.avatar

import org.gameyfin.app.media.FileStorageService
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/** Nonregistered native integration bundle. Trusted bootstrap only; no browser,
 * admin-cookie provisioning, startup auto-enable or default grant. Main's private
 * operator control must pause dispatch, revoke, deliver files, reload, then resume.
 */
class AvatarOperatorRuntime(files: AvatarOperatorFiles, private val identity: AvatarOperatorLoader.RuntimeIdentity,
                            dataSource: DataSource, storage: FileStorageService) : AvatarMachineOperations {
    private val authority = AvatarAuthority()
    private val accounts = AtomicReference<NativeAvatarAccountReader?>(null)
    private val loader = AvatarOperatorLoader(files, identity)
    private var enabled = false
    private var revision: Long? = null
    private val transactions = AvatarTransactions(dataSource, authority, AvatarLedger(), AvatarLegacyReader(storage)::hash) {
        connection, id -> accounts.get()?.read(connection, id)
    }

    /** Native trusted owner hook only. Reader replacement occurs while revoked. */
    @Synchronized fun reloadOperatorFiles() {
        enabled = false
        val loaded = loader.reload(authority) { accounts.set(it) }
        revision = loaded.descriptor.configuration
        enabled = true
    }
    @Synchronized fun revokeOperatorAuthority() { enabled = false; loader.revoke(authority); accounts.set(null) }

    /** Public inventory pins and fresh process challenge only; never grants/bindings. */
    @Synchronized internal fun operatorStatus(): String =
        "STATUS 1 ${identity.installation} ${identity.imageDigest} ${identity.buildChecksum} " +
            "${identity.bootNonce} ${if (enabled) "enabled" else "revoked"} ${revision ?: 0}\n"

    override fun preflight(secret: String, scope: AvatarLedger.Scope, requestFence: () -> Unit) = transactions.preflight(secret, scope, requestFence)
    override fun current(secret: String, scope: AvatarLedger.Scope, requestFence: () -> Unit) = transactions.current(secret, scope, requestFence)
    override fun apply(secret: String, intent: AvatarLedger.Intent, png: CanonicalAvatarPng?, requestFence: () -> Unit) =
        transactions.apply(secret, intent, png, requestFence)
    override fun originalReceipt(secret: String, installation: UUID, operation: UUID, intentDigest: String,
                                 restore: UUID, requestFence: () -> Unit) =
        transactions.originalReceipt(secret, installation, operation, intentDigest, restore, requestFence)
}
