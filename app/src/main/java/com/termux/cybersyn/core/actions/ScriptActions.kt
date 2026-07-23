package com.termux.cybersyn.core.actions

import com.termux.cybersyn.core.engine.Action
import com.termux.cybersyn.core.engine.ActionCategory
import com.termux.cybersyn.core.engine.ActionContext
import com.termux.cybersyn.core.engine.ActionResult
import com.termux.cybersyn.core.scripting.TermuxCommandBroker
import com.termux.cybersyn.core.scripting.TermuxCommandRequest
import com.termux.cybersyn.core.scripting.TermuxCommandResult
import com.termux.cybersyn.core.scripting.TermuxScriptBackend
import com.termux.cybersyn.core.scripting.TermuxScriptInvocation
import com.termux.cybersyn.core.scripting.TermuxScriptPolicy
import com.termux.cybersyn.core.scripting.TermuxPreparationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

class TermuxScriptAction : Action {
    override val id = TermuxScriptBackend.ACTION_ID
    override val category = ActionCategory.PLUGIN

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val executable = args["executable"]?.trim()
            ?: return ActionResult.Failure("Missing 'executable' argument")
        if (executable.isBlank()) return ActionResult.Failure("Executable path is blank")

        val timeoutMs = TermuxScriptPolicy.parseTimeout(args["timeoutMs"])
            ?: return ActionResult.Failure("Timeout must be a whole number of milliseconds")
        val capturePrefix = args["capturePrefix"]?.trim()?.ifBlank { null }
        if (capturePrefix != null && !TermuxScriptPolicy.isValidCapturePrefix(capturePrefix)) {
            return ActionResult.Failure("Output variable prefix is invalid")
        }
        val useRoot = args["useRoot"]?.trim()?.lowercase() in setOf("true", "1", "yes")

        val invocation = TermuxScriptInvocation(
            executable = executable,
            argumentText = args["arguments"],
            workingDirectory = args["workingDirectory"],
            stdin = args["stdin"],
            timeoutMs = timeoutMs,
            useRoot = useRoot,
        )
        val readiness = TermuxScriptBackend.isDispatchReady(ctx.app)
        if (!readiness) {
            return ActionResult.Failure("Termux RUN_COMMAND permission is not ready")
        }

        val script = when (val prepared = TermuxScriptPolicy.prepare(invocation)) {
            is TermuxPreparationResult.Invalid -> return ActionResult.Failure(prepared.message)
            is TermuxPreparationResult.Ready -> prepared.script
        }

        val request = TermuxCommandRequest(
            executable = script.executable,
            arguments = script.arguments,
            workingDirectory = script.workingDirectory,
            stdin = script.stdin,
            timeoutMs = script.timeoutMs,
            useRoot = useRoot,
        )

        return try {
            val result = TermuxCommandBroker.execute(ctx.app, request)
            if (!TermuxScriptPolicy.isOutputWithinLimit(result)) {
                return ActionResult.Failure("Termux output exceeds the 32 KB per-stream capture limit")
            }
            completeExecution(ctx, capturePrefix, result)
        } catch (_: TimeoutCancellationException) {
            ctx.logger("Termux script timed out")
            ActionResult.Failure("Termux command timed out")
        } catch (_: SecurityException) {
            ActionResult.Failure("Termux RUN_COMMAND permission was denied")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ActionResult.Failure("Termux dispatch failed (${error.javaClass.simpleName})")
        }
    }

    internal fun completeExecution(
        ctx: ActionContext,
        capturePrefix: String?,
        result: TermuxCommandResult,
    ): ActionResult {
        if (capturePrefix != null) {
            ctx.variables.set("${capturePrefix}_stdout", result.stdout)
            ctx.variables.set("${capturePrefix}_stderr", result.stderr)
            ctx.variables.set("${capturePrefix}_exit_code", result.exitCode.toString())
            ctx.variables.set("${capturePrefix}_stdout_length", result.stdoutOriginalLength.toString())
            ctx.variables.set("${capturePrefix}_stderr_length", result.stderrOriginalLength.toString())
        }
        return when {
            result.errorCode != 0 -> ActionResult.Failure("Termux could not execute the script")
            result.exitCode != 0 -> ActionResult.Failure("Termux script exited with code ${result.exitCode}")
            else -> ActionResult.Success
        }
    }
}
