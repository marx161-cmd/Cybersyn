package com.termux.cybersyn.core.evdev

import com.termux.cybersyn.core.evdev.EvdevDeviceInfo
import com.termux.cybersyn.core.evdev.GrabTargetKeyCode
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

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

    @JvmStatic
    fun main(args: Array<String>) {
        val nativeLibDir = if (args.isNotEmpty()) args[0] else "/data/app"
        System.load("$nativeLibDir/libevdev_manager.so")
        val bridge = CybersynEvdevBridge()
        bridge.initEvdevManager()

        val running = AtomicBoolean(true)
        val reader = BufferedReader(InputStreamReader(System.`in`))
        var stdoutLock = Any()

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
            // Return false = pass through to uinput. The controller handles
            // remapping by sending EMIT/EMIT_KC commands via stdin, and we
            // only consume events that the controller tells us to.
            false
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
                    "QUIT" -> running.set(false)
                }
            } catch (_: Exception) {
                // Malformed command — skip
            }
        }

        bridge.destroyEvdevManager()
    }
}
