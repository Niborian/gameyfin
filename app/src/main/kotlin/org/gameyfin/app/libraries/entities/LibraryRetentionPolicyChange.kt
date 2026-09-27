package org.gameyfin.app.libraries.entities

import jakarta.persistence.*
import java.time.Instant

/** Immutable audit record for a retention-policy metadata change. */
@Entity
class LibraryRetentionPolicyChange(
    @Id @GeneratedValue(strategy = GenerationType.AUTO) var id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) val policy: LibraryRetentionPolicy,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val previousMode: LibraryRetentionPolicyMode,
    val previousKeepLatestCount: Int? = null,
    val previousGracePeriodDays: Int? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val newMode: LibraryRetentionPolicyMode,
    val newKeepLatestCount: Int? = null,
    val newGracePeriodDays: Int? = null,
    @Column(nullable = false) val changedAt: Instant = Instant.now(),
    @Column(nullable = false) val actor: String,
)
