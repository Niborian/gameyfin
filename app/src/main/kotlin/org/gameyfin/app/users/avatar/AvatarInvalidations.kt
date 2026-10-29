package org.gameyfin.app.users.avatar

import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** Ephemeral UI invalidations, NOT receipts or a durable/replayable event log.
 * No content/identity proof/grant payload. Slow clients may drop notifications;
 * focus/reconnect readback reconciles actual state. Never hold DB locks here. */
object AvatarInvalidations {
    private class Delivery {
        val sink = Sinks.many().multicast().directBestEffort<Long>()
        val stop = Sinks.empty<Void>()
        val scheduler: Scheduler = Schedulers.newBoundedElastic(2, 32, "avatar-ui", 60, true)
    }
    @Volatile private var delivery: Delivery? = null
    private var stopped = true
    @Synchronized private fun current(): Delivery? =
        if (stopped) null else delivery ?: Delivery().also { delivery = it }
    /** Trusted application owner only. A late subscriber cannot reopen shutdown. */
    @Synchronized internal fun startOwnedDelivery() { check(delivery == null); stopped = false }
    fun updates(): Flux<Long> = Flux.defer {
        val owned = current() ?: return@defer Flux.empty<Long>()
        owned.sink.asFlux().onBackpressureLatest().takeUntilOther(owned.stop.asMono()).publishOn(owned.scheduler, 1)
    }
    fun changed(userId: Long) {
        try { delivery?.sink?.tryEmitNext(userId) } catch (_: Exception) {
            // UI delivery cannot turn an already committed write into a failed one.
        }
    }
    fun close() {
        val old = synchronized(this) { stopped = true; delivery.also { delivery = null } } ?: return
        old.stop.tryEmitEmpty()
        old.scheduler.dispose()
    }
}

@Component
@ConditionalOnProperty(prefix = "gameyfin.avatar.overlay", name = ["enabled"], havingValue = "true")
class AvatarInvalidationLifecycle : DisposableBean {
    init { AvatarInvalidations.startOwnedDelivery() }
    override fun destroy() = AvatarInvalidations.close()
}
