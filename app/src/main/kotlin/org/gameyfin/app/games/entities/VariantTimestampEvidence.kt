package org.gameyfin.app.games.entities

import jakarta.persistence.*
import java.time.Instant

@Entity
class VariantTimestampEvidence(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    val variant: GameVariant,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val kind: VariantTimestampKind,

    @Column(nullable = false)
    val observedAt: Instant,

    @Lob
    val provenance: String,

    @Column(nullable = false)
    val recordedAt: Instant = Instant.now(),

    @Column(nullable = false)
    val actor: String
)
