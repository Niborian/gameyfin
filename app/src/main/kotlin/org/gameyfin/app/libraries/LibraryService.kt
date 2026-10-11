package org.gameyfin.app.libraries

import com.vaadin.hilla.exception.EndpointException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gameyfin.app.core.security.getCurrentAuth
import org.gameyfin.app.core.security.isCurrentUserAdmin
import org.gameyfin.app.libraries.entities.IgnoredPathGroupedVariantSource
import org.gameyfin.app.libraries.entities.IgnoredPathPluginSource
import org.gameyfin.app.libraries.entities.IgnoredPathUserSource
import org.gameyfin.app.libraries.dto.*
import org.gameyfin.app.libraries.entities.DirectoryMapping
import org.gameyfin.app.libraries.entities.IgnoredPathSourceType
import org.gameyfin.app.libraries.entities.Library
import org.gameyfin.app.libraries.enums.ScanType
import org.gameyfin.app.libraries.extensions.toEntity
import org.gameyfin.app.users.UserService
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

@Service
class LibraryService(
    private val libraryRepository: LibraryRepository,
    private val libraryScanService: LibraryScanService,
    private val userService: UserService,
    private val ignoredPathRepository: IgnoredPathRepository,
) {

    companion object {
        private val log = KotlinLogging.logger {}

        /* Websockets */
        private val libraryUserEvents = Sinks.many().multicast().onBackpressureBuffer<LibraryUserEvent>(1024, false)
        private val libraryAdminEvents = Sinks.many().multicast().onBackpressureBuffer<LibraryAdminEvent>(1024, false)

        fun subscribeUser(): Flux<List<LibraryUserEvent>> {
            log.debug { "New user subscription for libraryUserEvents" }
            return libraryUserEvents.asFlux()
                .buffer(100.milliseconds.toJavaDuration())
                .doOnSubscribe {
                    log.debug { "Subscriber added to user libraryUserEvents [${libraryUserEvents.currentSubscriberCount()}]" }
                }
                .doFinally {
                    log.debug { "Subscriber removed from user libraryUserEvents with signal type $it [${libraryUserEvents.currentSubscriberCount()}]" }
                }
        }

        fun subscribeAdmin(): Flux<List<LibraryAdminEvent>> {
            log.debug { "New admin subscription for libraryAdminEvents" }
            return libraryAdminEvents.asFlux()
                .buffer(100.milliseconds.toJavaDuration())
                .doOnSubscribe {
                    log.debug { "Subscriber added to admin libraryAdminEvents [${libraryAdminEvents.currentSubscriberCount()}]" }
                }
                .doFinally {
                    log.debug { "Subscriber removed from admin libraryAdminEvents with signal type $it [${libraryAdminEvents.currentSubscriberCount()}]" }
                }
        }

        fun emitUser(event: LibraryUserEvent) {
            libraryUserEvents.tryEmitNext(event)
        }

        fun emitAdmin(event: LibraryAdminEvent) {
            libraryAdminEvents.tryEmitNext(event)
        }
    }


    /**
     * Retrieves the browse DTOs without loading each library's eager game graph.
     * Admin-only paths and source details are not queried for ordinary users.
     */
    @Transactional(readOnly = true)
    fun getAll(): List<LibraryDto> {
        val libraries = libraryRepository.findBrowseLibraries()
        if (libraries.isEmpty()) return emptyList()
        val games = libraryRepository.findBrowseGames().groupBy { it.libraryId }
        if (!isCurrentUserAdmin()) return libraries.map { library ->
            LibraryUserDto(library.id, library.name, library.createdAt!!,
                games[library.id].orEmpty().map { it.gameId },
                LibraryMetadataDto(library.displayOnHomepage, library.displayOrder))
        }

        val directories = libraryRepository.findBrowseDirectories().groupBy { it.libraryId }
        val platforms = libraryRepository.findBrowsePlatforms().groupBy { it.libraryId }
        val ignored = libraryRepository.findBrowseIgnoredPaths().groupBy { it.libraryId }
        val users = libraryRepository.findBrowseIgnoredUsers().associate { it.sourceId to it.userId }
        val plugins = libraryRepository.findBrowseIgnoredPlugins().groupBy { it.sourceId }
        return libraries.map { library ->
            val libraryGames = games[library.id].orEmpty()
            LibraryAdminDto(library.id, library.name, library.createdAt!!,
                directories[library.id].orEmpty().map { DirectoryMappingDto(it.internalPath, it.externalPath) },
                platforms[library.id].orEmpty().map { it.platform }, library.storageMode,
                libraryGames.map { it.gameId },
                LibraryStatsDto(libraryGames.size, libraryGames.sumOf { it.downloadCount }),
                ignored[library.id].orEmpty().map { path ->
                    val (type, source) = when (path.sourceType) {
                        IgnoredPathUserSource::class.java -> IgnoredPathSourceTypeDto.USER to users.getValue(path.sourceId).toString()
                        IgnoredPathPluginSource::class.java -> IgnoredPathSourceTypeDto.PLUGIN to
                            plugins[path.sourceId].orEmpty().joinToString("\", \"", "[\"", "\"]") { it.pluginId }
                        IgnoredPathGroupedVariantSource::class.java -> IgnoredPathSourceTypeDto.GROUPED_VARIANT to ""
                        else -> error("Unknown IgnoredPathSource type")
                    }
                    IgnoredPathDto(path.id, path.path, type, source)
                }, LibraryMetadataDto(library.displayOnHomepage, library.displayOrder))
        }
    }

    /**
     * Retrieves a library by its ID.
     */
    fun getById(libraryId: Long): Library {
        val library = libraryRepository.findByIdOrNull(libraryId)
            ?: throw IllegalArgumentException("Library with ID $libraryId not found")
        return library
    }

    /**
     * Creates or updates a library in the repository.
     *
     * @param library: The library to create or update.
     * @return The created or updated LibraryDto object.
     */
    fun create(library: LibraryAdminDto, scanAfterCreation: Boolean) {
        // Check for duplicate directories before creating a new library
        checkForDuplicateDirectories(library.directories.map { it.internalPath })

        val directories = library.directories.distinctBy { it.internalPath }.map {
            DirectoryMapping(internalPath = it.internalPath, externalPath = it.externalPath)
        }.toMutableList()

        var newLibrary = Library(
            name = library.name,
            directories = directories,
            platforms = library.platforms.toMutableList(),
            storageMode = library.storageMode
        )

        newLibrary = libraryRepository.save(newLibrary)

        if (scanAfterCreation) {
            libraryScanService.triggerScan(ScanType.QUICK, listOf(newLibrary.id!!))
        }
    }

    /**
     * Updates a library entity with the non-null fields from a LibraryUpdateDto.
     *
     * @param libraryUpdateDto: The LibraryUpdateDto containing the fields to update.
     * @return The updated LibraryDto.
     * @throws IllegalArgumentException if the library ID is null or the library is not found.
     */
    @Transactional
    fun update(libraryUpdateDto: LibraryUpdateDto) {
        val library = libraryRepository.findByIdOrNull(libraryUpdateDto.id)
            ?: throw IllegalArgumentException("Library with ID $libraryUpdateDto.id not found")

        libraryUpdateDto.name?.let { library.name = it }

        libraryUpdateDto.directories?.let { updatedDirs ->
            checkForDuplicateDirectories(
                updatedDirs.map { it.internalPath },
                excludeLibraryId = library.id
            )

            val existingMappings = library.directories.associateBy { it.internalPath }
            val updatedInternalPaths = updatedDirs.map { it.internalPath }.toSet()

            // Remove mappings not present in the update
            val removedDirs = library.directories.filter { it.internalPath !in updatedInternalPaths }
            library.directories.removeAll(removedDirs)

            // Remove all games within removed directories
            val removedDirPaths = removedDirs.map { it.internalPath }
            library.games.removeIf { game ->
                removedDirPaths.any { removedPath ->
                    game.metadata.path.startsWith(removedPath)
                }
            }

            // Update existing or add new directory mappings
            updatedDirs.forEach { dto ->
                val mapping = existingMappings[dto.internalPath]
                if (mapping != null) {
                    mapping.externalPath = dto.externalPath // update fields
                } else {
                    library.directories.add(
                        DirectoryMapping(
                            internalPath = dto.internalPath,
                            externalPath = dto.externalPath
                        )
                    )
                }
            }
        }

        libraryUpdateDto.platforms?.let {
            library.platforms.clear()
            library.platforms.addAll(it)
        }

        libraryUpdateDto.storageMode?.let { library.storageMode = it }

        // Only allow updating USER sourced ignored paths; preserve PLUGIN sourced ones
        libraryUpdateDto.ignoredPaths
            ?.filter { it.sourceType == IgnoredPathSourceTypeDto.USER } // Only USER source type is supported for updates
            ?.let { dtos ->
                val currentUser = getCurrentAuth()?.let { auth -> userService.getByUsername(auth.name) }

                // Remove existing USER-sourced ignored paths, keep PLUGIN-sourced ones intact
                library.ignoredPaths.removeIf { it.getType() == IgnoredPathSourceType.USER }

                // Recreate user-sourced paths (reuse existing entity if same path already present globally)
                val pathsToAdd = dtos.map { dto ->
                    val existingPath = ignoredPathRepository.findByPath(dto.path)
                    existingPath ?: dto.toEntity(currentUser)
                }

                library.ignoredPaths.addAll(pathsToAdd)
            }

        libraryUpdateDto.metadata?.let {
            library.metadata = it.toEntity()
        }

        library.updatedAt = Instant.now()
        libraryRepository.save(library)
    }

    /**
     * Updates multiple libraries in the repository.
     */
    @Transactional
    fun update(libraries: Collection<LibraryUpdateDto>) {
        libraries.forEach { update(it) }
    }

    /**
     * Deletes a library from the repository.
     *
     * @param libraryId: ID of the library to delete.
     */
    fun delete(libraryId: Long) {
        libraryRepository.deleteById(libraryId)
    }

    private fun checkForDuplicateDirectories(newLibraryFolders: List<String>, excludeLibraryId: Long? = null) {
        val alreadyConfiguredFolders = libraryRepository.findByPaths(newLibraryFolders)
            .filter { it.second.id != excludeLibraryId } // Exclude the current library if updating
            .map { Pair(it.first, it.second) } // Convert to Pair for easier error message formatting

        if (alreadyConfiguredFolders.isNotEmpty()) {
            throw EndpointException(
                "The following directories are already mapped to another library: " +
                        alreadyConfiguredFolders.joinToString(", ") { "${it.first} (${it.second.name})" }
            )
        }
    }
}
