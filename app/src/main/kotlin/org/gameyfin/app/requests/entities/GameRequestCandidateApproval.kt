package org.gameyfin.app.requests.entities

import jakarta.persistence.*
import java.time.Instant

/** Immutable administrator approval audit; approval does not queue or download anything. */
@Entity
class GameRequestCandidateApproval(
    @Id @GeneratedValue(strategy = GenerationType.AUTO) var id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) val candidate: GameRequestCandidate,
    @Column(nullable = false) val approvedAt: Instant = Instant.now(),
    @Column(nullable = false) val approvedBy: String,
    @Lob val reason: String? = null,
)
