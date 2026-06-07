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
    @CollectionTable(
        name = "VARIANT_CONTENT_PATHS",
        joinColumns = [JoinColumn(name = "VARIANT_CONTENT_ID")]
    )
    @OrderColumn(name = "PATH_INDEX")
    @Column(name = "PATH", nullable = false, length = 4096)
    var paths: MutableList<String> = mutableListOf(),

    @ElementCollection(fetch = FetchType.EAGER)
    var tags: MutableSet<String> = mutableSetOf()
)

fun VariantContent.effectivePaths(): List<String> {
    return paths.ifEmpty { mutableListOf(path) }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}
