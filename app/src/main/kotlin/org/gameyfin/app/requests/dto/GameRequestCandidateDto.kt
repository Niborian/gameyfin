package org.gameyfin.app.requests.dto

import java.time.Instant

data class RecordGameRequestCandidateDto(val providerLabel: String, val displayName: String, val externalReference: String, val notes: String? = null)
data class ApproveGameRequestCandidateDto(val reason: String? = null)
data class GameRequestCandidateDto(val id: Long, val providerLabel: String, val displayName: String, val externalReference: String, val notes: String?, val recordedAt: Instant, val recordedBy: String, val selected: Boolean)
data class GameRequestCandidateApprovalDto(val id: Long, val approvedAt: Instant, val approvedBy: String, val reason: String?)
