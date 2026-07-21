package com.termux.cybersyn.automation.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.termux.cybersyn.automation.scheduler.TimeEventScheduler
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
                runCatching { scheduler.scheduleNextMinute() }
                    .onFailure { AppLogger.error(TAG, "Could not re-arm the next time tick", it) }
                runCatching {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, AutomationService::class.java)
                            .setAction(AutomationService.ACTION_TIME_TICK_TRIGGER),
                    )
                }.onSuccess {
                    AppLogger.debug(TAG, "Delivered alarm-backed time tick to the engine")
                }.onFailure { error ->
                    AppLogger.error(TAG, "Could not deliver alarm-backed time tick", error)
                    runCatching { scheduler.scheduleRecovery() }
                        .onFailure { AppLogger.error(TAG, "Could not schedule time-tick recovery", it) }
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
