package org.gameyfin.app.users.avatar

import java.sql.Connection
import javax.sql.DataSource

/**
 * Nonregistered transaction owner for the native adapter. Does NOT authenticate
 * HTTP, install grants, load independent restore epochs or perform request I/O.
 * The trusted account reader must lock/recheck the real native account/binding
 * on this exact connection through COMMIT; a caller DTO is never an account.
 * DataSource must return a fresh, unshared, non-Spring-managed connection.
 */
class AvatarTransactions(
    private val dataSource: DataSource,
    private val authority: AvatarAuthority,
    private val ledger: AvatarLedger,
    private val legacyHash: ((Connection, Long) -> String)? = null,
    private val nativeAccount: (Connection, Long) -> AvatarAuthority.NativeAccount?
) {
    /** Before network/body I/O. Lease and DB locks are released before returning;
     * apply must acquire fresh authority again after the body has been validated. */
    fun preflight(secret: String, scope: AvatarLedger.Scope, requestFence: () -> Unit = {}) = ownedTransaction { connection ->
        requestFence()
        authority.acquireCurrentTransaction(secret, scope,
            { nativeAccount(connection, scope.userId) }, { connection.rollback() }).use { lease ->
            rollbackOnFailure(connection) {
                requestFence(); lease.checkCurrent(); requestFence()
                connection.commit()
            }
        }
    }

    /** Body validation/bounded decoding must already have completed outside any lease. */
    fun apply(secret: String, intent: AvatarLedger.Intent, png: CanonicalAvatarPng?, requestFence: () -> Unit = {}): AvatarLedger.Receipt =
        ownedTransaction { connection ->
            requestFence()
            authority.acquireCurrentTransaction(secret, intent.scope,
                { nativeAccount(connection, intent.scope.userId) }, { connection.rollback() }).use { lease ->
                rollbackOnFailure(connection) {
                    val check = { requestFence(); lease.checkCurrent(); requestFence() }
                    check()
                    ledger.prepareExistingUser(connection, intent.scope.userId)
                    val result = ledger.applyWithLegacy(connection, intent, png,
                        legacyHash?.let { reader -> { imageId -> reader(connection, imageId) } }, check)
                    check()
                    connection.commit() // Never return a receipt before COMMIT acknowledgement.
                    result
                }
            }
        }.also { AvatarInvalidations.changed(it.scope.userId) }

    /** Validated actual current representation hash, not the old operation's result. */
    fun current(secret: String, scope: AvatarLedger.Scope, requestFence: () -> Unit = {}): AvatarLedger.Current = ownedTransaction { connection ->
        requestFence()
        authority.acquireCurrentTransaction(secret, scope,
            { nativeAccount(connection, scope.userId) }, { connection.rollback() }).use { lease ->
            rollbackOnFailure(connection) {
                ledger.prepareExistingUser(connection, scope.userId)
                val state = ledger.lockedCurrent(connection, scope.userId) ?: throw AvatarLedger.Conflict()
                val hash = when (state.kind) {
                    AvatarLedger.Kind.ABSENT -> null
                    AvatarLedger.Kind.PNG -> checkNotNull(ledger.verifiedPng(connection, scope.userId)).sha256
                    AvatarLedger.Kind.LEGACY -> (legacyHash ?: throw AvatarLedger.UnsupportedLegacy())(
                        connection, checkNotNull(state.legacyImageId)).also {
                        if (!it.matches(Regex("[0-9a-f]{64}"))) throw AvatarLedger.UnsupportedLegacy()
                    }
                }
                requestFence(); lease.checkCurrent(); requestFence()
                connection.commit()
                state.copy(hash = hash)
            }
        }
    }

    /** Exact historical recovery never initializes an account or reads today's content. */
    fun originalReceipt(secret: String, installation: java.util.UUID, operation: java.util.UUID,
                        intentDigest: String, historicalRestore: java.util.UUID, requestFence: () -> Unit = {}): AvatarLedger.Receipt? =
        ownedTransaction { connection ->
            requestFence()
            authority.acquireHistorical(secret, installation, operation, intentDigest, historicalRestore).use { lease ->
                rollbackOnFailure(connection) {
                    val result = ledger.receipt(connection, installation, operation)
                    result?.let(lease::checkHistorical)
                    requestFence()
                    connection.commit()
                    result // Absence is unknown, NOT permission to redispatch or proof of never committed.
                }
            }
        }

    private fun <T> ownedTransaction(work: (Connection) -> T): T = dataSource.connection.use { connection ->
        check(connection.autoCommit) { "Avatar transaction requires a fresh owned connection" }
        connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        connection.autoCommit = false
        rollbackOnFailure(connection) { work(connection) }
    }

    private fun <T> rollbackOnFailure(connection: Connection, work: () -> T): T =
        try { work() } catch (failure: Throwable) {
            try { connection.rollback() } catch (rollbackFailure: Throwable) { failure.addSuppressed(rollbackFailure) }
            throw failure
        }
}
