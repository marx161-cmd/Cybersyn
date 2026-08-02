package com.termux.cybersyn.core.external

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import com.termux.cybersyn.core.logging.AppLogger
import com.termux.cybersyn.core.mqtt.MqttBridge

class ForegroundAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        val isWindow = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (isWindow && pkg != null) {
            AppLogger.info(TAG, "window: $pkg (was $lastPackage)")
        }

        if (!isWindow || pkg == null) return
        if (pkg == lastPackage) return
        val wasBrowser = isBrowser(lastPackage)
        val isBrowser = isBrowser(pkg)
        lastPackage = pkg

        if (!wasBrowser && isBrowser) {
            publishBrowserMode(true)
        } else if (wasBrowser && !isBrowser) {
            publishBrowserMode(false)
        }
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
        setServiceInfo(info)
        AppLogger.info(TAG, "Foreground accessibility service connected")
    }

    override fun onDestroy() {
        if (isBrowser(lastPackage)) {
            publishBrowserMode(false)
        }
        super.onDestroy()
    }

    private fun publishBrowserMode(on: Boolean) {
        AppLogger.info(TAG, "browser_mode -> $on (pkg=${lastPackage})")
        Thread {
            MqttBridge.publish(
                this,
                "volume_daemon/browser_mode",
                if (on) "ON" else "OFF",
            )
        }.also { it.name = "browser-mode-mqtt" }.start()
    }

    companion object {
        private const val TAG = "ForegroundAccessibility"

        private var lastPackage: String? = null

        private val BROWSER_PACKAGES = setOf(
            "org.cromite.cromite",
            "org.mozilla.firefox",
            "com.termux.diana.root.noir",
        )

        private fun isBrowser(pkg: String?): Boolean = pkg != null && pkg in BROWSER_PACKAGES
    }
}
