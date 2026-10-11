package org.gameyfin.app.libraries

import org.gameyfin.app.libraries.entities.Library
import org.gameyfin.pluginapi.gamemetadata.Platform
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface LibraryRepository : JpaRepository<Library, Long> {
    @Query("SELECT new org.gameyfin.app.libraries.LibraryBrowseRow(l.id,l.name,l.createdAt,l.storageMode,l.metadata.displayOnHomepage,l.metadata.displayOrder) FROM Library l ORDER BY l.id")
    fun findBrowseLibraries(): List<LibraryBrowseRow>

    @Query("SELECT new org.gameyfin.app.libraries.LibraryGameBrowseRow(g.library.id,g.id,g.metadata.downloadCount) FROM Game g ORDER BY g.id")
    fun findBrowseGames(): List<LibraryGameBrowseRow>

    @Query("SELECT new org.gameyfin.app.libraries.LibraryDirectoryBrowseRow(l.id,d.internalPath,d.externalPath) FROM Library l JOIN l.directories d ORDER BY d.id")
    fun findBrowseDirectories(): List<LibraryDirectoryBrowseRow>

    @Query("SELECT new org.gameyfin.app.libraries.LibraryPlatformBrowseRow(l.id,p) FROM Library l JOIN l.platforms p")
    fun findBrowsePlatforms(): List<LibraryPlatformBrowseRow>

    @Query("SELECT new org.gameyfin.app.libraries.LibraryIgnoredBrowseRow(l.id,i.id,i.path,s.id,TYPE(s)) FROM Library l JOIN l.ignoredPaths i JOIN i.source s ORDER BY i.id")
    fun findBrowseIgnoredPaths(): List<LibraryIgnoredBrowseRow>

    @Query("SELECT new org.gameyfin.app.libraries.LibraryIgnoredUserBrowseRow(s.id,s.user.id) FROM IgnoredPathUserSource s")
    fun findBrowseIgnoredUsers(): List<LibraryIgnoredUserBrowseRow>

    @Query("SELECT new org.gameyfin.app.libraries.LibraryIgnoredPluginBrowseRow(s.id,p.pluginId) FROM IgnoredPathPluginSource s JOIN s.plugins p")
    fun findBrowseIgnoredPlugins(): List<LibraryIgnoredPluginBrowseRow>

    @Query("SELECT p FROM Library l JOIN l.platforms p WHERE l.id = :libraryId")
    fun findPlatformsByLibraryId(libraryId: Long): List<Platform>

    @Query("SELECT d.internalPath, l FROM Library l JOIN l.directories d WHERE d.internalPath IN :paths")
    fun findByPaths(paths: List<String>): List<Pair<String, Library>>

    @Query("SELECT DISTINCT p FROM Library l JOIN l.platforms p")
    fun findAllDistinctPlatforms(): Set<Platform>
}
