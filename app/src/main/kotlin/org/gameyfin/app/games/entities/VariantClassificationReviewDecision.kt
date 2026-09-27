package org.gameyfin.app.games.entities

import jakarta.persistence.*
import java.time.Instant

@Entity
class VariantClassificationReviewDecision(
    @Id @GeneratedValue(strategy = GenerationType.AUTO) var id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) val review: VariantClassificationReview,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val previousState: ClassificationReviewState,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val newState: ClassificationReviewState,
    @Column(nullable = false) val decidedAt: Instant = Instant.now(),
    @Column(nullable = false) val actor: String,
    @Lob val reason: String? = null
)
