package org.gameyfin.app.requests

import com.vaadin.hilla.Endpoint
import jakarta.annotation.security.RolesAllowed
import org.gameyfin.app.core.Role
import org.gameyfin.app.requests.dto.*

@Endpoint
@RolesAllowed(Role.Names.ADMIN)
class GameRequestCandidateEndpoint(private val service: GameRequestCandidateService) {
    fun record(requestId: Long, request: RecordGameRequestCandidateDto) = service.record(requestId, request)
    fun approve(candidateId: Long, request: ApproveGameRequestCandidateDto) = service.approve(candidateId, request)
    fun select(candidateId: Long) = service.select(candidateId)
    fun list(requestId: Long) = service.list(requestId)
    fun approvals(candidateId: Long) = service.approvals(candidateId)
}
