package org.gameyfin.app.requests.status

enum class GameRequestStatus {
    PENDING,
    APPROVED,
    SEARCHING,
    CANDIDATES_FOUND,
    AWAITING_APPROVAL,
    QUEUED,
    DOWNLOADING,
    IMPORTED_FOR_REVIEW,
    FAILED,
    CANCELLED,
    REJECTED,
    FULFILLED
}
