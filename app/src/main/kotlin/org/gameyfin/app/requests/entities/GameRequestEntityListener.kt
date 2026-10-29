package org.gameyfin.app.requests.entities

import jakarta.persistence.PostPersist
import jakarta.persistence.PostRemove
import jakarta.persistence.PostUpdate
import org.gameyfin.app.requests.GameRequestService
import org.gameyfin.app.requests.dto.GameRequestEvent
import org.gameyfin.app.requests.extensions.toDto
import org.gameyfin.app.requests.extensions.avatarUserIds
import org.gameyfin.app.users.avatar.NativeAvatarState
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import io.github.oshai.kotlinlogging.KotlinLogging

@Component
class GameRequestEntityListener(private val nativeAvatars: NativeAvatarState? = null) {
    @PostPersist
    fun created(gameRequest: GameRequest) {
        publish { GameRequestService.emit(GameRequestEvent.Created(gameRequest.toDto(avatars(gameRequest)))) }
    }

    @PostUpdate
    fun updated(gameRequest: GameRequest) {
        publish { GameRequestService.emit(GameRequestEvent.Updated(gameRequest.toDto(avatars(gameRequest)))) }
    }

    @PostRemove
    fun deleted(gameRequest: GameRequest) {
        publish { GameRequestService.emit(GameRequestEvent.Deleted(gameRequest.id!!)) }
    }

    private fun avatars(request: GameRequest) = nativeAvatars?.metadata(listOf(request).avatarUserIds()).orEmpty()

    private fun publish(notification: () -> Unit) {
        // Overlay reads own a different connection. Never perform them inside a
        // Hibernate flush while related native rows may still be uncommitted.
        if (nativeAvatars != null && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    try { notification() } catch (_: Exception) {
                        // A notification failure must not turn committed request work
                        // into an apparent failed write. No payload/exception leakage.
                        log.warn { "Committed request notification could not be published" }
                    }
                }
            })
        } else notification() // Preserve existing overlay-off/direct-component behavior.
    }

    companion object { private val log = KotlinLogging.logger {} }
}
