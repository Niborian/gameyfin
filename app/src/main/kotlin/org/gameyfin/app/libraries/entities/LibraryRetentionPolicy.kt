package org.gameyfin.app.libraries.entities

import jakarta.persistence.*
import java.time.Instant

/**
 * An administrator's advisory retention preference for one library.
 *
 * This record intentionally does not schedule, select, hide, quarantine, move, or delete content.
 * Any future action based on it must be reviewed and implemented separately.
 */
@Entity
class LibraryRetentionPolicy(
    @Id @GeneratedValue(strategy = GenerationType.AUTO) var id: Long? = null,
    @OneToOne(fetch = FetchType.LAZY, optional = false) val library: Library,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var mode: LibraryRetentionPolicyMode,
    var keepLatestCount: Int? = null,
    var gracePeriodDays: Int? = null,
    @Column(nullable = false) var updatedAt: Instant = Instant.now(),
    @Column(nullable = false) var updatedBy: String,
)

enum class LibraryRetentionPolicyMode {
    KEEP_ALL,
    KEEP_LATEST_N,
    GRACE_PERIOD,
}
