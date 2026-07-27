package com.termux.cybersyn.core.external

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import com.termux.cybersyn.core.mqtt.MqttBridge
import java.net.ServerSocket

class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
        finish()
    }

    private fun handleShareIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return

        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            ?: return

        val name = resolveFileName(uri)
        Thread {
            serveFile(uri, name)
        }.also { it.name = "cybersyn-share-sender" }.start()
    }

    private fun serveFile(uri: Uri, name: String) {
        val server = ServerSocket(0)
        server.reuseAddress = true
        val port = server.localPort

        val ip = "100.69.13.12"
        MqttBridge.publish(
            this,
            "cybersyn/comrade/action",
            "file:receive:$ip:$port $name",
        )

        server.soTimeout = 30_000

        try {
            val client = server.accept()
            contentResolver.openInputStream(uri)?.use { input ->
                client.getOutputStream().use { output ->
                    val buf = ByteArray(4096)
                    var bytesRead: Int
                    while (input.read(buf).also { bytesRead = it } != -1) {
                        output.write(buf, 0, bytesRead)
                    }
                    output.flush()
                }
            }
            client.close()
        } catch (_: Exception) {
        } finally {
            try { server.close() } catch (_: Exception) {}
        }
    }

    private fun resolveFileName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = cursor.getString(idx)
                    if (name != null && name.isNotEmpty()) return name
                }
            }
        }

        val lastSegment = uri.lastPathSegment ?: "shared_file"
        return lastSegment.substringAfterLast('/')
    }

    companion object {
        private const val TAG = "ShareReceiver"
    }
}
