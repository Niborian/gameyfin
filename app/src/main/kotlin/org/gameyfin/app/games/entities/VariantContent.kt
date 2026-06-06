package org.gameyfin.app.games.entities

import jakarta.persistence.*

@Entity
class VariantContent(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    var variant: GameVariant,

    @Enumerated(EnumType.STRING)
    var type: VariantContentType = VariantContentType.BASE,

    @Column(nullable = false)
    var name: String,

    @Column(nullable = false)
    var path: String,

    var fileSize: Long? = null,

    var required: Boolean = false,

    var defaultSelected: Boolean = false,

    @ElementCollection(fetch = FetchType.EAGER)
    var tags: MutableSet<String> = mutableSetOf()
)
