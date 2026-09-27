package org.gameyfin.app.libraries

import com.vaadin.hilla.Endpoint
import jakarta.annotation.security.RolesAllowed
import org.gameyfin.app.core.Role
import org.gameyfin.app.libraries.dto.LibraryRetentionPolicyChangeDto
import org.gameyfin.app.libraries.dto.LibraryRetentionPolicyDto
import org.gameyfin.app.libraries.dto.UpdateLibraryRetentionPolicyRequestDto

/** Administrator-only advisory retention policy metadata; no cleanup action is exposed here. */
@Endpoint
@RolesAllowed(Role.Names.ADMIN)
class LibraryRetentionPolicyEndpoint(private val policyService: LibraryRetentionPolicyService) {
    fun get(libraryId: Long): LibraryRetentionPolicyDto = policyService.get(libraryId)
    fun update(libraryId: Long, request: UpdateLibraryRetentionPolicyRequestDto): LibraryRetentionPolicyDto = policyService.update(libraryId, request)
    fun history(libraryId: Long): List<LibraryRetentionPolicyChangeDto> = policyService.history(libraryId)
}
