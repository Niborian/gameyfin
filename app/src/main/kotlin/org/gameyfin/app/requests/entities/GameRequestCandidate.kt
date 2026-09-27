package org.gameyfin.app.requests.entities

import jakarta.persistence.*
import java.time.Instant

/** Manually recorded review metadata only; it never represents or triggers a provider action. */
@Entity
class GameRequestCandidate(
    @Id @GeneratedValue(strategy = GenerationType.AUTO) var id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) val gameRequest: GameRequest,
    @Column(nullable = false) val providerLabel: String,
    @Column(nullable = false) val displayName: String,
    @Column(nullable = false) val externalReference: String,
    @Lob val notes: String? = null,
    @Column(nullable = false) val recordedAt: Instant = Instant.now(),
    @Column(nullable = false) val recordedBy: String,
    var selected: Boolean = false,
)
