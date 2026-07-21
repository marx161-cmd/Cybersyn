package com.termux.cybersyn.core.mqtt

import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Cybersyn's MQTT transport.
 *
 * Primary path is a self-contained native helper (`cybersyn-mqtt`, rumqttc — the same
 * stack as the Fedora relay) bundled in the APK as
 * `jniLibs/arm64-v8a/libcybersyn-mqtt.so`. Android extracts it to nativeLibraryDir as an
 * executable file (needs `extractNativeLibs`/`useLegacyPackaging`); it links only against
 * bionic, so it works on a fresh device with no Termux packages installed.
 *
 * Publishing reuses ONE persistent helper process (arbitrary topics, no per-message
 * handshake — ideal for the mousepad stream). Subscribing is a cold Flow that supervises
 * its own helper process and respawns on drop.
 *
 * Fallback: if the bundled binary is missing/unrunnable, fall back to Termux's mosquitto
 * clients under $PREFIX (same UID, same MQTT). Both speak the identical `topic\tpayload`
 * line format, so parsing is shared. mosquitto's publish fallback is per-message (it can't
 * hold a persistent arbitrary-topic connection), which is fine as a degraded path.
 */
object MqttBridge {
    const val DEFAULT_BROKER = "100.108.8.60"
    const val DEFAULT_PORT = 1883

    private const val HELPER_LIB = "libcybersyn-mqtt.so"
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

    data class Message(val topic: String, val payload: String)

    // ---- publish: one persistent bundled-helper process, reused across calls ----
    private val pubProcess = AtomicReference<Process?>()
    private val pubStream = AtomicReference<OutputStream?>()

    @Synchronized
    fun publish(
        context: Context,
        topic: String,
        message: String,
        broker: String = DEFAULT_BROKER,
        port: Int = DEFAULT_PORT,
    ): Boolean {
        val bundled = bundledHelper(context)
        if (bundled == null) return publishViaMosquitto(topic, message, broker, port)

        val line = "$topic\t$message\n".toByteArray(Charsets.UTF_8)
        pubStream.get()?.let { out ->
            try {
                out.write(line); out.flush(); return true
            } catch (_: Exception) {
                closePub() // process died; fall through and respawn once
            }
        }
        return try {
            val proc = ProcessBuilder(bundled.absolutePath, "pub", "--broker", broker, "--port", port.toString())
                .redirectErrorStream(true)
                .start()
            pubProcess.set(proc)
            pubStream.set(proc.outputStream)
            proc.outputStream.write(line)
            proc.outputStream.flush()
            true
        } catch (_: Exception) {
            closePub()
            false
        }
    }

    private fun closePub() {
        pubStream.getAndSet(null)?.let { runCatching { it.close() } }
        pubProcess.getAndSet(null)?.let { runCatching { it.destroy() } }
    }

    private fun publishViaMosquitto(topic: String, message: String, broker: String, port: Int): Boolean =
        try {
            val proc = mosquitto(
                listOf("bin/mosquitto_pub", "-h", broker, "-p", port.toString(), "-q", "0", "-t", topic, "-m", message),
            )
            proc.waitFor() == 0
        } catch (_: Exception) {
            false
        }

    // ---- subscribe: cold Flow, supervises a helper process and respawns on drop ----
    fun subscribe(
        context: Context,
        topicFilter: String,
        broker: String = DEFAULT_BROKER,
        port: Int = DEFAULT_PORT,
    ): Flow<Message> = callbackFlow {
        val running = AtomicBoolean(true)
        val procRef = AtomicReference<Process?>()
        val bundled = bundledHelper(context)

        val worker = Thread({
            while (running.get()) {
                try {
                    val proc = if (bundled != null) {
                        ProcessBuilder(bundled.absolutePath, "sub", "--broker", broker, "--port", port.toString(), "--topic", topicFilter)
                            .start()
                    } else {
                        mosquitto(listOf("bin/mosquitto_sub", "-h", broker, "-p", port.toString(), "-t", topicFilter, "-F", "%t\t%p"))
                    }
                    procRef.set(proc)
                    // Both the helper and mosquitto -F '%t\t%p' emit identical lines.
                    proc.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (!running.get()) break
                            parseLine(line)?.let { trySend(it) }
                        }
                    }
                } catch (_: Exception) {
                    // fall through to backoff + respawn
                } finally {
                    procRef.getAndSet(null)?.let { runCatching { it.destroy() } }
                }
                if (!running.get()) break
                try {
                    Thread.sleep(2_000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "cybersyn-mqtt-sub")
        worker.isDaemon = true
        worker.start()

        awaitClose {
            running.set(false)
            procRef.getAndSet(null)?.let { runCatching { it.destroy() } }
            worker.interrupt()
        }
    }

    /** Parse a `topic\tpayload` line; payload may be empty. Returns null for a blank line. */
    internal fun parseLine(line: String): Message? {
        if (line.isEmpty()) return null
        val tab = line.indexOf('\t')
        return if (tab < 0) Message(line, "") else Message(line.substring(0, tab), line.substring(tab + 1))
    }

    /** The bundled native helper if present and executable, else null. */
    private fun bundledHelper(context: Context): File? =
        File(context.applicationInfo.nativeLibraryDir, HELPER_LIB).takeIf { it.canExecute() }

    /** Spawn a Termux mosquitto client with the linker env it needs (same UID). */
    private fun mosquitto(relativeArgv: List<String>): Process {
        val argv = relativeArgv.toMutableList()
        argv[0] = "$TERMUX_PREFIX/${argv[0]}"
        val pb = ProcessBuilder(argv)
        pb.environment()["PREFIX"] = TERMUX_PREFIX
        pb.environment()["LD_LIBRARY_PATH"] = "$TERMUX_PREFIX/lib"
        return pb.start()
    }
}
