package com.termux.cybersyn.core.engine

import android.content.Context
import com.termux.cybersyn.core.contexts.ContextMatchEvaluator
import com.termux.cybersyn.core.contexts.ContextSourceRegistry
import com.termux.cybersyn.core.contexts.SubscriptionReadyContextSource
import com.termux.cybersyn.core.location.LocationDwellStateStore
import com.termux.cybersyn.core.logging.AppLogger
import com.termux.cybersyn.core.model.ContextSpec
import com.termux.cybersyn.core.model.ContextType
import com.termux.cybersyn.core.model.Profile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.scan

/**
 * Watches a Profile's contexts and emits level-state transitions or event pulses.
 * Level contexts activate/deactivate when the aggregate match changes; event
 * contexts activate on each matching pulse.
 * 
 * Includes performance monitoring to detect slow matchers.
 */
class ProfileMatcher(
    private val app: Context,
    private val profile: Profile,
) {
    private val tag = "ProfileMatcher[${profile.name}]"
    private val performanceThresholdMs = 1000L // Warn if evaluation takes > 1 second
    private val locationDwellStateStore = LocationDwellStateStore(app)
    private val monitorSubscriptionsReady = CompletableDeferred<Unit>()
    private val readyPulseContextIndexes = mutableSetOf<Int>()

    suspend fun awaitMonitorSubscriptions() {
        monitorSubscriptionsReady.await()
    }
    
    fun stateChanges(): Flow<ProfileStateChange> {
        if (profile.contexts.isEmpty()) {
            monitorSubscriptionsReady.complete(Unit)
            return emptyFlow()
        }

        val pulseContextCount = profile.contexts.count { it.type.isPulseContext() }
        val hasPulseContexts = pulseContextCount > 0
        if (!hasPulseContexts) monitorSubscriptionsReady.complete(Unit)
        val flows = profile.contexts.mapIndexed { index, spec ->
            val sourceType = ContextMatchEvaluator.sourceKey(spec.type)
            val source = sourceType?.let(ContextSourceRegistry::get)
            if (source != null) {
                val isPulseContext = spec.type.isPulseContext()
                val sourceEvents = if (isPulseContext && source is SubscriptionReadyContextSource) {
                    source.events(app) { markPulseContextSubscribed(index, pulseContextCount) }
                } else {
                    if (isPulseContext) markPulseContextSubscribed(index, pulseContextCount)
                    source.events(app)
                }
                sourceEvents.scan(ContextMatchUpdate.initial(isPulseContext)) { previous, event ->
                    if (spec.type == ContextType.PLUGIN &&
                        !ContextMatchEvaluator.pluginEventAddressesSpec(spec, event)
                    ) {
                        // The shared plugin source multiplexes every subscription's poll results;
                        // a result for a different plugin/bundle must not flap this level context.
                        return@scan previous
                    }
                    val preparedEvent = if (spec.type == ContextType.LOCATION) {
                        locationDwellStateStore.enrich(profile.id, index, spec, event)
                    } else {
                        event
                    }
                    val matched = ContextMatchEvaluator.matches(spec, preparedEvent)
                    val effectiveMatched = if (spec.invert) !matched else matched
                    ContextMatchUpdate(
                        matched = effectiveMatched,
                        pulseContext = isPulseContext,
                        pulseSequence = if (isPulseContext) previous.pulseSequence + 1 else 0,
                        // Carried through so the fired task can read the triggering
                        // event's own data (e.g. an MQTT payload) as a variable --
                        // previously this was discarded right after matching, which
                        // silently broke the documented "{{ event.payload }}" syntax
                        // for every event-triggered task in the app.
                        eventMetadata = if (isPulseContext && effectiveMatched) preparedEvent.metadata else emptyMap(),
                    )
                }
            } else {
                AppLogger.warn(tag, "No context source registered for ${spec.type}; treating as non-matching")
                if (spec.type.isPulseContext()) markPulseContextSubscribed(index, pulseContextCount)
                flowOf(ContextMatchUpdate.initial(spec.type.isPulseContext()))
            }
        }

        return if (flows.isEmpty()) {
            emptyFlow()
        } else {
            combine(flows) { allMatches ->
                evaluateSnapshot(allMatches)
            }.let { snapshots ->
                profileStateChangesFromSnapshots(snapshots, hasPulseContexts) { change ->
                    val startTime = System.currentTimeMillis()
                    when (change) {
                        is ProfileStateChange.Activated -> {
                            val reason = if (hasPulseContexts) "Profile activated by event pulse" else "Profile activated"
                            AppLogger.info(tag, reason)
                        }
                        ProfileStateChange.Deactivated -> AppLogger.info(tag, "Profile deactivated")
                    }
                    val duration = System.currentTimeMillis() - startTime
                    AppLogger.debug(tag, "State transition evaluated in ${duration}ms")
                }
            }
        }
    }

    private fun evaluateSnapshot(
        contextMatches: Array<ContextMatchUpdate>,
    ): ProfileMatchSnapshot {
        val startTime = System.currentTimeMillis()
        val allMatched = evaluateWithOrGroups(contextMatches, profile.contexts)
        val pulseSequence = contextMatches
            .filter { it.pulseContext }
            .sumOf { it.pulseSequence }
        // Whichever pulse context actually carried data on this snapshot -- for the
        // common case of a single event context this is unambiguous; for a profile
        // combining multiple pulse contexts, the first non-empty one wins (good
        // enough for "which event fired me", not meant to merge multiple payloads).
        val eventMetadata = contextMatches
            .firstOrNull { it.pulseContext && it.eventMetadata.isNotEmpty() }
            ?.eventMetadata
            ?: emptyMap()
        val duration = System.currentTimeMillis() - startTime

        if (duration > performanceThresholdMs) {
            AppLogger.warn(tag, "Slow profile evaluation: ${duration}ms (threshold: ${performanceThresholdMs}ms)")
        }

        return ProfileMatchSnapshot(
            allMatched = allMatched,
            pulseSequence = pulseSequence,
            eventMetadata = eventMetadata,
        )
    }

    private fun markPulseContextSubscribed(index: Int, expectedCount: Int) {
        synchronized(readyPulseContextIndexes) {
            if (readyPulseContextIndexes.add(index) && readyPulseContextIndexes.size >= expectedCount) {
                monitorSubscriptionsReady.complete(Unit)
            }
        }
    }

}

internal data class ContextMatchUpdate(
    val matched: Boolean,
    val pulseContext: Boolean,
    val pulseSequence: Long,
    val eventMetadata: Map<String, String> = emptyMap(),
) {
    companion object {
        fun initial(pulseContext: Boolean): ContextMatchUpdate =
            ContextMatchUpdate(matched = false, pulseContext = pulseContext, pulseSequence = 0)
    }
}

internal data class ProfileMatchSnapshot(
    val allMatched: Boolean,
    val pulseSequence: Long,
    val eventMetadata: Map<String, String> = emptyMap(),
)

private data class PulseAccumulator(
    val lastPulseSequence: Long,
    val change: ProfileStateChange?,
)

internal fun profileStateChangesFromSnapshots(
    snapshots: Flow<ProfileMatchSnapshot>,
    hasPulseContexts: Boolean,
    onChange: (ProfileStateChange) -> Unit = {},
): Flow<ProfileStateChange> =
    if (hasPulseContexts) {
        snapshots.scan(PulseAccumulator(lastPulseSequence = 0, change = null)) { previous, snapshot ->
            val pulseChanged = snapshot.pulseSequence != previous.lastPulseSequence
            val change = if (pulseChanged && snapshot.pulseSequence > 0 && snapshot.allMatched) {
                ProfileStateChange.Activated(snapshot.eventMetadata)
            } else {
                null
            }
            PulseAccumulator(lastPulseSequence = snapshot.pulseSequence, change = change)
        }.mapNotNull { accumulator ->
            accumulator.change?.also(onChange)
        }
    } else {
        snapshots.map { it.allMatched }
            .distinctUntilChanged()
            .scan(Pair(false, false)) { (_, prev), now -> Pair(prev, now) }
            .mapNotNull { (prev, now) ->
                val change = when {
                    // Level contexts (not pulse contexts) have no discrete triggering
                    // event of their own to carry -- default empty metadata.
                    !prev && now -> ProfileStateChange.Activated()
                    prev && !now -> ProfileStateChange.Deactivated
                    else -> null
                }
                change?.also(onChange)
            }
    }

internal fun evaluateWithOrGroups(
    contextMatches: Array<ContextMatchUpdate>,
    specs: List<ContextSpec>,
): Boolean {
    if (contextMatches.isEmpty()) return false
    val andTerms = mutableListOf<Boolean>()
    val orGroups = mutableMapOf<String, Boolean>()

    for (i in contextMatches.indices) {
        val group = specs.getOrNull(i)?.orGroup
        if (group != null) {
            orGroups[group] = orGroups.getOrDefault(group, false) || contextMatches[i].matched
        } else {
            andTerms.add(contextMatches[i].matched)
        }
    }
    return andTerms.all { it } && orGroups.values.all { it }
}

sealed class ProfileStateChange {
    data class Activated(val eventMetadata: Map<String, String> = emptyMap()) : ProfileStateChange()
    data object Deactivated : ProfileStateChange()
}

internal fun ContextType.isPulseContext(): Boolean = this == ContextType.EVENT || this == ContextType.LOGCAT
