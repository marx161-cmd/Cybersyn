package com.termux.cybersyn.core.contexts

import android.content.Context
import com.termux.cybersyn.core.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicInteger

class LogcatContextSource : SubscriptionReadyContextSource {
    override val type = "logcat"

    private val subscriberCount = AtomicInteger(0)
    private var readerJob: Job? = null
    private var readerProcess: Process? = null
    private var readerScope: CoroutineScope? = null
    @Volatile private var readerPid: Int? = null

    private val sharedFlow = MutableSharedFlow<ContextEvent>(
        replay = 0,
        extraBufferCapacity = 128,
    )

    override fun events(app: Context, onSubscribed: () -> Unit): Flow<ContextEvent> = callbackFlow {
        val count = subscriberCount.incrementAndGet()

        val collectorJob = launch {
            sharedFlow.collect { send(it) }
        }

        if (count == 1) {
            startReader(app)
        }
        onSubscribed()

        awaitClose {
            collectorJob.cancel()
            if (subscriberCount.decrementAndGet() == 0) {
                stopReader()
            }
        }
    }

    override fun events(app: Context): Flow<ContextEvent> = events(app) {}

    private fun startReader(app: Context) {
        if (readerProcess?.isAlive == true) {
            AppLogger.warn(TAG, "startReader called with a reader already running; ignoring")
            return
        }
        val scope = CoroutineScope(Dispatchers.IO + Job())
        readerScope = scope
        readerJob = scope.launch {
            try {
                val pb = ProcessBuilder(
                    "su", "-c",
                    // `exec` replaces this shell's image with logcat's, so the pid this shell
                    // reports via `$$` is logcat's real pid for the rest of its life — captured
                    // here since it re-execs as root and Process.pid()/destroy() can't reach it.
                    "echo READER_PID:\$\$; exec logcat -v threadtime *:E *:S",
                )
                pb.redirectErrorStream(true)
                val process = pb.start()
                readerProcess = process
                AppLogger.info(TAG, "logcat reader started")

                BufferedReader(InputStreamReader(process.inputStream), 8192).use { reader ->
                    var line: String?
                    var pidLineConsumed = false
                    while (isActive) {
                        line = reader.readLine() ?: break
                        if (!pidLineConsumed) {
                            pidLineConsumed = true
                            readerPid = line.removePrefix("READER_PID:").trim().toIntOrNull()
                            continue
                        }
                        if (line.isBlank()) continue
                        val event = parseLogLine(line) ?: continue
                        sharedFlow.tryEmit(event)
                    }
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                AppLogger.error(TAG, "logcat reader error", e)
            } finally {
                killReaderProcess(readerProcess, readerPid)
                readerProcess = null
                readerPid = null
                readerJob = null
                AppLogger.info(TAG, "logcat reader stopped")
            }
        }
    }

    private fun stopReader() {
        readerJob?.cancel()
        readerJob = null
        killReaderProcess(readerProcess, readerPid)
        readerProcess = null
        readerPid = null
        readerScope = null
    }

    /**
     * [Process.destroy] only sends a signal the JVM's own (unprivileged) UID is allowed to
     * deliver. The reader was launched via `su -c "exec logcat ..."`, so it re-execs as root —
     * destroy() silently no-ops against it (no exception, no effect) and the process survives
     * every stop/start cycle as an unkillable, ever-accumulating orphan. Killing it needs another
     * root shell, by the pid the shell reported before it exec'd into logcat.
     */
    private fun killReaderProcess(process: Process?, pid: Int?) {
        process?.destroy()
        if (pid != null) {
            runCatching {
                ProcessBuilder("su", "-c", "kill -9 $pid").start().waitFor()
            }.onFailure { AppLogger.warn(TAG, "Failed to root-kill logcat reader pid=$pid", it) }
        }
    }

    /**
     * Unconditional teardown regardless of [subscriberCount]. Called by the owning monitor's
     * stop handle so a stuck/miscounted subscriber can never keep the reader process alive forever.
     */
    fun forceStop() {
        subscriberCount.set(0)
        stopReader()
    }

    companion object {
        private const val TAG = "LogcatContextSource"

        internal fun parseLogLine(line: String): ContextEvent? {
            // threadtime: "07-24 14:35:54.038  1809  1809 E Tag: message"
            val parts = line.split(" ", limit = 6)
            if (parts.size < 6) return null

            val priority = parts[4]
            val tagMessage = parts[5]
            val colonIdx = tagMessage.indexOf(':')
            if (colonIdx < 0) return null

            val tag = tagMessage.substring(0, colonIdx).trim()
            val message = tagMessage.substring(colonIdx + 1).trim()
            val pidStr = parts[2]

            return ContextEvent(
                type = "logcat",
                matched = true,
                metadata = mapOf(
                    "tag" to tag,
                    "message" to message,
                    "priority" to priority,
                    "pid" to pidStr,
                    "raw" to line,
                ),
            )
        }
    }
}
