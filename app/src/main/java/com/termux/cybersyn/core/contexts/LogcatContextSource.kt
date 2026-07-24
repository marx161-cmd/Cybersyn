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
        val scope = CoroutineScope(Dispatchers.IO + Job())
        readerScope = scope
        readerJob = scope.launch {
            try {
                val pb = ProcessBuilder(
                    "su", "-c",
                    "exec logcat -v threadtime *:E *:S",
                )
                pb.redirectErrorStream(true)
                val process = pb.start()
                readerProcess = process
                AppLogger.info(TAG, "logcat reader started")

                BufferedReader(InputStreamReader(process.inputStream), 8192).use { reader ->
                    var line: String?
                    while (isActive) {
                        line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val event = parseLogLine(line) ?: continue
                        sharedFlow.tryEmit(event)
                    }
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                AppLogger.error(TAG, "logcat reader error", e)
            } finally {
                readerProcess?.destroy()
                readerProcess = null
                readerJob = null
                AppLogger.info(TAG, "logcat reader stopped")
            }
        }
    }

    private fun stopReader() {
        readerJob?.cancel()
        readerJob = null
        readerProcess?.destroy()
        readerProcess = null
        readerScope = null
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
