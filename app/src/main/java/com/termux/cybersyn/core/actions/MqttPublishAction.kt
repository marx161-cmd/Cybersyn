package com.termux.cybersyn.core.actions

import com.termux.cybersyn.core.engine.Action
import com.termux.cybersyn.core.engine.ActionCategory
import com.termux.cybersyn.core.engine.ActionContext
import com.termux.cybersyn.core.engine.ActionResult
import com.termux.cybersyn.core.mqtt.MqttBridge

/**
 * Publish a message to an MQTT broker — the first-class Cybersyn transport action.
 *
 * A trigger fires, this publishes a command onto `cybersyn/<node>/action`, and the Rust
 * relay on comrade/comintern executes it (see Fedora_src/cybersyn-relay). Transport is
 * [MqttBridge] — a bundled native rumqttc helper (Termux-mosquitto fallback) holding one
 * persistent connection, so repeated/streamed publishes pay no per-message handshake.
 * Trust boundary is the Tailscale mesh; there is deliberately no auth layer (RECON §1.6).
 *
 * Args:
 *   - "broker":  host or IP (default: comrade's Tailscale IP 100.108.8.60)
 *   - "port":    TCP port (default: 1883)
 *   - "topic":   topic to publish to (required, e.g. "cybersyn/comrade/action")
 *   - "message": payload (default: empty, e.g. "notify:hi" or "ping")
 */
class MqttPublishAction : Action {
    override val id = "mqtt.publish"
    override val category = ActionCategory.NET

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val broker = (args["broker"] ?: MqttBridge.DEFAULT_BROKER).trim()
        if (broker.isEmpty() || !HOST_PATTERN.matches(broker)) {
            return ActionResult.Failure("invalid broker: $broker")
        }
        val topic = args["topic"]?.trim().orEmpty()
        if (topic.isEmpty()) return ActionResult.Failure("missing topic")
        // The stdio line protocol to the helper is tab/newline delimited.
        if (topic.contains('\t') || topic.contains('\n')) {
            return ActionResult.Failure("topic must not contain tab or newline")
        }
        val message = args["message"].orEmpty()
        if (message.contains('\n')) return ActionResult.Failure("message must not contain a newline")
        val port = (args["port"]?.toIntOrNull() ?: MqttBridge.DEFAULT_PORT).coerceIn(1, 65535)

        return if (MqttBridge.publish(ctx.app, topic, message, broker, port)) {
            ctx.logger("MQTT published to $broker:$port topic=$topic (${message.length} chars)")
            ActionResult.Success
        } else {
            ActionResult.Failure("MQTT publish failed (broker $broker:$port)")
        }
    }

    companion object {
        private val HOST_PATTERN = Regex("^[A-Za-z0-9.:-]{1,253}$")
    }
}
