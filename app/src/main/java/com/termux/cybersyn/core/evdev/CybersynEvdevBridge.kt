package com.termux.cybersyn.core.evdev

import com.termux.cybersyn.core.evdev.EvdevDeviceInfo
import com.termux.cybersyn.core.evdev.GrabTargetKeyCode
import com.termux.cybersyn.core.evdev.GrabbedDeviceHandle
import com.termux.cybersyn.core.logging.AppLogger
import java.io.BufferedReader

class CybersynEvdevBridge {

    @Suppress("KotlinJniMissingFunction")
    external fun setGrabTargetsNative(
        devices: Array<GrabTargetKeyCode>,
    ): Array<GrabbedDeviceHandle>

    @Suppress("KotlinJniMissingFunction")
    external fun writeEvdevEventNative(deviceId: Int, type: Int, code: Int, value: Int): Boolean

    @Suppress("KotlinJniMissingFunction")
    external fun writeEvdevEventKeyCodeNative(deviceId: Int, keyCode: Int, value: Int): Boolean

    @Suppress("KotlinJniMissingFunction")
    external fun getEvdevDevicesNative(): Array<EvdevDeviceInfo>

    @Suppress("KotlinJniMissingFunction")
    external fun initEvdevManager()

    @Suppress("KotlinJniMissingFunction")
    external fun destroyEvdevManager()

    @Suppress("KotlinJniMissingFunction")
    external fun setLogLevelNative(level: Int)

    /** Called from Rust via JNI when an evdev event occurs. */
    @Suppress("unused")
    fun onEvdevEvent(
        deviceId: Int, timeSec: Long, timeUsec: Long,
        type: Int, code: Int, value: Int, androidCode: Int,
    ): Boolean = evdevEventCallback?.invoke(deviceId, timeSec, timeUsec, type, code, value, androidCode) ?: false

    /** Called from Rust via JNI when grabbed devices change (hotplug). */
    @Suppress("unused")
    fun onGrabbedDevicesChanged(devices: Array<GrabbedDeviceHandle>) {
        evdevDevicesChangedCallback?.invoke(devices)
    }

    /** Called from Rust via JNI when available evdev devices change. */
    @Suppress("unused")
    fun onEvdevDevicesChanged(devices: Array<EvdevDeviceInfo>) {
        evdevDevicesCallback?.invoke(devices)
    }

    /** Called from Rust via JNI on emergency (power held 10+ seconds). */
    @Suppress("unused")
    fun onEmergencyKillSystemBridge() {
        emergencyKillCallback?.invoke()
    }

    /** Called from Rust via JNI for log messages. */
    @Suppress("unused")
    fun onLogMessage(level: Int, message: String) {
        AppLogger.debug(TAG, "[evdev] $message")
    }

    fun setEventCallback(callback: (Int, Long, Long, Int, Int, Int, Int) -> Boolean) {
        evdevEventCallback = callback
    }

    fun setDevicesChangedCallback(callback: (Array<GrabbedDeviceHandle>) -> Unit) {
        evdevDevicesChangedCallback = callback
    }

    fun setEvdevDevicesCallback(callback: (Array<EvdevDeviceInfo>) -> Unit) {
        evdevDevicesCallback = callback
    }

    fun setEmergencyKillCallback(callback: () -> Unit) {
        emergencyKillCallback = callback
    }

    fun executeCommand(command: String, timeoutMillis: Long = 30_000): ShellResult {
        val process = ProcessBuilder()
            .command("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        var stdout = ""
        val worker = Thread {
            val stdoutReader = BufferedReader(process.inputStream.reader())
            try {
                stdout = stdoutReader.readText()
                process.waitFor()
            } catch (_: InterruptedException) {
            } finally {
                stdoutReader.close()
            }
        }
        worker.start()
        worker.join(timeoutMillis)
        if (worker.isAlive) {
            worker.interrupt()
            process.destroy()
            return ShellResult(stdout, -1)
        }
        return ShellResult(stdout, process.exitValue())
    }

    private var evdevEventCallback: ((Int, Long, Long, Int, Int, Int, Int) -> Boolean)? = null
    private var evdevDevicesChangedCallback: ((Array<GrabbedDeviceHandle>) -> Unit)? = null
    private var evdevDevicesCallback: ((Array<EvdevDeviceInfo>) -> Unit)? = null
    private var emergencyKillCallback: (() -> Unit)? = null

    companion object {
        private const val TAG = "CybersynEvdev"
        // Library loaded by EvdevRootHelper (root process), not here.
    }
}
