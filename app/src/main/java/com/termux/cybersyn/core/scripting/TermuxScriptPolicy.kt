package com.termux.cybersyn.core.scripting

import java.nio.charset.StandardCharsets

internal data class TermuxScriptInvocation(
    val executable: String,
    val argumentText: String?,
    val workingDirectory: String?,
    val stdin: String?,
    val timeoutMs: Long,
    val useRoot: Boolean = false,
)

internal data class PreparedTermuxScript(
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: String?,
    val stdin: String?,
    val timeoutMs: Long,
)

internal data class TermuxCommandRequest(
    val executable: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val stdin: String? = null,
    val timeoutMs: Long,
    val useRoot: Boolean = false,
)

internal data class TermuxCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val stdoutOriginalLength: Int,
    val stderrOriginalLength: Int,
    val errorCode: Int,
)

internal object TermuxScriptPolicy {
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val MIN_TIMEOUT_MS = 1_000L
    const val MAX_TIMEOUT_MS = 120_000L
    const val MAX_OUTPUT_BYTES = 32 * 1024
    const val MAX_STDIN_BYTES = 32 * 1024
    const val MAX_ARGUMENT_BYTES = 4 * 1024
    const val MAX_ARGUMENT_TOTAL_BYTES = 32 * 1024
    const val MAX_ARGUMENTS = 128
    const val MAX_PATH_BYTES = 4 * 1024
    const val MAX_CAPTURE_PREFIX_LENGTH = 64
    const val SU_EXECUTABLE = "\$PREFIX/bin/su"

    private val capturePrefixRegex = Regex("^%?[A-Za-z][A-Za-z0-9_]{0,62}$")

    fun prepare(invocation: TermuxScriptInvocation): TermuxPreparationResult {
        val executable = normalizeExecutable(invocation.executable)
            ?: return TermuxPreparationResult.Invalid(
                "Executable must be inside ${TermuxScriptBackend.SCRIPT_DIRECTORY} without traversal or control characters",
            )
        val arguments = parseArguments(invocation.argumentText)
            ?: return TermuxPreparationResult.Invalid("Arguments are malformed or exceed the bounded argument limits")
        val workingDirectory = invocation.workingDirectory?.trim()?.ifBlank { null }
        if (workingDirectory != null && !isBoundedPath(workingDirectory)) {
            return TermuxPreparationResult.Invalid("Working directory is malformed or exceeds the path limit")
        }
        val stdin = invocation.stdin?.takeIf { it.isNotEmpty() }
        if (stdin != null && utf8Size(stdin) > MAX_STDIN_BYTES) {
            return TermuxPreparationResult.Invalid("Standard input exceeds the 32 KB limit")
        }
        if (invocation.timeoutMs !in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
            return TermuxPreparationResult.Invalid("Timeout must be between 1000 and 120000 milliseconds")
        }
        return TermuxPreparationResult.Ready(
            PreparedTermuxScript(
                executable = executable,
                arguments = arguments,
                workingDirectory = workingDirectory,
                stdin = stdin,
                timeoutMs = invocation.timeoutMs,
            ),
        )
    }

    fun normalizeExecutable(path: String): String? {
        val normalized = path.trim()
        val prefix = "${TermuxScriptBackend.SCRIPT_DIRECTORY}/"
        if (!normalized.startsWith(prefix) || normalized.length == prefix.length) return null
        if (!isBoundedPath(normalized)) return null
        val relativeSegments = normalized.removePrefix(prefix).split('/')
        if (relativeSegments.any { it.isBlank() || it == "." || it == ".." }) return null
        return normalized
    }

    fun isValidCapturePrefix(prefix: String): Boolean =
        prefix.length <= MAX_CAPTURE_PREFIX_LENGTH && capturePrefixRegex.matches(prefix)

    fun isOutputWithinLimit(result: TermuxCommandResult, limitBytes: Int = MAX_OUTPUT_BYTES): Boolean =
        result.stdoutOriginalLength in 0..limitBytes &&
            result.stderrOriginalLength in 0..limitBytes &&
            utf8Size(result.stdout) <= limitBytes &&
            utf8Size(result.stderr) <= limitBytes

    fun parseTimeout(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        return if (value.isEmpty()) DEFAULT_TIMEOUT_MS else value.toLongOrNull()
    }

    fun utf8Size(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size

    internal fun isBoundedPath(path: String): Boolean =
        path.isNotBlank() && utf8Size(path) <= MAX_PATH_BYTES && path.none(Char::isISOControl)

    internal fun parseArguments(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return emptyList()
        if (utf8Size(raw) > MAX_ARGUMENT_TOTAL_BYTES) return null

        val arguments = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaping = false
        var tokenStarted = false

        fun finishToken(): Boolean {
            if (!tokenStarted) return true
            val token = current.toString()
            if (utf8Size(token) > MAX_ARGUMENT_BYTES || arguments.size >= MAX_ARGUMENTS) return false
            arguments += token
            current.clear()
            tokenStarted = false
            return true
        }

        raw.forEach { char ->
            if (escaping) {
                current.append(char)
                escaping = false
                tokenStarted = true
            } else if (char == '\\' && quote != '\'') {
                escaping = true
                tokenStarted = true
            } else if ((char == '\'' || char == '"')) {
                if (quote == null) {
                    quote = char
                    tokenStarted = true
                } else if (quote == char) {
                    quote = null
                } else {
                    current.append(char)
                }
            } else if (char.isWhitespace() && quote == null) {
                if (!finishToken()) return null
            } else {
                current.append(char)
                tokenStarted = true
            }
        }
        if (escaping || quote != null || !finishToken()) return null
        return arguments
    }
}

internal sealed interface TermuxPreparationResult {
    data class Ready(val script: PreparedTermuxScript) : TermuxPreparationResult
    data class Invalid(val message: String) : TermuxPreparationResult
}
