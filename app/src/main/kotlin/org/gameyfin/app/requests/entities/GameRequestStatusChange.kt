package org.gameyfin.app.requests.entities

import jakarta.persistence.*
import org.gameyfin.app.requests.status.GameRequestStatus
import java.time.Instant

@Entity
class GameRequestStatusChange(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    val gameRequest: GameRequest,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val previousStatus: GameRequestStatus,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val newStatus: GameRequestStatus,

    @Column(nullable = false)
    val changedAt: Instant = Instant.now(),

    @Column(nullable = false)
    val actor: String,

    @Lob
    val reason: String? = null
)
