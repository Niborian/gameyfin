package org.gameyfin.app.games.entities

import jakarta.persistence.*
import java.time.Instant

@Entity
class VariantQuarantineRecord(
    @Id @GeneratedValue(strategy = GenerationType.AUTO) var id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) val variant: GameVariant,
    @Column(nullable = false) val originalPath: String,
    @Column(nullable = false) val quarantinePath: String,
    @Column(nullable = false) val quarantinedAt: Instant,
    @Column(nullable = false) val recoverableUntil: Instant,
    @Column(nullable = false) val actor: String,
    @Column(nullable = false) val reason: String,
    var restoredAt: Instant? = null,
    var restoredBy: String? = null
)
