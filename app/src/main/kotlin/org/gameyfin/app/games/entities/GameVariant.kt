package org.gameyfin.app.games.entities

import jakarta.persistence.*
import jakarta.persistence.CascadeType.ALL

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

    @OneToMany(mappedBy = "variant", cascade = [ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    var contents: MutableList<VariantContent> = mutableListOf()
)
