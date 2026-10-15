package com.termux.cybersyn.core.mqtt

import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import com.termux.cybersyn.core.logging.AppLogger

/**
 * Cybersyn's MQTT transport.
 *
 * A self-contained native helper (`cybersyn-mqtt`, rumqttc — the same stack as the Fedora
 * relay) bundled in the APK as `jniLibs/arm64-v8a/libcybersyn-mqtt.so`. Android extracts it
 * to nativeLibraryDir as an executable file (needs `extractNativeLibs`/`useLegacyPackaging`);
 * it links only against bionic, so it works on a fresh device with no Termux packages.
 *
 * ONE helper process (`hub`) = ONE MQTT connection per (broker, port) carries every
 * subscription and every publish. This used to be one process + TCP connection per topic
 * filter plus a separate publisher, each with its own 30 s keepalive; on a phone that sits
 * on LTE every one of those wake-ups costs cellular-radio energy (measured 2026-10-15:
 * radio was ~60% of all battery drain). Inbound messages are routed to collectors here by
 * MQTT filter matching; filters are ref-counted so N collectors of one filter share one
 * broker subscription.
 *
 * Hub stdio protocol (line based, tab separated): stdin `S\t<filter>`, `U\t<filter>`,
 * `P\t<topic>\t<payload>`; stdout `<topic>\t<payload>` per inbound message.
 */
object MqttBridge {
    const val DEFAULT_BROKER = "100.108.8.60"
    const val DEFAULT_PORT = 1883

    private const val HELPER_LIB = "libcybersyn-mqtt.so"

    data class Message(val topic: String, val payload: String)

    private val hubs = ConcurrentHashMap<String, Hub>()

    private fun hubFor(context: Context, broker: String, port: Int): Hub =
        hubs.getOrPut("$broker:$port") { Hub(broker, port, bundledHelper(context.applicationContext)) }

    fun publish(
        context: Context,
        topic: String,
        message: String,
        broker: String = DEFAULT_BROKER,
        port: Int = DEFAULT_PORT,
    ): Boolean {
        val hub = hubFor(context, broker, port)
        if (hub.helper == null) {
            AppLogger.error("MqttBridge", "Bundled mqtt helper ($HELPER_LIB) missing; cannot publish. Termux mosquitto clients were deliberately removed from this phone — no fallback path exists.")
            return false
        }
        return hub.send("P\t$topic\t$message")
    }

    /**
     * A cold Flow of messages whose topic matches [topicFilter]. Each collector registers a
     * listener on the shared hub; the broker subscription exists while at least one is live.
     */
    fun subscribe(
        context: Context,
        topicFilter: String,
        broker: String = DEFAULT_BROKER,
        port: Int = DEFAULT_PORT,
    ): Flow<Message> {
        val hub = hubFor(context, broker, port)
        return callbackFlow {
            var listener: Listener? = null
            if (hub.helper == null) {
                AppLogger.error("MqttBridge", "Bundled mqtt helper ($HELPER_LIB) missing; cannot subscribe to $topicFilter")
            } else {
                listener = Listener(topicFilter) { trySend(it) }.also { hub.addListener(it) }
            }
            awaitClose { listener?.let { hub.removeListener(it) } }
        }
    }

    private class Listener(val filter: String, val deliver: (Message) -> Unit)

    /**
     * One helper process, one MQTT connection. The process is spawned and recorded inside the
     * same critical section ([lock]) and a reader thread only ever clears the process it was
     * started for, so there is exactly one live helper per hub and no window where a spawned
     * process has no owner (the 2026-07-31 leak shape: ~1 orphan per restart cycle).
     */
    private class Hub(val broker: String, val port: Int, val helper: File?) {
        private val lock = Any()
        private var proc: Process? = null
        private var out: OutputStream? = null
        private val refs = HashMap<String, Int>()          // filter -> live listener count (lock)
        private val listeners = CopyOnWriteArrayList<Listener>()

        fun addListener(l: Listener) {
            listeners.add(l)
            synchronized(lock) {
                val n = (refs[l.filter] ?: 0) + 1
                refs[l.filter] = n
                // Spawning re-sends every filter in refs (incl. this one); only write S ourselves
                // when a live helper already existed and this filter is new to it.
                val hadProc = proc?.isAlive == true
                if (!ensureStartedLocked()) return
                if (hadProc && n == 1) writeLocked("S\t${l.filter}")
            }
        }

        fun removeListener(l: Listener) {
            listeners.remove(l)
            synchronized(lock) {
                val n = (refs[l.filter] ?: return) - 1
                if (n <= 0) {
                    refs.remove(l.filter)
                    if (proc?.isAlive == true) writeLocked("U\t${l.filter}")
                } else {
                    refs[l.filter] = n
                }
            }
        }

        fun send(line: String): Boolean = synchronized(lock) {
            if (!ensureStartedLocked()) return false
            if (writeLocked(line)) return true
            // Helper died under us: killCurrent already ran; respawn once and retry.
            ensureStartedLocked() && writeLocked(line)
        }

        private fun writeLocked(line: String): Boolean {
            val o = out ?: return false
            return try {
                o.write((line + "\n").toByteArray(Charsets.UTF_8)); o.flush(); true
            } catch (_: Exception) {
                killLocked(); false
            }
        }

        private fun killLocked() {
            // SIGKILL, not SIGTERM: short-lived network helper, nothing to flush.
            proc?.let { runCatching { it.destroyForcibly() } }
            proc = null
            out = null
        }

        private fun ensureStartedLocked(): Boolean {
            if (proc?.isAlive == true) return true
            val bin = helper ?: return false
            return try {
                val p = ProcessBuilder(
                    bin.absolutePath, "hub", "--broker", broker, "--port", port.toString(),
                    "--id", "cybersyn-hub-${android.os.Process.myPid()}",
                ).redirectErrorStream(false).start()
                proc = p
                out = p.outputStream
                startReader(p)
                // A fresh helper knows no filters; restore everything still wanted.
                for (f in refs.keys) writeLocked("S\t$f")
                true
            } catch (_: Exception) {
                killLocked(); false
            }
        }

        private fun startReader(p: Process) {
            Thread({
                try {
                    p.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            val m = parseLine(line) ?: continue
                            for (l in listeners) if (topicMatches(l.filter, m.topic)) l.deliver(m)
                        }
                    }
                } catch (_: Exception) {
                }
                // EOF: this helper is gone. Clear it only if it is still the current one.
                val wanted = synchronized(lock) {
                    if (proc === p) { runCatching { p.destroyForcibly() }; proc = null; out = null }
                    refs.isNotEmpty()
                }
                if (wanted) {
                    try { Thread.sleep(2_000) } catch (_: InterruptedException) { return@Thread }
                    synchronized(lock) { if (refs.isNotEmpty()) ensureStartedLocked() }
                }
            }, "cybersyn-mqtt-hub").apply { isDaemon = true }.start()
        }
    }

    /** Parse a `topic\tpayload` line; payload may be empty. Returns null for a blank line. */
    internal fun parseLine(line: String): Message? {
        if (line.isEmpty()) return null
        val tab = line.indexOf('\t')
        return if (tab < 0) Message(line, "") else Message(line.substring(0, tab), line.substring(tab + 1))
    }

    /** MQTT filter match: `+` = exactly one level, `#` = this level and everything below (last only). */
    internal fun topicMatches(filter: String, topic: String): Boolean {
        val f = filter.split('/')
        val t = topic.split('/')
        for (i in f.indices) {
            val seg = f[i]
            if (seg == "#") return i == f.size - 1
            if (i >= t.size) return false
            if (seg != "+" && seg != t[i]) return false
        }
        return f.size == t.size
    }

    /** The bundled native helper if present and executable, else null. */
    private fun bundledHelper(context: Context): File? =
        File(context.applicationInfo.nativeLibraryDir, HELPER_LIB).takeIf { it.canExecute() }
}
