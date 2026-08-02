package com.termux.cybersyn.core.evdev

import android.content.Context
import com.termux.cybersyn.core.logging.AppLogger
import com.termux.cybersyn.core.mqtt.MqttBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the lifecycle of the EvdevRootHelper root process and routes grabbed key events.
 *
 * Uses the same procLock + running-flag pattern from MqttBridge.rawSubscribe and
 * LogcatContextSource. No separate daemon — one su -c process, stdin/stdout protocol.
 *
 * Routing rules (carried over from volume_daemon.py):
 *   - power+vol_down held → screenshot (KEYCODE_SYSRQ via shell)
 *   - IME shown + vol keys → MQTT publish android/clutch or android/click
 *   - browser foreground + no keyboard + vol keys → media prev/next
 *   - everything else → EMIT to root helper (pass-through to Android)
 */
object KeyHijackController {
    private const val TAG = "KeyHijackController"
    private val KEY_VOLUME_DOWN = 25
    private val KEY_VOLUME_UP = 24
    private val KEY_POWER = 26

    private val procLock = Any()
    private var helperProcess: Process? = null
    private var helperWriter: OutputStreamWriter? = null
    private var helperReaderJob: Job? = null
    private val running = AtomicBoolean(false)
    private val keyState = mutableMapOf<Int, Boolean>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Set by caller (AutomationService)
    var appContext: Context? = null
    var imeShown: Boolean = false
    var browserForeground: Boolean = false
    var deviceId: Int = 0

    fun start(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        synchronized(procLock) {
            if (running.get()) {
                AppLogger.warn(TAG, "already running")
                return
            }
            running.set(true)

            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val apkPath = context.packageCodePath
            val cmd = arrayOf(
                "su", "-c",
                "CLASSPATH=$apkPath /system/bin/app_process / com.termux.cybersyn.core.evdev.EvdevRootHelper $nativeLibDir"
            )

            val proc = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .start()

            val shouldKeep = synchronized(procLock) {
                if (running.get()) {
                    helperProcess = proc
                    helperWriter = OutputStreamWriter(proc.outputStream)
                    true
                } else {
                    false
                }
            }
            if (!shouldKeep) {
                proc.destroyForcibly()
                return
            }

            AppLogger.info(TAG, "root helper started")

            helperReaderJob = scope.launch {
                try {
                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    var line: String?
                    while (running.get()) {
                        line = reader.readLine() ?: break
                        handleLine(line.trim())
                    }
                } catch (_: Exception) {
                } finally {
                    synchronized(procLock) {
                        if (running.get()) {
                            AppLogger.warn(TAG, "root helper died unexpectedly; restart")
                            cleanup()
                            start(context)
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        synchronized(procLock) {
            running.set(false)
            cleanup()
        }
    }

    private fun cleanup() {
        helperReaderJob?.cancel()
        helperReaderJob = null
        try { helperWriter?.close() } catch (_: Exception) {}
        helperWriter = null
        helperProcess?.destroyForcibly()
        helperProcess = null
        keyState.clear()
        deviceId = 0
    }

    /** Set which keys to grab. Must be called after READY is received. */
    fun setGrabTargets(bus: Int, vendor: Int, product: Int, codes: IntArray) {
        val codesStr = codes.joinToString(",")
        send("GRAB $bus $vendor $product $codesStr")
    }

    private fun send(cmd: String) {
        synchronized(procLock) {
            try {
                helperWriter?.apply {
                    write(cmd)
                    write("\n")
                    flush()
                }
            } catch (_: Exception) {}
        }
    }

    private fun handleLine(line: String) {
        val parts = line.split("\\s+".toRegex())
        if (parts.isEmpty()) return

        try {
            when (parts[0]) {
                "READY" -> {
                    val pid = parts[1].toIntOrNull() ?: return
                    AppLogger.info(TAG, "root helper ready (pid=$pid)")
                    setGrabTargets(0x19, 0x1, 0x1, intArrayOf(KEY_VOLUME_DOWN, KEY_VOLUME_UP, KEY_POWER))
                }
                "GRAB_OK" -> {
                    deviceId = parts[2].toIntOrNull() ?: 0
                    AppLogger.info(TAG, "grab ok: ${parts[1]} id=$deviceId")
                }
                "EV_UP" -> {
                    val id = parts[1].toInt()
                    val code = parts[2].toInt()
                    keyState[code] = false
                    // Key up routing: only needed for gyro stop events
                    if (imeShown) {
                        when (code) {
                            KEY_VOLUME_DOWN -> stopGyro()
                            KEY_VOLUME_UP -> stopHold()
                        }
                    }
                }
                "EV_DOWN" -> {
                    val id = parts[1].toInt()
                    val code = parts[2].toInt()
                    keyState[code] = true

                    // Screenshot: power held + vol_down press
                    if (code == KEY_VOLUME_DOWN && keyState[KEY_POWER] == true) {
                        screencap()
                        return
                    }

                    if (imeShown && code in listOf(KEY_VOLUME_DOWN, KEY_VOLUME_UP)) {
                        // Gyro mode
                        when (code) {
                            KEY_VOLUME_DOWN -> startGyro()
                            KEY_VOLUME_UP -> startHold()
                        }
                    } else if (!imeShown && browserForeground && code in listOf(KEY_VOLUME_DOWN, KEY_VOLUME_UP)) {
                        // Browser nav mode
                        emitKeycode(id, if (code == KEY_VOLUME_DOWN) 88 else 87, 1) // MEDIA_PREV=88, MEDIA_NEXT=87
                        emitKeycode(id, if (code == KEY_VOLUME_DOWN) 88 else 87, 0)
                    } else if (!imeShown || code == KEY_POWER) {
                        // Normal pass-through
                        passThrough(id, code)
                    }
                }
                "EV_RAW" -> {} // Raw events streamed; EV_DOWN/EV_UP are derived
                "EV_REPEAT" -> {} // Ignore repeats for now
            }
        } catch (_: Exception) {}
    }

    private fun passThrough(deviceId: Int, code: Int) {
        send("EMIT_KC $deviceId $code 1")
        send("EMIT_KC $deviceId $code 0")
    }

    private fun emitKeycode(deviceId: Int, code: Int, value: Int) {
        send("EMIT_KC $deviceId $code $value")
    }

    private fun startGyro() {
        val ctx = appContext ?: return
        Thread {
            MqttBridge.publish(ctx, "android/clutch", "ON")
        }.also { it.name = "gyro-pub" }.start()
    }

    private fun stopGyro() {
        val ctx = appContext ?: return
        Thread {
            MqttBridge.publish(ctx, "android/clutch", "OFF")
        }.also { it.name = "gyro-pub" }.start()
    }

    private fun startHold() {
        val ctx = appContext ?: return
        Thread {
            MqttBridge.publish(ctx, "android/click", "ON")
        }.also { it.name = "gyro-pub" }.start()
    }

    private fun stopHold() {
        val ctx = appContext ?: return
        Thread {
            MqttBridge.publish(ctx, "android/click", "OFF")
        }.also { it.name = "gyro-pub" }.start()
    }

    private fun screencap() {
        Thread {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("input", "keyevent", "KEYCODE_SYSRQ"))
                proc.waitFor()
                AppLogger.info(TAG, "screencap taken (power+vol_down)")
            } catch (_: Exception) {}
        }.also { it.name = "screencap" }.start()
    }
}
