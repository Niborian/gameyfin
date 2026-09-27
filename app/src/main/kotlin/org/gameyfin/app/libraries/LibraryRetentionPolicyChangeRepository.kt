package org.gameyfin.app.libraries

import org.gameyfin.app.libraries.entities.LibraryRetentionPolicyChange
import org.springframework.data.jpa.repository.JpaRepository

interface LibraryRetentionPolicyChangeRepository : JpaRepository<LibraryRetentionPolicyChange, Long> {
    fun findAllByPolicyLibraryIdOrderByChangedAtAsc(libraryId: Long): List<LibraryRetentionPolicyChange>
}
