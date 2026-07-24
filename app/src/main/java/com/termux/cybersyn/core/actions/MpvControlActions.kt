package com.termux.cybersyn.core.actions

import android.content.Intent
import com.termux.cybersyn.core.engine.Action
import com.termux.cybersyn.core.engine.ActionCategory
import com.termux.cybersyn.core.engine.ActionContext
import com.termux.cybersyn.core.engine.ActionResult

private const val MPV_PACKAGE = "com.termux.mpv"

private fun ActionContext.sendMpvAction(action: String): ActionResult {
    return try {
        val intent = Intent(action).apply { `package` = MPV_PACKAGE }
        app.sendBroadcast(intent)
        ActionResult.Success
    } catch (e: Exception) {
        ActionResult.Failure("mpv broadcast failed: ${e.message}", e)
    }
}

class MpvEnterFreeformAction : Action {
    override val id = "mpv.enter_freeform"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult =
        ctx.sendMpvAction("com.termux.mpv.action.ENTER_FREEFORM")
}

class MpvEnterPipAction : Action {
    override val id = "mpv.enter_pip"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult =
        ctx.sendMpvAction("com.termux.mpv.action.ENTER_PIP")
}

class MpvExitFullscreenAction : Action {
    override val id = "mpv.exit_fullscreen"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult =
        ctx.sendMpvAction("com.termux.mpv.action.EXIT_FULLSCREEN")
}

class MpvTogglePlayAction : Action {
    override val id = "mpv.toggle_play"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult =
        ctx.sendMpvAction("com.termux.mpv.action.TOGGLE_PLAY")
}

class MpvTogglePauseAction : Action {
    override val id = "mpv.toggle_pause"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult =
        ctx.sendMpvAction("com.termux.mpv.action.TOGGLE_PAUSE")
}

class MpvAspectWidescreenAction : Action {
    override val id = "mpv.aspect_widescreen"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult =
        ctx.sendMpvAction("com.termux.mpv.action.ASPECT_WIDESCREEN")
}

class MpvAspectCinemaAction : Action {
    override val id = "mpv.aspect_cinema"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult =
        ctx.sendMpvAction("com.termux.mpv.action.ASPECT_CINEMA")
}

class MpvAspectSquareAction : Action {
    override val id = "mpv.aspect_square"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult =
        ctx.sendMpvAction("com.termux.mpv.action.ASPECT_SQUARE")
}
