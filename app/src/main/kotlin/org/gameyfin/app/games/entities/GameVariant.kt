package org.gameyfin.app.games.entities

import jakarta.persistence.*
import jakarta.persistence.CascadeType.ALL
import java.time.Instant

@Entity
class GameVariant(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    var game: Game,

    @Column(nullable = false)
    var name: String = "Normal",

    @Column(nullable = false)
    var version: String = "0",

    @Column(nullable = false, unique = true)
    var path: String,

    var fileSize: Long? = null,

    @ElementCollection(fetch = FetchType.EAGER)
    var tags: MutableSet<String> = mutableSetOf(),

    var steamAppId: String? = null,

    var steamAppIdVerifiedAt: Instant? = null,

    @Column(nullable = false)
    var steamAppIdManualOverride: Boolean = false,

    var localBuildVersion: String? = null,

    var localBuildObservedAt: Instant? = null,

    var steamUpdateMarker: String? = null,

    var steamMetadataObservedAt: Instant? = null,

    @Lob
    var steamMetadataSource: String? = null,

    var steamMetadataCheckedAt: Instant? = null,

    /** Marker deliberately ignored by an administrator; it never triggers a download action. */
    var steamUpdateIgnoredMarker: String? = null,

    /** An administrator may defer review of the currently observed marker until this instant. */
    var steamUpdateSnoozedUntil: Instant? = null,

    @Lob
    var launchArgs: String? = null,

    @Lob
    var patchInfo: String? = null,

    var isDefault: Boolean = false,

    var defaultLocked: Boolean = false,

    var isLatestForVariant: Boolean = false,

    var scanManaged: Boolean = true,

    @Enumerated(EnumType.STRING)
    var linkStatus: VariantLinkStatus = VariantLinkStatus.DIRECT,

    @Lob
    var linkFallbackReason: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var retirementState: VariantRetirementState = VariantRetirementState.ACTIVE,

    var retirementReviewAt: Instant? = null,

    /** First administrator observation of an active newer version of this variant. */
    var supersededAt: Instant? = null,

    var supersededByVariantId: Long? = null,

    /** Archived mirror quarantine location; original catalog paths remain unchanged until restoration. */
    var quarantinePath: String? = null,

    @OneToMany(mappedBy = "variant", cascade = [ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    var contents: MutableList<VariantContent> = mutableListOf()
)
