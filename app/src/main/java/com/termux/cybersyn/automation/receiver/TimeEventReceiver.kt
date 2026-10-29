package com.termux.cybersyn.automation.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.termux.cybersyn.automation.scheduler.TimeEventScheduler
import com.termux.cybersyn.automation.scheduler.TimeTickRecoveryBackoff
import com.termux.cybersyn.core.engine.AutomationService
import com.termux.cybersyn.core.logging.AppLogger
import com.termux.cybersyn.core.scheduling.ExactAlarmSupport

/**
 * Receives app-owned time ticks and exact-alarm permission changes.
 */
class TimeEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        when (intent.action) {
            TimeEventScheduler.ACTION_TIME_TICK,
            Intent.ACTION_TIME_TICK -> {
                val scheduler = TimeEventScheduler(context)
                val backoff = TimeTickRecoveryBackoff(context)
                val rearmed = runCatching { scheduler.scheduleNextMinute() }
                    .onFailure { AppLogger.error(TAG, "Could not re-arm the next time tick", it) }
                    .isSuccess
                runCatching {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, AutomationService::class.java)
                            .setAction(AutomationService.ACTION_TIME_TICK_TRIGGER),
                    )
                }.onSuccess {
                    backoff.reset()
                    AppLogger.debug(TAG, "Delivered alarm-backed time tick to the engine")
                }.onFailure { error ->
                    // Only skip the recovery when the ordinary tick was re-armed. If that failed
                    // too, the recovery alarm is the one thing left that can restart the chain.
                    if (rearmed && isBackgroundStartRefusal(error)) {
                        // Android refused a foreground-service start from the background and will
                        // refuse the retry for the same reason. Booking the 5 s recovery here woke
                        // the phone every five seconds for as long as that lasted, burning the
                        // allow-while-idle quota the minute tick depends on. The next tick is the
                        // recovery. (Ported from upstream OpenTasker 67988068.)
                        AppLogger.warn(TAG, "Android refused a background service start; waiting for the next tick", error)
                    } else {
                        AppLogger.error(TAG, "Could not deliver alarm-backed time tick", error)
                        // Back off (5 s, 30 s, 5 min), then stop; EngineWatchdogWorker keeps
                        // re-arming time delivery on its own schedule.
                        val delay = backoff.claimRecoveryDelay()
                        if (delay == null) {
                            AppLogger.warn(TAG, "Stopped booking time-tick recovery after repeated failures; the engine watchdog re-arms it")
                        } else {
                            runCatching { scheduler.scheduleRecovery(delayMillis = delay) }
                                .onFailure { AppLogger.error(TAG, "Could not schedule time-tick recovery", it) }
                        }
                    }
                }
            }
            ExactAlarmSupport.PERMISSION_STATE_CHANGED_ACTION -> {
                try {
                    AppLogger.debug(TAG, "Exact alarm permission changed")
                    TimeEventScheduler(context).scheduleNextMinute()
                } catch (e: Exception) {
                    AppLogger.error(TAG, "Error rescheduling time tick after exact alarm permission change", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "TimeEventReceiver"
    }
}

/**
 * Whether the platform refused a foreground-service start because the app is in the background.
 *
 * Matched by class name rather than `is`, so nothing has to load a class that does not exist below
 * API 31. Below API 31 the refusal is a plain `IllegalStateException`, so its message is checked
 * too; a false positive only skips one recovery, and the minute tick is already re-armed.
 * (Ported from upstream OpenTasker 67988068.)
 */
internal fun isBackgroundStartRefusal(error: Throwable): Boolean =
    namesBackgroundStartRefusal(causeChainClassNames(error)) ||
        generateSequence(error, Throwable::cause)
            .take(MAX_CAUSE_DEPTH)
            .any { readsAsBackgroundStartRefusal(it.message) }

internal fun readsAsBackgroundStartRefusal(message: String?): Boolean {
    val text = message?.lowercase() ?: return false
    return "not allowed to start service" in text ||
        ("background" in text && "start" in text && "service" in text)
}

internal fun causeChainClassNames(error: Throwable): List<String> =
    generateSequence(error, Throwable::cause)
        .take(MAX_CAUSE_DEPTH)
        .map { it.javaClass.name }
        .toList()

/** Split from the walk because the platform class is stubbed on the JVM test classpath. */
internal fun namesBackgroundStartRefusal(classNames: List<String>): Boolean =
    classNames.any { it == FOREGROUND_START_REFUSAL }

private const val FOREGROUND_START_REFUSAL = "android.app.ForegroundServiceStartNotAllowedException"

/** A cause chain is normally two or three deep; this only stops a cyclic one from hanging. */
private const val MAX_CAUSE_DEPTH = 8
