package com.termux.cybersyn.core.external

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.termux.cybersyn.core.contexts.ExternalTriggerContextEvents
import com.termux.cybersyn.core.engine.AutomationService
import com.termux.cybersyn.core.logging.AppLogger

class QuickTapRelayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        relayQuickTap()
        finish()
        overridePendingTransition(0, 0)
    }

    private fun relayQuickTap() {
        val now = System.currentTimeMillis()
        sendBroadcast(
            Intent(ExternalTriggerContract.ACTION_EXTERNAL_TRIGGER).apply {
                setPackage(packageName)
                putExtra(ExternalTriggerContract.EXTRA_TRIGGER_NAME, ExternalTriggerContextEvents.DEFAULT_TRIGGER)
                putExtra(ExternalTriggerContract.EXTRA_SOURCE, ExternalTriggerContract.SOURCE_PIXEL_QUICK_TAP)
                putExtra(ExternalTriggerContract.EXTRA_TRIGGER_TIME_MS, now)
            },
        )
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, AutomationService::class.java)
                    .putExtra(AutomationService.EXTRA_STARTED_FROM_VISIBLE_UI, false),
            )
        }.onFailure { error ->
            AppLogger.error(TAG, "Failed to start automation service after Quick Tap", error)
        }
        AppLogger.debug(TAG, "Relayed Pixel Quick Tap")
    }

    companion object {
        private const val TAG = "QuickTapRelay"
    }
}
