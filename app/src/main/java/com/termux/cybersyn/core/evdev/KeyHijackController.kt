package com.termux.cybersyn.core.evdev

import android.content.Context
import com.termux.cybersyn.core.logging.AppLogger
import com.termux.cybersyn.core.mqtt.MqttBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * Routing:
 *   - IME shown + vol keys        → MQTT android/clutch (gyro) / android/click (hold)
 *   - browser foreground, no IME  → media prev/next
 *   - registered KeyTriggers      → classified short/long/double/chord matches
 *
 * Pass-through is NOT done here. The helper's callback returns false for anything the
 * current mode doesn't consume and the native layer re-emits it verbatim, so Android's
 * own long-press power handling and the power+vol_down screenshot chord keep working.
 * Emitting a pass-through from this side would deliver every key twice.
 *
 * Routing is decided at key-down and LATCHED: the release of a press is routed by what
 * the press started (see [activePress]), never by the mode at release time — a keyboard
 * that hides mid-hold must still end the gyro clutch it started. The helper latches its
 * consume decision the same way.
 *
 * Codes on this side are ANDROID keycodes (the helper maps them); the helper's own
 * consume logic works in raw Linux evdev codes.
 */
object KeyHijackController {
    private const val TAG = "KeyHijackController"
    private const val KEY_VOLUME_DOWN = 25
    private const val KEY_VOLUME_UP = 24
    private const val KEY_POWER = 26
    private const val KEY_MEDIA_NEXT = 87
    private const val KEY_MEDIA_PREVIOUS = 88
    private const val TICK_INTERVAL_MS = 50L

    // A helper that dies faster than this counts toward the give-up threshold.
    private const val FAST_DEATH_MS = 5_000L
    private const val MAX_FAST_DEATHS = 8
    private const val MAX_BACKOFF_MS = 30_000L

    /**
     * Android → Linux evdev code map for the CONSUME pushdown. Only keys we actually
     * grab need entries. Power is deliberately absent: the helper never consumes power
     * (its callback handles KEY_POWER before consulting the consume set), so a trigger
     * asking to consume it would silently misbehave — better to fail the mapping loudly.
     */
    private val ANDROID_TO_LINUX = mapOf(
        KEY_VOLUME_UP to 115,
        KEY_VOLUME_DOWN to 114,
        KEY_MEDIA_NEXT to 163,
        KEY_MEDIA_PREVIOUS to 165,
    )

    /** What a key-down started, so its release routes the same way. */
    private enum class PressKind { GYRO, HOLD, BROWSER_NAV, PASSIVE }

    private val procLock = Any()
    private var helperProcess: Process? = null
    private var helperWriter: OutputStreamWriter? = null
    private var helperReaderJob: Job? = null
    private var tickJob: Job? = null
    private val running = AtomicBoolean(false)
    private val keyState = mutableMapOf<Int, Boolean>()
    private val activePress = mutableMapOf<Int, PressKind>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Restart bookkeeping (guarded by procLock).
    private var lastSpawnAtMs = 0L
    private var fastDeathCount = 0
    private var gaveUp = false

    // Power held 10s → the native layer exits by design (Key Mapper's lockout escape).
    // Honor it: no auto-restart until the next explicit start() from the service.
    private val emergencyStop = AtomicBoolean(false)

    /**
     * Classified triggers (long press, double press, chords). Gyro and hold are
     * deliberately NOT expressed here: they are hold-to-act, firing on key-down and
     * again on key-up, which a click-type model can't represent -- ClickType describes a
     * completed press, and a hold has no completion until release. Those stay on the raw
     * down/up path below; the detector adds the press classification Cybersyn never had.
     */
    private var detector: KeyTriggerDetector? = null
    private var consumeCodes: Set<Int> = emptySet()

    /**
     * Replace the classified trigger set. Safe to call while running.
     *
     * Triggers with `consumeEvent=true` get their codes pushed to the helper's CONSUME
     * set — the swallow happens in the native callback (Linux codes), not here. A
     * consuming trigger swallows EVERY press of its keys (a first press cannot know it
     * won't become part of a match), so normal function of that key is gone while the
     * trigger is registered; register consuming triggers deliberately.
     */
    fun setTriggers(triggers: List<KeyTrigger>, onTrigger: (KeyTrigger) -> Unit) {
        detector = if (triggers.isEmpty()) {
            null
        } else {
            KeyTriggerDetector(triggers, onTrigger)
        }
        consumeCodes = triggers
            .filter { it.consumes }
            .flatMap { trigger -> trigger.keys.filter { it.consumeEvent }.map { it.code } }
            .mapNotNull { androidCode ->
                ANDROID_TO_LINUX[androidCode].also {
                    if (it == null) {
                        AppLogger.error(TAG, "No Linux code mapping for consuming trigger key $androidCode; NOT consumed")
                    }
                }
            }
            .toSet()
        pushConsume()
    }

    // Set by caller (AutomationService). Both push the routing mode down to the helper,
    // because the consume decision has to be made inside the native callback -- see the
    // comment in EvdevRootHelper.
    var appContext: Context? = null
    var deviceId: Int = 0

    var imeShown: Boolean = false
        set(value) {
            field = value
            pushMode()
        }

    var browserForeground: Boolean = false
        set(value) {
            field = value
            pushMode()
        }

    private fun pushMode() {
        val mode = when {
            imeShown -> "gyro"
            browserForeground -> "browser"
            else -> "normal"
        }
        send("MODE $mode")
    }

    private fun pushConsume() {
        val codes = consumeCodes
        send(if (codes.isEmpty()) "CONSUME" else "CONSUME ${codes.joinToString(",")}")
    }

    /** Explicit start from the service. Clears emergency/give-up state. */
    fun start(context: Context) {
        emergencyStop.set(false)
        synchronized(procLock) { gaveUp = false; fastDeathCount = 0 }
        startInternal(context)
    }

    private fun startInternal(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        synchronized(procLock) {
            if (running.get()) {
                AppLogger.warn(TAG, "already running")
                return
            }
            if (gaveUp) {
                AppLogger.warn(TAG, "helper gave up after $MAX_FAST_DEATHS fast deaths; not restarting")
                return
            }
            running.set(true)

            // The helper is a root child of a `su` we spawned, so it outlives us: an app
            // crash, a force-stop or a reinstall leaves it running and still holding
            // EVIOCGRAB on event0. The next generation then fails to grab with EBUSY(-16)
            // and the keys are dead. Process.destroyForcibly() can't signal a root child,
            // so sweep by cmdline first -- same remedy as killStrayLogcatReaders().
            killStrayHelpers()

            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val apkPath = context.packageCodePath
            val cmd = arrayOf(
                "su", "-c",
                "CLASSPATH=$apkPath /system/bin/app_process / com.termux.cybersyn.core.evdev.EvdevRootHelper $nativeLibDir"
            )

            // A failed spawn (su denied, ENOENT mid-update) must not leave running=true
            // with no process behind it — that wedges every future start() on the
            // already-running guard, permanently.
            val proc = try {
                ProcessBuilder(*cmd)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) {
                AppLogger.error(TAG, "Failed to spawn root helper", e)
                running.set(false)
                scheduleRestart(context, diedFast = true)
                return
            }

            lastSpawnAtMs = System.currentTimeMillis()
            helperProcess = proc
            helperWriter = OutputStreamWriter(proc.outputStream)

            AppLogger.info(TAG, "root helper started")

            // Long-press and deferred short-press fire on elapsed time, not on an event,
            // so they need a clock: a key held down produces no further stdout traffic
            // once autorepeat is exhausted. 50ms is well under the 300ms double-press
            // window and costs nothing while idle (the detector is null unless triggers
            // are registered).
            tickJob = scope.launch {
                while (running.get()) {
                    detector?.tick()
                    delay(TICK_INTERVAL_MS)
                }
            }

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
                    val shouldRestart = synchronized(procLock) {
                        if (running.get() && helperProcess === proc) {
                            cleanup()
                            // Must clear `running` before restarting: start() bails out
                            // on the already-running guard otherwise, so the helper
                            // would never actually come back.
                            running.set(false)
                            true
                        } else {
                            false
                        }
                    }
                    if (shouldRestart) {
                        if (emergencyStop.get()) {
                            AppLogger.error(
                                TAG,
                                "root helper exited via 10s-power-hold EMERGENCY; NOT restarting (lockout escape)",
                            )
                        } else {
                            val uptime = System.currentTimeMillis() - lastSpawnAtMs
                            AppLogger.warn(TAG, "root helper died unexpectedly after ${uptime}ms; restarting")
                            scheduleRestart(context, diedFast = uptime < FAST_DEATH_MS)
                        }
                    }
                }
            }
        }
    }

    /**
     * Restart with backoff. A helper that keeps dying on arrival (missing .so after an
     * update, su policy change) must not become a tight su+pkill+app_process loop.
     */
    private fun scheduleRestart(context: Context, diedFast: Boolean) {
        val delayMs = synchronized(procLock) {
            if (!diedFast) {
                fastDeathCount = 0
                0L
            } else {
                fastDeathCount++
                if (fastDeathCount > MAX_FAST_DEATHS) {
                    gaveUp = true
                    -1L
                } else {
                    minOf(MAX_BACKOFF_MS, 1000L shl (fastDeathCount - 1))
                }
            }
        }
        if (delayMs < 0) {
            AppLogger.error(TAG, "root helper died $MAX_FAST_DEATHS times in a row; giving up until next service start")
            return
        }
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            startInternal(context)
        }
    }

    fun stop() {
        synchronized(procLock) {
            running.set(false)
            // Orderly shutdown first: QUIT lets the helper run destroyEvdevManager()
            // and release the grab itself, instead of relying on stdin EOF + the next
            // generation's sweep.
            try {
                helperWriter?.apply { write("QUIT\n"); flush() }
            } catch (_: Exception) {}
            cleanup()
        }
    }

    private fun cleanup() {
        tickJob?.cancel()
        tickJob = null
        detector?.reset()
        helperReaderJob?.cancel()
        helperReaderJob = null
        try { helperWriter?.close() } catch (_: Exception) {}
        helperWriter = null
        helperProcess?.destroyForcibly()
        helperProcess = null
        keyState.clear()
        activePress.clear()
        deviceId = 0
    }

    /**
     * Kill any EvdevRootHelper left over from a previous generation. Matches on the class
     * name in the cmdline, which is stable across the changing /data/app hash, and covers
     * both the `sh -c` wrapper and the app_process child. The bracket trick keeps the
     * pattern from matching the sweep's own su/sh wrapper, whose cmdline contains the
     * pattern text itself.
     */
    private fun killStrayHelpers() {
        runCatching {
            ProcessBuilder(
                "su", "-c",
                "pkill -9 -f 'EvdevRootHelpe[r]'",
            ).start().waitFor()
        }.onFailure { AppLogger.warn(TAG, "Failed to sweep stray evdev helpers", it) }
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
                    // The GRAB codes become the uinput clone's *extra* capabilities
                    // (extra_event_codes): gpio_keys only advertises vol±/power, so the
                    // media keys MUST be registered here or the kernel silently drops
                    // every EMIT_KC for them (browser prev/next was dead without this).
                    setGrabTargets(
                        0x19, 0x1, 0x1,
                        intArrayOf(KEY_VOLUME_DOWN, KEY_VOLUME_UP, KEY_POWER, KEY_MEDIA_NEXT, KEY_MEDIA_PREVIOUS),
                    )
                    // (Re)establish routing state — a restarted helper starts blank.
                    pushMode()
                    pushConsume()
                }
                "GRAB_OK" -> {
                    deviceId = parts[2].toIntOrNull() ?: 0
                    AppLogger.info(TAG, "grab ok: ${parts[1]} id=$deviceId")
                }
                "EMERGENCY" -> {
                    emergencyStop.set(true)
                    AppLogger.error(TAG, "EMERGENCY from helper (power held 10s); native layer exits itself")
                }
                "EV_UP" -> {
                    val code = parts[2].toInt()
                    keyState[code] = false
                    detector?.onKeyEvent(code, 0)
                    // Route the release by what the DOWN started, never by current mode:
                    // a keyboard that hides mid-hold must still end the clutch it began,
                    // or android/clutch sticks ON until the next press.
                    when (activePress.remove(code)) {
                        PressKind.GYRO -> stopGyro()
                        PressKind.HOLD -> stopHold()
                        else -> {}
                    }
                }
                "EV_DOWN" -> {
                    val id = parts[1].toInt()
                    val code = parts[2].toInt()
                    keyState[code] = true
                    detector?.onKeyEvent(code, 1)

                    // NOTE: pass-through is NOT handled here. The helper's callback
                    // returns false for anything the current mode doesn't consume, and
                    // the native layer re-emits it verbatim with real timing. Emitting
                    // it again from here would deliver every key twice, and would also
                    // turn a power hold into a discrete press.
                    //
                    // Same for the power+vol_down screenshot: the helper hands the
                    // volume keys back while power is held, so Android's own chord
                    // fires. Re-implementing it would double-fire.
                    if (keyState[KEY_POWER] == true) {
                        activePress[code] = PressKind.PASSIVE
                        return
                    }

                    if (imeShown && code in listOf(KEY_VOLUME_DOWN, KEY_VOLUME_UP)) {
                        // Gyro mode
                        when (code) {
                            KEY_VOLUME_DOWN -> { activePress[code] = PressKind.GYRO; startGyro() }
                            KEY_VOLUME_UP -> { activePress[code] = PressKind.HOLD; startHold() }
                        }
                    } else if (!imeShown && browserForeground && code in listOf(KEY_VOLUME_DOWN, KEY_VOLUME_UP)) {
                        // Browser nav mode.
                        activePress[code] = PressKind.BROWSER_NAV
                        val media = if (code == KEY_VOLUME_DOWN) KEY_MEDIA_PREVIOUS else KEY_MEDIA_NEXT
                        emitKeycode(id, media, 1)
                        emitKeycode(id, media, 0)
                    } else {
                        activePress[code] = PressKind.PASSIVE
                    }
                }
                "EV_RAW" -> {} // Raw events streamed; EV_DOWN/EV_UP are derived
                "EV_REPEAT" -> {
                    val code = parts[2].toIntOrNull() ?: return
                    detector?.onKeyEvent(code, 2)
                }
            }
        } catch (_: Exception) {}
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

}
