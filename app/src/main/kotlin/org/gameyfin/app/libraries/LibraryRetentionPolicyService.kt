package org.gameyfin.app.libraries

import org.gameyfin.app.core.security.getCurrentAuth
import org.gameyfin.app.libraries.dto.LibraryRetentionPolicyChangeDto
import org.gameyfin.app.libraries.dto.LibraryRetentionPolicyDto
import org.gameyfin.app.libraries.dto.UpdateLibraryRetentionPolicyRequestDto
import org.gameyfin.app.libraries.entities.LibraryRetentionPolicy
import org.gameyfin.app.libraries.entities.LibraryRetentionPolicyChange
import org.gameyfin.app.libraries.entities.LibraryRetentionPolicyMode
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Stores advisory policy metadata only; it has no retention evaluation or filesystem behaviour. */
@Service
class LibraryRetentionPolicyService(
    private val libraryRepository: LibraryRepository,
    private val policyRepository: LibraryRetentionPolicyRepository,
    private val changeRepository: LibraryRetentionPolicyChangeRepository,
) {
    @Transactional(readOnly = true)
    fun get(libraryId: Long): LibraryRetentionPolicyDto {
        requireLibrary(libraryId)
        return policyRepository.findByLibraryId(libraryId)?.toDto()
            ?: LibraryRetentionPolicyDto(libraryId = libraryId, mode = LibraryRetentionPolicyMode.KEEP_ALL)
    }

    @Transactional
    fun update(libraryId: Long, request: UpdateLibraryRetentionPolicyRequestDto): LibraryRetentionPolicyDto {
        val library = requireLibrary(libraryId)
        val normalized = normalize(request)
        val existing = policyRepository.findByLibraryId(libraryId)
        val previous = existing?.snapshot() ?: Snapshot(LibraryRetentionPolicyMode.KEEP_ALL, null, null)
        val policy = existing ?: LibraryRetentionPolicy(library = library, mode = normalized.mode, updatedBy = actor())
        policy.mode = normalized.mode
        policy.keepLatestCount = normalized.keepLatestCount
        policy.gracePeriodDays = normalized.gracePeriodDays
        policy.updatedAt = Instant.now()
        policy.updatedBy = actor()
        val saved = policyRepository.save(policy)
        changeRepository.save(LibraryRetentionPolicyChange(
            policy = saved,
            previousMode = previous.mode,
            previousKeepLatestCount = previous.keepLatestCount,
            previousGracePeriodDays = previous.gracePeriodDays,
            newMode = normalized.mode,
            newKeepLatestCount = normalized.keepLatestCount,
            newGracePeriodDays = normalized.gracePeriodDays,
            actor = actor(),
        ))
        return saved.toDto()
    }

    @Transactional(readOnly = true)
    fun history(libraryId: Long): List<LibraryRetentionPolicyChangeDto> {
        requireLibrary(libraryId)
        return changeRepository.findAllByPolicyLibraryIdOrderByChangedAtAsc(libraryId).map { it.toDto() }
    }

    private fun requireLibrary(libraryId: Long) = libraryRepository.findByIdOrNull(libraryId)
        ?: throw IllegalArgumentException("Library with ID $libraryId not found")

    private fun normalize(request: UpdateLibraryRetentionPolicyRequestDto): Snapshot = when (request.mode) {
        LibraryRetentionPolicyMode.KEEP_ALL -> {
            require(request.keepLatestCount == null && request.gracePeriodDays == null) { "KEEP_ALL does not accept parameters" }
            Snapshot(request.mode, null, null)
        }
        LibraryRetentionPolicyMode.KEEP_LATEST_N -> {
            require(request.keepLatestCount != null && request.keepLatestCount > 0) { "KEEP_LATEST_N requires a positive keepLatestCount" }
            require(request.gracePeriodDays == null) { "KEEP_LATEST_N does not accept gracePeriodDays" }
            Snapshot(request.mode, request.keepLatestCount, null)
        }
        LibraryRetentionPolicyMode.GRACE_PERIOD -> {
            require(request.gracePeriodDays != null && request.gracePeriodDays > 0) { "GRACE_PERIOD requires positive gracePeriodDays" }
            require(request.keepLatestCount == null) { "GRACE_PERIOD does not accept keepLatestCount" }
            Snapshot(request.mode, null, request.gracePeriodDays)
        }
    }

    private fun actor() = getCurrentAuth()?.name?.takeIf { it.isNotBlank() } ?: "system"
    private fun LibraryRetentionPolicy.snapshot() = Snapshot(mode, keepLatestCount, gracePeriodDays)
    private fun LibraryRetentionPolicy.toDto() = LibraryRetentionPolicyDto(requireNotNull(library.id), mode, keepLatestCount, gracePeriodDays, updatedAt, updatedBy)
    private fun LibraryRetentionPolicyChange.toDto() = LibraryRetentionPolicyChangeDto(requireNotNull(id), previousMode, previousKeepLatestCount, previousGracePeriodDays, newMode, newKeepLatestCount, newGracePeriodDays, changedAt, actor)
    private data class Snapshot(val mode: LibraryRetentionPolicyMode, val keepLatestCount: Int?, val gracePeriodDays: Int?)
}
