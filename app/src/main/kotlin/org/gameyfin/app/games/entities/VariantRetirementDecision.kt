package org.gameyfin.app.games.entities

import jakarta.persistence.*
import java.time.Instant

/** Immutable administrator audit record for an in-application retirement decision. */
@Entity
class VariantRetirementDecision(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    val variant: GameVariant,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val previousState: VariantRetirementState,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val newState: VariantRetirementState,

    @Column(nullable = false)
    val decidedAt: Instant = Instant.now(),

    @Column(nullable = false)
    val actor: String,

    @Lob
    val reason: String? = null,

    val reviewAt: Instant? = null,
    val supersededAt: Instant? = null,
    val supersededByVariantId: Long? = null
)
