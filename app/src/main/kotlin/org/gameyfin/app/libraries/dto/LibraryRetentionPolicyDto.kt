package org.gameyfin.app.libraries.dto

import org.gameyfin.app.libraries.entities.LibraryRetentionPolicyMode
import java.time.Instant

/** Advisory only. This does not select or alter retained library content. */
data class LibraryRetentionPolicyDto(
    val libraryId: Long,
    val mode: LibraryRetentionPolicyMode,
    val keepLatestCount: Int? = null,
    val gracePeriodDays: Int? = null,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
)

data class UpdateLibraryRetentionPolicyRequestDto(
    val mode: LibraryRetentionPolicyMode,
    val keepLatestCount: Int? = null,
    val gracePeriodDays: Int? = null,
)

data class LibraryRetentionPolicyChangeDto(
    val id: Long,
    val previousMode: LibraryRetentionPolicyMode,
    val previousKeepLatestCount: Int?,
    val previousGracePeriodDays: Int?,
    val newMode: LibraryRetentionPolicyMode,
    val newKeepLatestCount: Int?,
    val newGracePeriodDays: Int?,
    val changedAt: Instant,
    val actor: String,
)
