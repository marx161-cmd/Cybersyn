package com.termux.cybersyn.core.contexts

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import com.termux.cybersyn.core.mqtt.MqttBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import org.json.JSONObject

class ClipboardContextSource : ContextSource {

    override val type = "clipboard"

    override fun events(app: Context): Flow<ContextEvent> = channelFlow {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var lastReceivedFromRemote: String? = null

        // Subscribe to incoming clipboard from comrade
        val incomingJob = scope.launch {
            MqttBridge.subscribe(app, "cybersyn/clipboard/comrade").collect { message ->
                try {
                    val content = JSONObject(message.payload).optString("content", "")
                    if (content.isEmpty() || content == lastReceivedFromRemote) return@collect
                    lastReceivedFromRemote = content

                    scope.launch(Dispatchers.Main) {
                        applyClipboard(app, content)
                    }
                } catch (_: Exception) { }
            }
        }

        // Monitor Android clipboard changes, publish to comrade
        val outboundJob = scope.launch(Dispatchers.Main) {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@launch
            var lastPublishedContent: String? = null

            cm.addPrimaryClipChangedListener {
                val clip = cm.primaryClip ?: return@addPrimaryClipChangedListener
                if (clip.itemCount == 0) return@addPrimaryClipChangedListener
                val item = clip.getItemAt(0)
                val text = item.coerceToText(app).toString()
                if (text.isEmpty() || text == lastPublishedContent || text == lastReceivedFromRemote) return@addPrimaryClipChangedListener

                lastPublishedContent = text
                val payload = """{"content":"${text.escapeJson()}"}"""
                MqttBridge.publish(app, "cybersyn/clipboard/pixel", payload)
            }
        }

        awaitClose {
            incomingJob.cancel()
            outboundJob.cancel()
        }
    }

    private fun applyClipboard(app: Context, content: String) {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText("Cybersyn", content)
        cm.setPrimaryClip(clip)
    }

    private fun String.escapeJson(): String {
        return this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }
}
