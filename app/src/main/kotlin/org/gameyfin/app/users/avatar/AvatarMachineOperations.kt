package org.gameyfin.app.users.avatar

import java.util.UUID

/** Trusted native adapter only. Transport never supplies a native account or provisions authority. */
interface AvatarMachineOperations {
    fun preflight(secret: String, scope: AvatarLedger.Scope, requestFence: () -> Unit = {})
    fun current(secret: String, scope: AvatarLedger.Scope, requestFence: () -> Unit = {}): AvatarLedger.Current
    fun apply(secret: String, intent: AvatarLedger.Intent, png: CanonicalAvatarPng?,
              requestFence: () -> Unit = {}): AvatarLedger.Receipt
    fun originalReceipt(secret: String, installation: UUID, operation: UUID, intentDigest: String,
                        restore: UUID, requestFence: () -> Unit = {}): AvatarLedger.Receipt?
}
