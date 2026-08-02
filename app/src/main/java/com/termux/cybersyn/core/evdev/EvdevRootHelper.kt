package com.termux.cybersyn.core.evdev

import com.termux.cybersyn.core.evdev.EvdevDeviceInfo
import com.termux.cybersyn.core.evdev.GrabTargetKeyCode
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Root helper that runs via `su -c`, loads libevdev_manager.so, grabs event0, and
 * communicates events via stdin/stdout.
 *
 * Protocol:
 *   stdout: EV_RAW  <deviceId> <type> <code> <value> <androidCode>        (per key event)
 *           EV_DOWN <deviceId> <androidCode>                              (key down, simplified)
 *           EV_UP   <deviceId> <androidCode>                              (key up, simplified)
 *           GRAB_OK  <deviceName>                                         (grab succeeded)
 *           READY    <pid>                                                (fully initialized)
 *   stdin:  EMIT    <deviceId> <type> <code> <value>                      (write evdev event)
 *           EMIT_KC <deviceId> <androidCode> <value>                       (write android keycode)
 *           GRAB    <bus> <vendor> <product> <code>,...                    (set grab targets)
 *           QUIT                                                          (shutdown)
 */
object EvdevRootHelper {
    private const val TAG = "EvdevRootHelper"

    private const val MODE_NORMAL = 0
    private const val MODE_GYRO = 1
    private const val MODE_BROWSER = 2

    private const val EV_KEY = 1

    // Linux input codes (NOT Android keycodes) — this callback sees raw evdev.
    private const val KEY_VOLUME_DOWN = 114
    private const val KEY_VOLUME_UP = 115
    private const val KEY_POWER = 116

    @JvmStatic
    fun main(args: Array<String>) {
        val nativeLibDir = if (args.isNotEmpty()) args[0] else "/data/app"
        System.load("$nativeLibDir/libevdev_manager.so")
        val bridge = CybersynEvdevBridge()
        bridge.initEvdevManager()

        val running = AtomicBoolean(true)
        val reader = BufferedReader(InputStreamReader(System.`in`))
        val stdoutLock = Any()

        // Routing mode lives HERE, not in the controller, because the consume decision
        // must be made synchronously inside the native callback: returning false makes
        // the native layer re-emit the event immediately (event_loop.rs: `if !consumed
        // { write_event(...) }`). A controller sitting on the other end of a pipe cannot
        // answer in time -- by the time it reads EV_DOWN off stdout the key is already
        // through. So the controller pushes MODE on change (rare) and the callback
        // consults it per event (hot path).
        val mode = AtomicInteger(MODE_NORMAL)
        val powerHeld = AtomicBoolean(false)

        // Print READY with pid — same trick as LogcatContextSource
        println("READY ${android.os.Process.myPid()}")
        System.out.flush()

        // Set event callback — writes simplified events to stdout
        bridge.setEventCallback { deviceId, _, _, type, code, value, androidCode ->
            synchronized(stdoutLock) {
                if (androidCode != 0) {
                    when (value) {
                        0 -> println("EV_UP $deviceId $androidCode")
                        1 -> println("EV_DOWN $deviceId $androidCode")
                        2 -> println("EV_REPEAT $deviceId $androidCode")
                    }
                }
                println("EV_RAW $deviceId $type $code $value $androidCode")
                System.out.flush()
            }
            // Consume decision, synchronous. false => native re-emits verbatim, which
            // preserves real press/hold/release timing, so Android's own long-press and
            // chord handling keep working. Never synthesize a pass-through on top of
            // this -- that is a double press.
            if (type != EV_KEY) {
                false
            } else {
                when (code) {
                    // Power is never remapped: let it through so long-press (assistant /
                    // power menu, per power_button_long_press) resolves natively.
                    KEY_POWER -> {
                        powerHeld.set(value != 0)
                        false
                    }
                    KEY_VOLUME_DOWN, KEY_VOLUME_UP ->
                        // Power held: hand the volume keys back so Android's own
                        // power+vol_down screenshot chord fires (and suppresses the
                        // power action itself). Otherwise consume only in a mode that
                        // actually remaps them.
                        !powerHeld.get() && mode.get() != MODE_NORMAL
                    else -> false
                }
            }
        }

        bridge.setDevicesChangedCallback { handles ->
            synchronized(stdoutLock) {
                for (h in handles) {
                    println("GRAB_OK ${h.name} ${h.id}")
                }
                System.out.flush()
            }
        }

        // Stdin command loop
        while (running.get()) {
            val line = reader.readLine() ?: break
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.isEmpty()) continue

            try {
                when (parts[0]) {
                    "GRAB" -> {
                        // GRAB <bus> <vendor> <product> <code1>,<code2>,...
                        if (parts.size < 5) continue
                        val bus = parts[1].toInt()
                        val vendor = parts[2].toInt()
                        val product = parts[3].toInt()
                        val codes = parts[4].split(",").mapNotNull { it.toIntOrNull() }.toIntArray()
                        bridge.setGrabTargetsNative(
                            arrayOf(GrabTargetKeyCode("gpio_keys", bus, vendor, product, codes))
                        )
                    }
                    "EMIT" -> {
                        // EMIT <deviceId> <type> <code> <value>
                        if (parts.size < 5) continue
                        bridge.writeEvdevEventNative(
                            parts[1].toInt(), parts[2].toInt(),
                            parts[3].toInt(), parts[4].toInt()
                        )
                    }
                    "EMIT_KC" -> {
                        // EMIT_KC <deviceId> <androidCode> <value>
                        if (parts.size < 4) continue
                        bridge.writeEvdevEventKeyCodeNative(
                            parts[1].toInt(), parts[2].toInt(), parts[3].toInt()
                        )
                    }
                    "MODE" -> {
                        // MODE normal|gyro|browser — pushed by the controller when the
                        // keyboard shows/hides or the foreground app changes.
                        if (parts.size < 2) continue
                        mode.set(
                            when (parts[1].lowercase()) {
                                "gyro" -> MODE_GYRO
                                "browser" -> MODE_BROWSER
                                else -> MODE_NORMAL
                            },
                        )
                    }
                    "QUIT" -> running.set(false)
                }
            } catch (_: Exception) {
                // Malformed command — skip
            }
        }

        bridge.destroyEvdevManager()
    }
}
