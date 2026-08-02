package com.termux.cybersyn.core.contexts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Calendar
import java.util.TimeZone

/**
 * A once-a-minute EVENT pulse, for profiles that must run *periodically* rather than
 * while some condition holds.
 *
 * A TIME context cannot express this. TIME is a level context ([ProfileMatcher] only
 * treats EVENT and LOGCAT as pulses), so a profile with range `00:00-23:59` matches
 * continuously and activates exactly once, on the false->true edge. Every watchdog in
 * this app was written as such a TIME profile and appeared to run every minute only
 * because the service used to call `reloadProfiles()` on every time tick: rebuilding the
 * matchers reset their state, so each rebuild produced a fresh activation edge. That
 * rebuild also respawned the logcat reader and the MQTT subscriber 1440x/day and leaked
 * root children, so it was removed -- and with it, silently, the watchdog cadence.
 *
 * Making TIME itself a pulse would be wrong: a `09:00-17:00` profile means "when the
 * window is entered", and pulsing it would fire the task every minute all day. So
 * periodicity gets its own event instead, and profiles opt in explicitly with
 * `{"type":"EVENT","config":{"event":"time_tick"}}`.
 *
 * Deliberately not replayed to late subscribers (unlike boot/external triggers): a stale
 * tick has no value, the next one is at most a minute away.
 */
object TimeTickContextEvents {
    const val EVENT_TIME_TICK = "time_tick"

    private val tickEvents = MutableSharedFlow<ContextEvent>(extraBufferCapacity = 4)

    val events: Flow<ContextEvent> = tickEvents.asSharedFlow()

    fun publish(nowMs: Long = System.currentTimeMillis()): Boolean =
        tickEvents.tryEmit(buildEvent(nowMs))

    fun buildEvent(
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): ContextEvent {
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = nowMs }
        return ContextEvent(
            type = "event",
            matched = true,
            metadata = mapOf(
                "event" to EVENT_TIME_TICK,
                // Same shape TIME contexts expose, so a tick profile can still filter on
                // clock time or weekday without needing a second context.
                "time" to "%02d:%02d".format(
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                ),
                "day" to DaySchedule.tokenFor(calendar),
                "observedAtEpochMs" to nowMs.toString(),
            ),
        )
    }
}
