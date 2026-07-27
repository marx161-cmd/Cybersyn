package com.termux.cybersyn.core.actions

import android.content.Context
import com.termux.cybersyn.core.engine.Action
import com.termux.cybersyn.core.engine.ActionCategory
import com.termux.cybersyn.core.engine.ActionContext
import com.termux.cybersyn.core.engine.ActionResult
import com.termux.cybersyn.core.mqtt.MqttBridge

/**
 * Media control actions that publish MPRIS commands to the Cybersyn relay.
 * The relay dispatches these to playerctl on the host.
 *
 * Topic: cybersyn/comrade/action
 * Format: mpris.<subcommand> or mpris.<subcommand>:<arg>
 */
class MediaPlayAction : Action {
    override val id = "media.play"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val player = args["player"]?.trim().orEmpty()
        return publishMprisCommand(ctx.app, "mpris.play${if (player.isNotEmpty()) ":$player" else ""}")
    }
}

class MediaPauseAction : Action {
    override val id = "media.pause"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val player = args["player"]?.trim().orEmpty()
        return publishMprisCommand(ctx.app, "mpris.pause${if (player.isNotEmpty()) ":$player" else ""}")
    }
}

class MediaPlayPauseAction : Action {
    override val id = "media.play_pause"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val player = args["player"]?.trim().orEmpty()
        return publishMprisCommand(ctx.app, "mpris.play-pause${if (player.isNotEmpty()) ":$player" else ""}")
    }
}

class MediaNextAction : Action {
    override val id = "media.next"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val player = args["player"]?.trim().orEmpty()
        return publishMprisCommand(ctx.app, "mpris.next${if (player.isNotEmpty()) ":$player" else ""}")
    }
}

class MediaPrevAction : Action {
    override val id = "media.prev"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val player = args["player"]?.trim().orEmpty()
        return publishMprisCommand(ctx.app, "mpris.prev${if (player.isNotEmpty()) ":$player" else ""}")
    }
}

class MediaStopAction : Action {
    override val id = "media.stop"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val player = args["player"]?.trim().orEmpty()
        return publishMprisCommand(ctx.app, "mpris.stop${if (player.isNotEmpty()) ":$player" else ""}")
    }
}

class MediaSeekAction : Action {
    override val id = "media.seek"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val offset = args["offset_secs"]?.trim().orEmpty()
        if (offset.isEmpty()) return ActionResult.Failure("media.seek: missing offset_secs")
        val player = args["player"]?.trim().orEmpty()
        return publishMprisCommand(ctx.app, "mpris.seek:$offset${if (player.isNotEmpty()) " $player" else ""}")
    }
}

class MediaSetPositionAction : Action {
    override val id = "media.position"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val pos = args["position_secs"]?.trim().orEmpty()
        if (pos.isEmpty()) return ActionResult.Failure("media.position: missing position_secs")
        val player = args["player"]?.trim().orEmpty()
        return publishMprisCommand(ctx.app, "mpris.position:$pos${if (player.isNotEmpty()) " $player" else ""}")
    }
}

class MediaSetVolumeAction : Action {
    override val id = "media.volume"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val vol = args["volume_pct"]?.trim().orEmpty()
        if (vol.isEmpty()) return ActionResult.Failure("media.volume: missing volume_pct (0-100)")
        val player = args["player"]?.trim().orEmpty()
        return publishMprisCommand(ctx.app, "mpris.volume:$vol${if (player.isNotEmpty()) " $player" else ""}")
    }
}

class MediaSetLoopAction : Action {
    override val id = "media.loop"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val mode = args["mode"]?.trim() ?: "None"
        val player = args["player"]?.trim().orEmpty()
        return publishMprisCommand(ctx.app, "mpris.loop:$mode${if (player.isNotEmpty()) " $player" else ""}")
    }
}

class MediaSetShuffleAction : Action {
    override val id = "media.shuffle"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val on = args["shuffle_on"]?.trim()?.lowercase() ?: "off"
        val player = args["player"]?.trim().orEmpty()
        return publishMprisCommand(ctx.app, "mpris.shuffle:${if (on == "true" || on == "on") "On" else "Off"}${if (player.isNotEmpty()) " $player" else ""}")
    }
}

class MediaRequestAlbumArtAction : Action {
    override val id = "media.request_art"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val hash = args["hash"]?.trim().orEmpty()
        if (hash.isEmpty()) return ActionResult.Failure("media.request_art: missing hash")
        return publishMprisCommand(ctx.app, "albumart:$hash")
    }
}

// --- Clipboard ---

class ClipboardPushAction : Action {
    override val id = "clipboard.push"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val text = args["text"]?.trim() ?: ""
        val payload = """{"content":${text.toJsonString()}}"""
        return if (MqttBridge.publish(ctx.app, "cybersyn/clipboard/pixel", payload)) {
            ctx.logger("clipboard pushed to comrade: ${text.length} chars")
            ActionResult.Success
        } else {
            ActionResult.Failure("clipboard push failed")
        }
    }
}

class ClipboardPullAction : Action {
    override val id = "clipboard.pull"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        return publishMprisCommand(ctx.app, "clipboard:get")
    }
}

// --- File transfer ---

class FileServeAction : Action {
    override val id = "file.serve"
    override val category = ActionCategory.FILE

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val path = args["path"]?.trim().orEmpty()
        if (path.isEmpty()) return ActionResult.Failure("file.serve: missing path")
        return publishMprisCommand(ctx.app, "file:serve:$path")
    }
}

class FileReceiveAction : Action {
    override val id = "file.receive"
    override val category = ActionCategory.FILE

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val addr = args["addr"]?.trim().orEmpty()
        val name = args["name"]?.trim().orEmpty()
        if (addr.isEmpty() || name.isEmpty()) return ActionResult.Failure("file.receive: missing addr or name")
        return publishMprisCommand(ctx.app, "file:receive:$addr $name")
    }
}

// --- helpers ---

private const val ACTION_TOPIC = "cybersyn/comrade/action"

private fun publishMprisCommand(app: Context, command: String): ActionResult {
    return if (MqttBridge.publish(app, ACTION_TOPIC, command)) {
        ActionResult.Success
    } else {
        ActionResult.Failure("MQTT publish failed for: $command")
    }
}

private fun String.toJsonString(): String {
    return this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}
