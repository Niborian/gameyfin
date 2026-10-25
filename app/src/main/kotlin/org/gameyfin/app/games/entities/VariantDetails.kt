package org.gameyfin.app.games.entities

import jakarta.persistence.*
import java.time.Instant

/** Separate metadata row: legacy scans/merges of GameVariant cannot overwrite editor fields. */
@Entity
@Table(name = "VARIANT_DETAILS")
class VariantDetails(
    @Id @Column(name = "VARIANT_ID") val variantId: Long,
    @Column(nullable = false) val gameId: Long,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var store: VariantStore = VariantStore.UNKNOWN,
    var storeProductId: String? = null,
    var edition: String? = null,
    var libraryVersion: String? = null,
    var buildId: String? = null,
    @Column(nullable = false) var manualOverride: Boolean = false,
    /** Incremented only while holding this variant's database row lock; not GameVariant's version. */
    @Column(nullable = false) var revision: Long = 0,
    @Enumerated(EnumType.STRING) var source: VariantDetailsSource? = null,
    @Lob var evidence: String? = null,
    var observedAt: Instant? = null,
    var actor: String? = null,
)
