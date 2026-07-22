package com.termux.cybersyn.core.external

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.termux.cybersyn.core.contexts.ExternalTriggerContextEvents
import com.termux.cybersyn.core.engine.AutomationService
import com.termux.cybersyn.core.logging.AppLogger

class ExternalTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ExternalTriggerContract.ACCEPTED_ACTIONS) return
        val triggerName = intent.getStringExtra(ExternalTriggerContract.EXTRA_TRIGGER_NAME)
            ?: when (intent.action) {
                ExternalTriggerContract.ACTION_QUICK_TAP -> ExternalTriggerContextEvents.DEFAULT_TRIGGER
                else -> ExternalTriggerContextEvents.DEFAULT_TRIGGER
            }
        val source = intent.getStringExtra(ExternalTriggerContract.EXTRA_SOURCE)
        val emitted = ExternalTriggerContextEvents.publishTrigger(
            triggerName = triggerName,
            sourcePackage = intent.`package` ?: intent.component?.packageName ?: context.packageName,
            source = source,
        )
        AppLogger.debug(TAG, "External trigger '$triggerName' accepted emitted=$emitted")
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AutomationService::class.java)
                    .putExtra(AutomationService.EXTRA_STARTED_FROM_VISIBLE_UI, false),
            )
        }.onFailure { error ->
            AppLogger.error(TAG, "Failed to start automation service after external trigger", error)
        }
    }

    companion object {
        private const val TAG = "ExternalTriggerReceiver"
    }
}

object ExternalTriggerContract {
    const val ACTION_EXTERNAL_TRIGGER = "com.termux.cybersyn.action.EXTERNAL_TRIGGER"
    const val ACTION_QUICK_TAP = "com.termux.cybersyn.action.QUICK_TAP"
    const val EXTRA_TRIGGER_NAME = "com.termux.cybersyn.extra.TRIGGER_NAME"
    const val EXTRA_SOURCE = "com.termux.cybersyn.extra.SOURCE"
    const val EXTRA_TRIGGER_TIME_MS = "com.termux.cybersyn.extra.TRIGGER_TIME_MS"
    const val SOURCE_PIXEL_QUICK_TAP = "pixel_quick_tap"

    val ACCEPTED_ACTIONS = setOf(ACTION_EXTERNAL_TRIGGER, ACTION_QUICK_TAP)
}
