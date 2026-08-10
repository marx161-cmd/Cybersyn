package com.termux.cybersyn.core.mqtt

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.termux.cybersyn.core.logging.AppLogger
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream

/**
 * Local ingest doorway into Cybersyn's single persistent MQTT connection.
 *
 * Every termux-family app and script (all UID 1000) reaches comrade over MQTT by dropping
 * `topic\tpayload` lines into this FIFO; the reader forwards each line straight into
 * [MqttBridge.publish], which reuses the ONE persistent publisher process. This is the only
 * sanctioned outbound path — nothing outside Cybersyn opens its own MQTT connection. Writers
 * use the trivial `cybersyn-send` wrapper in $PREFIX/bin.
 *
 * The FIFO is opened O_RDWR so the reader always holds a writer end of its own: read() blocks
 * cheaply when idle instead of hitting EOF every time the last external writer closes (which
 * would otherwise spin this loop reopening the pipe).
 */
object MqttIngest {
    // $PREFIX/tmp lives on the shared UID-1000 filesystem. Termux may clear tmp between
    // sessions, so the FIFO is (re)created on every start().
    private const val FIFO_PATH = "/data/data/com.termux/files/usr/tmp/cybersyn-pub.fifo"
    private const val TAG = "MqttIngest"

    @Volatile private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var stream: FileInputStream? = null

    @Synchronized
    fun start(context: Context) {
        if (thread != null) return
        running = true
        val app = context.applicationContext
        thread = Thread({ loop(app) }, "cybersyn-mqtt-ingest").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        // A blocking readLine() on a FileDescriptor is NOT interruptible via Thread.interrupt()
        // (the documented leak shape in MqttBridge.subscribe — leaked reader processes). Wake the
        // reader deterministically by writing one newline into the FIFO; with running=false the
        // loop reads the empty line and exits. Closing the stream is only a backstop.
        runCatching {
            val fd = Os.open(FIFO_PATH, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0)
            try {
                Os.write(fd, byteArrayOf('\n'.code.toByte()), 0, 1)
            } finally {
                Os.close(fd)
            }
        }
        stream?.let { runCatching { it.close() } }
        thread?.interrupt()
        thread = null
    }

    /** Create the FIFO if absent; replace a stray non-FIFO sitting at the path. */
    private fun ensureFifo() {
        val existingMode = runCatching { Os.stat(FIFO_PATH).st_mode }.getOrNull()
        if (existingMode != null && OsConstants.S_ISFIFO(existingMode)) return
        if (existingMode != null) File(FIFO_PATH).delete() // wrong type sitting at the path
        try {
            Os.mkfifo(FIFO_PATH, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        } catch (e: ErrnoException) {
            if (e.errno != OsConstants.EEXIST) throw e
            // Lost a create race: only accept it if what now exists is actually a FIFO.
            val mode = Os.stat(FIFO_PATH).st_mode
            if (!OsConstants.S_ISFIFO(mode)) throw e
        }
    }

    private fun loop(context: Context) {
        while (running) {
            var fd: FileDescriptor? = null
            try {
                ensureFifo()
                fd = Os.open(FIFO_PATH, OsConstants.O_RDWR, 0)
                val fis = FileInputStream(fd)
                stream = fis
                fis.bufferedReader().use { reader ->
                    while (running) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) continue
                        val msg = MqttBridge.parseLine(line) ?: continue
                        // No retry/backoff here: this thread shares MqttBridge.publish's lock
                        // with the trackpad/key streams, so a slow path must never stall here.
                        if (!MqttBridge.publish(context, msg.topic, msg.payload)) {
                            AppLogger.warn(TAG, "publish failed for topic=${msg.topic}")
                        }
                    }
                }
            } catch (e: Exception) {
                if (!running) break
                AppLogger.warn(TAG, "ingest loop error; retrying", e)
                runCatching { Thread.sleep(1000) }
            } finally {
                stream = null
                fd?.let { runCatching { Os.close(it) } }
            }
        }
        AppLogger.info(TAG, "ingest stopped")
    }
}
