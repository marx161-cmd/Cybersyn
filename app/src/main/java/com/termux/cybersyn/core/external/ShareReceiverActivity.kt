package com.termux.cybersyn.core.external

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import com.termux.cybersyn.core.mqtt.MqttBridge
import java.net.Inet4Address
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
        // Bound with no explicit address, so it's already listening on every
        // interface (WiFi LAN and the Tailscale VPN interface both) — the only
        // thing that needs deciding is which address comrade should try first.
        val server = ServerSocket(0)
        server.reuseAddress = true
        val port = server.localPort

        val tailscaleIp = "100.69.13.12"
        val lanIp = currentWifiIpv4(this)
        // LAN first (when on WiFi) — comrade tries it with a short timeout before
        // falling back to Tailscale, same convention as the relay's own offers.
        val addrs = listOfNotNull(lanIp?.let { "$it:$port" }, "$tailscaleIp:$port").joinToString(",")
        MqttBridge.publish(
            this,
            "cybersyn/comrade/action",
            "file:receive:$addrs $name",
        )

        server.soTimeout = 30_000

        try {
            val client = server.accept()
            contentResolver.openInputStream(uri)?.use { input ->
                client.getOutputStream().use { output ->
                    val buf = ByteArray(65536)
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

    /** This device's current WiFi IPv4 address, or null if not on WiFi (e.g. mobile data). */
    private fun currentWifiIpv4(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val linkProperties = cm.getLinkProperties(network) ?: continue
            for (linkAddress in linkProperties.linkAddresses) {
                val addr = linkAddress.address
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    return addr.hostAddress
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "ShareReceiver"
    }
}
