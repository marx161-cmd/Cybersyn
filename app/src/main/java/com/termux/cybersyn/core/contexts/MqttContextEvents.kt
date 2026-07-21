package com.termux.cybersyn.core.contexts

import android.content.Context
import com.termux.cybersyn.core.mqtt.MqttBridge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Inbound MQTT messages as EVENT-family context pulses — the return leg of the
 * Cybersyn loop (Bullet 2). A relay node runs an action and publishes the result to
 * `cybersyn/<node>/event`; this bridge turns that into a `matched` event so a phone
 * rule can react (trigger config `event = mqtt`, optional `topic` filter).
 *
 * Transport is [MqttBridge] (bundled native helper, Termux-mosquitto fallback). It
 * subscribes to `cybersyn/+/event` only — the phone publishes commands to
 * `cybersyn/<node>/action`, never to `.../event`, so this cannot self-trigger.
 *
 * The message payload is exposed as the `payload` metadata key (available to the fired
 * task as an event variable); `topic` and `node` are also provided.
 */
object MqttContextEvents {
    const val EVENT_KIND = "mqtt"
    const val SUBSCRIBE_FILTER = "cybersyn/+/event"

    fun events(app: Context): Flow<ContextEvent> =
        MqttBridge.subscribe(app, SUBSCRIBE_FILTER).map { message ->
            ContextEvent(
                type = "event",
                matched = true,
                metadata = mapOf(
                    "event" to EVENT_KIND,
                    "topic" to message.topic,
                    "payload" to message.payload,
                    "node" to nodeFromTopic(message.topic),
                ),
            )
        }

    /** `cybersyn/<node>/event` -> `<node>` (empty if the topic is not in that shape). */
    internal fun nodeFromTopic(topic: String): String {
        val parts = topic.split('/')
        return if (parts.size >= 3 && parts[0] == "cybersyn") parts[1] else ""
    }
}
