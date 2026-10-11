package org.gameyfin.app.libraries.entities

import jakarta.persistence.*
import org.gameyfin.app.games.entities.Game
import org.gameyfin.pluginapi.gamemetadata.Platform
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

@Entity
@EntityListeners(LibraryEntityListener::class)
open class Library(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null,

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    open var createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(nullable = false)
    open var updatedAt: Instant? = null,

    open var name: String,

    @OneToMany(fetch = FetchType.EAGER, orphanRemoval = true, cascade = [CascadeType.ALL])
    open var directories: MutableList<DirectoryMapping> = ArrayList(),

    @ElementCollection(targetClass = Platform::class, fetch = FetchType.EAGER)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    open var platforms: MutableList<Platform> = ArrayList(),

    @Enumerated(EnumType.STRING)
    open var storageMode: LibraryStorageMode = LibraryStorageMode.DIRECT,

    @OneToMany(mappedBy = "library", fetch = FetchType.EAGER, orphanRemoval = true)
    open var games: MutableList<Game> = ArrayList(),

    @OneToMany(fetch = FetchType.EAGER, orphanRemoval = true, cascade = [CascadeType.ALL])
    open var ignoredPaths: MutableList<IgnoredPath> = ArrayList(),

    @Embedded
    open var metadata: LibraryMetadata = LibraryMetadata()
)
