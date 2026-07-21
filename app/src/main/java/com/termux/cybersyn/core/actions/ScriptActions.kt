package com.termux.cybersyn.core.actions

import com.termux.cybersyn.core.engine.Action
import com.termux.cybersyn.core.engine.ActionCategory
import com.termux.cybersyn.core.engine.ActionContext
import com.termux.cybersyn.core.engine.ActionResult
import com.termux.cybersyn.core.logging.AppLogger
import com.termux.cybersyn.core.scripting.TermuxCommandBroker
import com.termux.cybersyn.core.scripting.TermuxScriptAllowlistStore
import com.termux.cybersyn.core.scripting.TermuxScriptBackend
import com.termux.cybersyn.core.scripting.TermuxScriptCoordinator
import com.termux.cybersyn.core.scripting.TermuxScriptExecutionResult
import com.termux.cybersyn.core.scripting.TermuxScriptInvocation
import com.termux.cybersyn.core.scripting.TermuxScriptPolicy
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

        val invocation = TermuxScriptInvocation(
            executable = executable,
            argumentText = args["arguments"],
            workingDirectory = args["workingDirectory"],
            stdin = args["stdin"],
            timeoutMs = timeoutMs,
        )
        val readiness = TermuxScriptBackend.isDispatchReady(ctx.app)
        val coordinator = TermuxScriptCoordinator()

        return try {
            val execution = coordinator.execute(
                ready = readiness,
                invocation = invocation,
                approvedHashFor = { normalizedExecutable ->
                    TermuxScriptAllowlistStore(ctx.app).expectedHash(normalizedExecutable)
                },
                commandRunner = { request -> TermuxCommandBroker.execute(ctx.app, request) },
            )
            when (execution) {
                is TermuxScriptExecutionResult.Rejected -> ActionResult.Failure(execution.message)
                is TermuxScriptExecutionResult.Completed -> completeExecution(ctx, capturePrefix, execution)
            }
        } catch (_: TimeoutCancellationException) {
            ctx.logger("Termux script timed out; stdout=<redacted> stderr=<redacted>")
            ActionResult.Failure("Termux command timed out")
        } catch (_: SecurityException) {
            ActionResult.Failure("Termux RUN_COMMAND permission was denied")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLogger.error("TermuxScriptAction", "Dispatch failed (${error.javaClass.simpleName}); output redacted")
            ActionResult.Failure("Termux dispatch failed (${error.javaClass.simpleName})")
        }
    }

    internal fun completeExecution(
        ctx: ActionContext,
        capturePrefix: String?,
        execution: TermuxScriptExecutionResult.Completed,
    ): ActionResult {
        val result = execution.command
        if (capturePrefix != null) {
            ctx.variables.set("${capturePrefix}_stdout", result.stdout)
            ctx.variables.set("${capturePrefix}_stderr", result.stderr)
            ctx.variables.set("${capturePrefix}_exit_code", result.exitCode.toString())
            ctx.variables.set("${capturePrefix}_stdout_length", result.stdoutOriginalLength.toString())
            ctx.variables.set("${capturePrefix}_stderr_length", result.stderrOriginalLength.toString())
        }
        ctx.logger(
            "Termux script completed: hash=${execution.approvedHash}; exit=${result.exitCode}; " +
                "stdout=<redacted:${TermuxScriptPolicy.utf8Size(result.stdout)}B>; " +
                "stderr=<redacted:${TermuxScriptPolicy.utf8Size(result.stderr)}B>",
        )
        return when {
            result.errorCode != 0 -> ActionResult.Failure("Termux could not execute the approved script")
            result.exitCode != 0 -> ActionResult.Failure("Termux script exited with code ${result.exitCode}")
            else -> ActionResult.Success
        }
    }
}
