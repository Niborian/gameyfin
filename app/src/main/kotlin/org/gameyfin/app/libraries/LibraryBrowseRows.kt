package org.gameyfin.app.libraries

import org.gameyfin.app.libraries.entities.LibraryStorageMode
import org.gameyfin.pluginapi.gamemetadata.Platform
import java.time.Instant

/** Scalar-only browse rows: never hydrate the eager Library -> Game graph. */
data class LibraryBrowseRow(val id: Long, val name: String, val createdAt: Instant?, val storageMode: LibraryStorageMode, val displayOnHomepage: Boolean, val displayOrder: Int)
data class LibraryGameBrowseRow(val libraryId: Long, val gameId: Long, val downloadCount: Int)
data class LibraryDirectoryBrowseRow(val libraryId: Long, val internalPath: String, val externalPath: String?)
data class LibraryPlatformBrowseRow(val libraryId: Long, val platform: Platform)
data class LibraryIgnoredBrowseRow(val libraryId: Long, val id: Long, val path: String, val sourceId: Long, val sourceType: Class<*>)
data class LibraryIgnoredUserBrowseRow(val sourceId: Long, val userId: Long)
data class LibraryIgnoredPluginBrowseRow(val sourceId: Long, val pluginId: String)
