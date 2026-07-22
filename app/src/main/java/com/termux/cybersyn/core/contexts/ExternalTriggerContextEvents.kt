package com.termux.cybersyn.core.contexts

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

object ExternalTriggerContextEvents {
    const val EVENT_EXTERNAL_TRIGGER = "external_trigger"
    const val DEFAULT_TRIGGER = "quick_tap"
    internal const val PENDING_PULSE_REPLAY_MS = 30_000L

    private val triggerEvents = MutableSharedFlow<ContextEvent>(
        extraBufferCapacity = 16,
    )
    private val pendingTriggerPulse = AtomicReference<PendingExternalTriggerPulse?>(null)

    val events: Flow<ContextEvent> = events { System.currentTimeMillis() }

    fun publishTrigger(
        triggerName: String,
        sourcePackage: String?,
        source: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val event = buildEvent(triggerName, sourcePackage, source, nowMs)
        pendingTriggerPulse.set(PendingExternalTriggerPulse(event, nowMs))
        return triggerEvents.tryEmit(event)
    }

    fun buildEvent(
        triggerName: String,
        sourcePackage: String?,
        source: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): ContextEvent {
        val normalizedTrigger = triggerName.trim().ifBlank { DEFAULT_TRIGGER }
        return ContextEvent(
            type = "event",
            matched = true,
            metadata = buildMap {
                put("event", EVENT_EXTERNAL_TRIGGER)
                put("trigger", normalizedTrigger)
                put("name", normalizedTrigger)
                put("observedAtEpochMs", nowMs.toString())
                sourcePackage?.takeIf { it.isNotBlank() }?.let { put("package", it) }
                source?.takeIf { it.isNotBlank() }?.let { put("source", it) }
            },
        )
    }

    internal fun events(nowMs: () -> Long): Flow<ContextEvent> = flow {
        pendingTriggerPulse.get()
            ?.takeIf { nowMs() - it.observedAtMs <= PENDING_PULSE_REPLAY_MS }
            ?.let { emit(it.event) }
        emitAll(triggerEvents.asSharedFlow())
    }

    internal fun clearPendingForTests() {
        pendingTriggerPulse.set(null)
    }
}

private data class PendingExternalTriggerPulse(
    val event: ContextEvent,
    val observedAtMs: Long,
)
