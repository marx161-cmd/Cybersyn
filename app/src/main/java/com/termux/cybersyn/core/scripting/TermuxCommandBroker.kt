package com.termux.cybersyn.core.scripting

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal object TermuxCommandBroker {
    private const val RESULT_ACTION = "com.termux.cybersyn.action.TERMUX_COMMAND_RESULT"
    private const val EXTRA_REQUEST_ID = "com.termux.cybersyn.extra.TERMUX_REQUEST_ID"
    private const val MAX_PENDING_COMMANDS = 32
    private val pending = ConcurrentHashMap<String, CompletableDeferred<TermuxCommandResult>>()

    suspend fun execute(context: Context, request: TermuxCommandRequest): TermuxCommandResult {
        if (canExecuteDirectly()) {
            return executeDirect(request)
        }
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<TermuxCommandResult>()
        synchronized(pending) {
            check(pending.size < MAX_PENDING_COMMANDS) { "Too many pending Termux commands" }
            pending[requestId] = deferred
        }

        val callbackIntent = Intent(context, TermuxResultReceiver::class.java).apply {
            action = RESULT_ACTION
            data = Uri.parse("opentasker://termux-result/$requestId")
            setPackage(context.packageName)
            putExtra(EXTRA_REQUEST_ID, requestId)
        }
        val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val callback = PendingIntent.getBroadcast(
            context,
            requestId.hashCode(),
            callbackIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag,
        )

        val (cmdPath, cmdArgs) = buildCommand(request)
        val commandIntent = Intent().apply {
            component = ComponentName(
                TermuxScriptBackend.TERMUX_PACKAGE,
                "com.termux.app.RunCommandService",
            )
            action = ACTION_RUN_COMMAND
            putExtra(EXTRA_COMMAND_PATH, cmdPath)
            putExtra(EXTRA_ARGUMENTS, cmdArgs.toTypedArray())
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_PENDING_INTENT, callback)
            request.workingDirectory?.let { putExtra(EXTRA_WORKDIR, it) }
            request.stdin?.let { putExtra(EXTRA_STDIN, it) }
        }

        return try {
            context.startForegroundService(commandIntent)
            withTimeout(request.timeoutMs) { deferred.await() }
        } finally {
            pending.remove(requestId)
            callback.cancel()
        }
    }

    private fun canExecuteDirectly(): Boolean = File(TERMUX_PREFIX, "bin/sh").canExecute()

    private suspend fun executeDirect(request: TermuxCommandRequest): TermuxCommandResult = withContext(Dispatchers.IO) {
        withTimeout(request.timeoutMs) {
            val (executable, args) = buildCommand(request)
            val workDir = request.workingDirectory?.let(::resolveTermuxPath)?.let(::File)
                ?: File(TERMUX_HOME)
            val process = ProcessBuilder(listOf(executable) + args)
                .directory(workDir)
                .redirectErrorStream(false)
                .apply {
                    environment()["HOME"] = TERMUX_HOME
                    environment()["PREFIX"] = TERMUX_PREFIX
                    environment()["PATH"] = "$TERMUX_PREFIX/bin:/system/bin:/system/xbin:/vendor/bin:/vendor/xbin"
                }
                .start()
            request.stdin?.let { stdin ->
                process.outputStream.bufferedWriter().use { it.write(stdin) }
            } ?: process.outputStream.close()
            coroutineScope {
                val stdout = async { process.inputStream.readCapped(TermuxScriptPolicy.MAX_OUTPUT_BYTES + 1) }
                val stderr = async { process.errorStream.readCapped(TermuxScriptPolicy.MAX_OUTPUT_BYTES + 1) }
                val exit = process.waitFor()
                val out = stdout.await()
                val err = stderr.await()
                TermuxCommandResult(
                    stdout = out.text,
                    stderr = err.text,
                    exitCode = exit,
                    stdoutOriginalLength = out.length,
                    stderrOriginalLength = err.length,
                    errorCode = 0,
                )
            }
        }
    }

    private fun buildCommand(request: TermuxCommandRequest): Pair<String, List<String>> {
        val executable = resolveTermuxPath(request.executable)
        if (!request.useRoot) return executable to request.arguments
        val cmd = listOf(executable) + request.arguments
        val shellCmd = cmd.joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
        return resolveTermuxPath(TermuxScriptPolicy.SU_EXECUTABLE) to listOf("-c", shellCmd)
    }

    private fun resolveTermuxPath(path: String): String = when {
        path == "\$PREFIX" -> TERMUX_PREFIX
        path.startsWith("\$PREFIX/") -> TERMUX_PREFIX + path.removePrefix("\$PREFIX")
        path == "~" -> TERMUX_HOME
        path.startsWith("~/") -> TERMUX_HOME + path.removePrefix("~")
        else -> path
    }

    private fun java.io.InputStream.readCapped(limit: Int): CappedOutput {
        val buffer = ByteArray(4096)
        val output = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (output.size() < limit) {
                output.write(buffer, 0, minOf(read, limit - output.size()))
            }
        }
        return CappedOutput(output.toString(Charsets.UTF_8.name()), total)
    }

    internal fun deliver(intent: Intent) {
        if (intent.action != RESULT_ACTION) return
        val requestId = intent.data?.lastPathSegment ?: return
        if (intent.getStringExtra(EXTRA_REQUEST_ID) != requestId) return
        val resultBundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
        pending.remove(requestId)?.complete(parseResult(resultBundle))
    }

    internal fun parseResult(bundle: Bundle?): TermuxCommandResult {
        if (bundle == null) {
            return TermuxCommandResult(
                stdout = "",
                stderr = "",
                exitCode = -1,
                stdoutOriginalLength = 0,
                stderrOriginalLength = 0,
                errorCode = -1,
            )
        }
        val stdout = bundle.getString(RESULT_STDOUT).orEmpty()
        val stderr = bundle.getString(RESULT_STDERR).orEmpty()
        return TermuxCommandResult(
            stdout = stdout,
            stderr = stderr,
            exitCode = bundle.getInt(RESULT_EXIT_CODE, -1),
            stdoutOriginalLength = parseOriginalLength(bundle, RESULT_STDOUT_ORIGINAL_LENGTH),
            stderrOriginalLength = parseOriginalLength(bundle, RESULT_STDERR_ORIGINAL_LENGTH),
            errorCode = bundle.getInt(RESULT_ERROR_CODE, 0),
        )
    }

    private fun parseOriginalLength(bundle: Bundle, key: String): Int =
        bundle.getString(key)?.toIntOrNull()?.takeIf { it >= 0 } ?: -1

    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    private const val EXTRA_RESULT_BUNDLE = "result"
    private const val RESULT_STDOUT = "stdout"
    private const val RESULT_STDERR = "stderr"
    private const val RESULT_EXIT_CODE = "exitCode"
    private const val RESULT_ERROR_CODE = "err"
    private const val RESULT_STDOUT_ORIGINAL_LENGTH = "stdout_original_length"
    private const val RESULT_STDERR_ORIGINAL_LENGTH = "stderr_original_length"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
}

private data class CappedOutput(val text: String, val length: Int)

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TermuxCommandBroker.deliver(intent)
    }
}
