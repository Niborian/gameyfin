package org.gameyfin.app.libraries

import org.gameyfin.app.libraries.entities.LibraryRetentionPolicy
import org.springframework.data.jpa.repository.JpaRepository

interface LibraryRetentionPolicyRepository : JpaRepository<LibraryRetentionPolicy, Long> {
    fun findByLibraryId(libraryId: Long): LibraryRetentionPolicy?
}
