package org.gameyfin.app.games.dto

import java.time.Instant

data class QuarantineVariantRequestDto(val confirmation: String, val reason: String, val recoveryDays: Int = 30)
data class VariantQuarantineDto(val id: Long, val variantId: Long, val originalPath: String, val quarantinePath: String,
    val quarantinedAt: Instant, val recoverableUntil: Instant, val actor: String, val reason: String,
    val restoredAt: Instant?, val restoredBy: String?)
