package org.gameyfin.app.games.entities

import jakarta.persistence.*
import java.time.Instant

@Entity
class VariantClassificationReview(
    @Id @GeneratedValue(strategy = GenerationType.AUTO) var id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) val sourceGame: Game,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) val sourceVariant: GameVariant,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) val targetGame: Game,
    @Column(nullable = false) val confidence: Int,
    @Lob @Column(nullable = false) val explanation: String,
    @Lob @Column(nullable = false) val evidence: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var state: ClassificationReviewState = ClassificationReviewState.OPEN,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) val createdBy: String
)
