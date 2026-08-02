package com.termux.cybersyn.core.actions

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.termux.cybersyn.core.engine.Action
import com.termux.cybersyn.core.engine.ActionCategory
import com.termux.cybersyn.core.engine.ActionContext
import com.termux.cybersyn.core.engine.ActionResult
import com.termux.cybersyn.core.scripting.TermuxCommandBroker
import com.termux.cybersyn.core.scripting.TermuxCommandRequest

/**
 * Vibrate device.
 *
 * Args:
 *   - "millis": duration in milliseconds
 */
class VibrateAction : Action {
    override val id = "vibrate"
    override val category = ActionCategory.NOTIFICATION

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val rawMillis = args["millis"] ?: return ActionResult.Failure("missing millis")
        val millis = rawMillis.toLongOrNull() ?: return ActionResult.Failure("invalid millis: $rawMillis")
        if (millis !in MIN_VIBRATE_MS..MAX_VIBRATE_MS) {
            return ActionResult.Failure("vibrate duration must be between $MIN_VIBRATE_MS and $MAX_VIBRATE_MS ms")
        }
        return try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                ctx.app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)?.let {
                    (it as VibratorManager).defaultVibrator
                }
            } else {
                @Suppress("DEPRECATION")
                ctx.app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return ActionResult.Failure("vibrator not available")

            vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
            ctx.logger("Vibrate ${millis}ms")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("vibrate failed: ${e.message}")
        }
    }

    companion object {
        private const val MIN_VIBRATE_MS = 1L
        private const val MAX_VIBRATE_MS = 10_000L
    }
}

/**
 * Reboot device.
 *
 * Args:
 *   - "mode": "recovery", "bootloader", or blank for normal reboot
 */
class RebootAction : Action {
    override val id = "reboot"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val mode = args["mode"]?.ifBlank { null }
        ctx.logger("Reboot${mode?.let { " ($it)" } ?: ""}")
        val command = when (mode?.lowercase()) {
            null -> "svc power reboot"
            "recovery" -> "svc power reboot recovery"
            "bootloader" -> "svc power reboot bootloader"
            else -> return ActionResult.Failure("invalid reboot mode: $mode")
        }
        val result = runRootCommand(ctx, command)
        return if (result.exitCode == 0) {
            ActionResult.Success
        } else {
            ActionResult.Failure("Reboot root command failed: ${result.stderr.ifBlank { result.stdout }}")
        }
    }
}

/**
 * Lock device (secure lock).
 */
class LockDeviceAction : Action {
    override val id = "lock"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Lock device")
        return ActionResult.Failure("Device lock requires a configured DevicePolicyManager admin")
    }
}

/**
 * Send a key event — the same mechanism as pressing a physical key.
 * Shows the volume slider with KEYCODE_VOLUME_UP, sleeps with KEYCODE_SLEEP, etc.
 *
 * Args:
 *   - "code": keycode name (e.g. KEYCODE_VOLUME_UP, KEYCODE_VOLUME_DOWN)
 */
class KeyEventAction : Action {
    override val id = "key.send"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val code = args["code"] ?: return ActionResult.Failure("missing code (e.g. KEYCODE_VOLUME_UP)")
        ctx.logger("key.send $code")
        val result = runRootCommand(ctx, "input keyevent $code")
        return if (result.exitCode == 0) {
            ActionResult.Success
        } else {
            ActionResult.Failure("key.send failed: ${result.stderr.ifBlank { result.stdout }}")
        }
    }
}

/**
 * Turn off screen.
 */
class ScreenOffAction : Action {
    override val id = "screen.off"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Screen off")
        val result = runRootCommand(ctx, "input keyevent KEYCODE_SLEEP")
        return if (result.exitCode == 0) {
            ActionResult.Success
        } else {
            ActionResult.Failure("Screen-off root command failed: ${result.stderr.ifBlank { result.stdout }}")
        }
    }
}

/**
 * Turn on screen (wake device).
 *
 * Args:
 *   - "duration_sec": how long to keep screen on
 */
class WakeAction : Action {
    override val id = "wake"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val dur = args["duration_sec"]?.toLongOrNull() ?: 10L
        ctx.logger("Wake (${dur}s)")
        val result = runRootCommand(ctx, "input keyevent KEYCODE_WAKEUP")
        return if (result.exitCode == 0) {
            ActionResult.Success
        } else {
            ActionResult.Failure("Wake root command failed: ${result.stderr.ifBlank { result.stdout }}")
        }
    }
}

private suspend fun runRootCommand(ctx: ActionContext, command: String) =
    TermuxCommandBroker.execute(
        ctx.app,
        TermuxCommandRequest(
            executable = "\$PREFIX/bin/sh",
            arguments = listOf("-c", command),
            timeoutMs = 30_000L,
            useRoot = true,
        ),
    )

/**
 * Log a message to the run log (visible in history).
 *
 * Args:
 *   - "message": text to log
 */
class LogAction : Action {
    override val id = "log"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val message = args["message"] ?: ""
        ctx.logger(message)
        return ActionResult.Success
    }
}
