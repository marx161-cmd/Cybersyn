package com.termux.cybersyn.core.contexts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import com.termux.cybersyn.core.mqtt.MqttBridge
import com.termux.cybersyn.core.transfer.AlbumArtBus
import com.termux.cybersyn.core.transfer.CybersynFileTransfer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Consumes `cybersyn/file/offer` messages the relay publishes for both `albumart:<hash>`
 * and `file:serve:<path>` requests. Album art is downloaded to memory and handed to
 * [AlbumArtBus] for MediaContextSource to attach to its MediaSession; general files land in
 * the public Downloads/cybersyn folder with a notification, mirroring the relay's own
 * ~/Downloads/cybersyn convention on the comrade side.
 */
class FileOfferContextSource : ContextSource {

    override val type = "file_offer"

    override fun events(app: Context): Flow<ContextEvent> = channelFlow {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val job = scope.launch {
            MqttBridge.subscribe(app, "cybersyn/file/offer").collect { message ->
                try {
                    handleOffer(app, JSONObject(message.payload))
                } catch (_: Exception) { }
            }
        }

        awaitClose { job.cancel() }
    }

    private suspend fun handleOffer(app: Context, offer: JSONObject) {
        val ip = offer.optString("ip", "")
        val lanIp = offer.optString("lan_ip", "").takeIf { it.isNotEmpty() }
        val port = offer.optInt("port", -1)
        val name = offer.optString("name", "file")
        val type = offer.optString("type", "file")
        if (ip.isEmpty() || port <= 0) return

        // LAN address first (when the relay could offer one) — Tailscale is the
        // guaranteed fallback. Order matters: connect() tries these in sequence.
        val candidates = listOfNotNull(lanIp?.let { it to port }, ip to port)

        if (type == "albumart") {
            val hash = name.substringBeforeLast(".")
            val bytes = CybersynFileTransfer.downloadToBytes(candidates) ?: return
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            AlbumArtBus.emit(hash, bitmap)
            return
        }

        val tmp = File(app.cacheDir, "cybersyn_incoming_${System.nanoTime()}_$name")
        val size = CybersynFileTransfer.downloadToFile(candidates, tmp) ?: return
        if (saveToDownloads(app, tmp, name)) {
            notifyFileReceived(app, name, size)
        }
        tmp.delete()
    }

    private fun saveToDownloads(app: Context, src: File, name: String): Boolean {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/cybersyn")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun notifyFileReceived(app: Context, name: String, size: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File transfers",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            app.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(app, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(app)
        }
        val notification = builder
            .setContentTitle("File received from comrade")
            .setContentText("$name (${size / 1024} KB) saved to Downloads/cybersyn")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        app.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "cybersyn_files"
        private const val NOTIFICATION_ID = 9002
    }
}
