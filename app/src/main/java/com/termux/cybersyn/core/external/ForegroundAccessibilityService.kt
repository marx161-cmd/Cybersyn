package com.termux.cybersyn.core.external

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.view.accessibility.AccessibilityEvent
import com.termux.cybersyn.core.logging.AppLogger
import com.termux.cybersyn.core.evdev.KeyHijackController

/**
 * Tracks the foreground app so [KeyHijackController] knows when a browser is in front
 * (volume keys become page prev/next). Event-driven, no polling.
 */
class ForegroundAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        // TYPE_WINDOW_STATE_CHANGED also fires for the notification shade, the volume
        // dialog, permission dialogs and IME popups -- all with non-browser packages.
        // Treating those as "the app switched away" turned browser mode off whenever the
        // shade was pulled over Cromite, and it stayed off until the next real switch.
        // Only a window that is actually an Activity changes the foreground app.
        if (pkg in OVERLAY_PACKAGES || !event.isActivityWindow()) return

        if (pkg == lastPackage) return
        val wasBrowser = isBrowser(lastPackage)
        val isBrowser = isBrowser(pkg)
        lastPackage = pkg
        if (wasBrowser != isBrowser) setBrowserMode(isBrowser)
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
        if (isBrowser(lastPackage)) setBrowserMode(false)
        lastPackage = null
        super.onDestroy()
    }

    /**
     * Direct, in-process. The old `volume_daemon/browser_mode` MQTT publish went away
     * with the python daemon that read it: detecting the browser inside Cybersyn and
     * shipping it to a broker on another machine so Cybersyn could read it back was pure
     * round trip, and it cost a thread plus a publish on every app switch.
     */
    private fun setBrowserMode(on: Boolean) {
        AppLogger.info(TAG, "browser_mode -> $on (pkg=$lastPackage)")
        KeyHijackController.browserForeground = on
    }

    /**
     * True if this event came from a real Activity window. Dialogs, toasts and system
     * overlays report a className that doesn't resolve to an Activity, which is the
     * standard way to tell a foreground-app change from a transient window.
     */
    private fun AccessibilityEvent.isActivityWindow(): Boolean {
        val cls = className?.toString() ?: return false
        val pkg = packageName?.toString() ?: return false
        // Cheap structural check first — resolving every window's class against the
        // PackageManager on the accessibility callback thread is work worth skipping.
        if (cls.endsWith("PopupWindow") || cls.endsWith("Dialog") || cls.endsWith("Toast")) return false
        return runCatching { packageManager.getActivityInfo(ComponentName(pkg, cls), 0) }.isSuccess
    }

    companion object {
        private const val TAG = "ForegroundAccessibility"

        private var lastPackage: String? = null

        private val BROWSER_PACKAGES = setOf(
            "org.cromite.cromite",
            "org.mozilla.firefox",
            "com.termux.diana.root.noir",
        )

        /** Windows that never represent a foreground-app change. */
        private val OVERLAY_PACKAGES = setOf(
            "com.android.systemui",
            "android",
        )

        private fun isBrowser(pkg: String?): Boolean = pkg != null && pkg in BROWSER_PACKAGES
    }
}
