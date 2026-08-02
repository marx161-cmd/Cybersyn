package com.termux.cybersyn.core.external

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.termux.cybersyn.core.mqtt.MqttBridge
import com.termux.cybersyn.core.transfer.TermuxExec
import com.termux.cybersyn.core.transfer.TransferProgressNotifier
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import com.termux.cybersyn.core.logging.AppLogger

class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
        finish()
    }

    private fun handleShareIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val streamUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (streamUri != null) {
                    val name = resolveFileName(streamUri)
                    val size = resolveFileSize(streamUri)
                    Thread {
                        try {
                            contentResolver.openInputStream(streamUri).use { input ->
                                if (input == null) {
                                    AppLogger.warn(TAG, "openInputStream returned null for $streamUri")
                                    notifyShareFailed(name)
                                } else {
                                    serveStream(name, input, size)
                                }
                            }
                        } catch (e: Exception) {
                            AppLogger.warn(TAG, "ACTION_SEND failed for $streamUri", e)
                        }
                    }.also { it.name = "cybersyn-share-sender" }.start()
                    return
                }

                // Text share of a raw filesystem path — e.g. a file manager's "copy path"
                // pasted into a share. Preferable to a stream/URI share for folders: this app
                // is UID 1000/system, so a real path gets real recursive File+tar access with
                // no content:// layer in between, sidestepping providers (MiXplorer's included)
                // that hand out an unreadable directory fd instead of actually zipping it.
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
                val localFile = text?.let(::resolveLocalPath)
                AppLogger.info(TAG, "ACTION_SEND text share: text=$text resolvedLocalFile=$localFile")
                if (localFile != null) {
                    Thread {
                        serveLocalPath(localFile)
                    }.also { it.name = "cybersyn-share-sender" }.start()
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: return
                if (uris.isEmpty()) return
                Thread {
                    serveMultiple(uris)
                }.also { it.name = "cybersyn-share-sender" }.start()
            }
        }
    }

    /**
     * Multiple shared items don't fit the single-file protocol, so bundle them into one
     * uncompressed tar first (via Termux's `tar`, same UID as this app) and serve that —
     * the relay auto-extracts any `.tar`-suffixed upload on arrival. Deliberately no
     * compression here: it would run on the phone's weaker, thermally-constrained CPU as
     * the sender, competing with the WiFi throughput this path is tuned to saturate.
     */
    private fun serveMultiple(uris: List<Uri>) {
        val stagingDir = File(cacheDir, "cybersyn_share_${System.nanoTime()}")
        stagingDir.mkdirs()
        val tarFile = File(cacheDir, "cybersyn_share_${System.nanoTime()}.tar")
        try {
            val usedNames = HashSet<String>()
            for (uri in uris) {
                val name = uniqueName(resolveFileName(uri), usedNames)
                contentResolver.openInputStream(uri)?.use { input ->
                    File(stagingDir, name).outputStream().use { input.copyTo(it) }
                }
            }

            val proc = TermuxExec.exec(listOf("bin/tar", "-cf", tarFile.absolutePath, "-C", stagingDir.absolutePath, "."))
            if (proc.waitFor() != 0) {
                AppLogger.warn(TAG, "tar failed for $uris")
                notifyShareFailed("shared_${uris.size}_files")
                return
            }

            val archiveName = "shared_${uris.size}_files.tar"
            tarFile.inputStream().use { serveStream(archiveName, it, tarFile.length()) }
        } catch (e: Exception) {
            AppLogger.warn(TAG, "serveMultiple failed for $uris", e)
        } finally {
            stagingDir.deleteRecursively()
            tarFile.delete()
        }
    }

    private fun uniqueName(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (!used.add("$base ($n)$ext")) n++
        return "$base ($n)$ext"
    }

    /** A shared text's raw path if it resolves to something that actually exists on disk. */
    private fun resolveLocalPath(text: String): File? {
        val path = when {
            text.startsWith("file://") -> Uri.parse(text).path
            text.startsWith("/") -> text
            else -> null
        } ?: return null
        val file = File(path)
        return if (file.exists()) file else null
    }

    /**
     * Serves a real on-disk path directly — no ContentResolver/URI layer, so this works for
     * arbitrarily nested folders (recursive `tar`) with none of [serveStream]'s EISDIR risk.
     * A single file needs no wrapping and streams as-is; a directory is tarred flat (`-C dir .`,
     * matching [serveMultiple]'s convention) since the relay already extracts a `.tar` upload
     * into a directory named after it — wrapping the entries in another same-named directory
     * here would double it up.
     */
    private fun serveLocalPath(file: File) {
        if (!file.isDirectory) {
            try {
                file.inputStream().use { serveStream(file.name, it, file.length()) }
            } catch (e: Exception) {
                AppLogger.warn(TAG, "serveLocalPath failed for $file", e)
            }
            return
        }

        val tarFile = File(cacheDir, "cybersyn_share_${System.nanoTime()}.tar")
        try {
            val proc = TermuxExec.exec(listOf("bin/tar", "-cf", tarFile.absolutePath, "-C", file.absolutePath, "."))
            if (proc.waitFor() != 0) {
                AppLogger.warn(TAG, "tar failed for $file")
                notifyShareFailed(file.name)
                return
            }
            tarFile.inputStream().use { serveStream("${file.name}.tar", it, tarFile.length()) }
        } catch (e: Exception) {
            AppLogger.warn(TAG, "serveLocalPath (dir) failed for $file", e)
        } finally {
            tarFile.delete()
        }
    }

    /** [totalSize] enables a determinate progress bar; pass -1 (or leave default) when unknown. */
    private fun serveStream(name: String, input: InputStream, totalSize: Long = -1) {
        val notifier = TransferProgressNotifier(this, "Sending $name")
        notifier.start(indeterminate = totalSize <= 0)

        // Some senders (MiXplorer's single-folder "Share" button, at least) hand back a
        // stream that looks fine but throws EISDIR on the first real read — they're pointing
        // at a raw directory fd with no zip/tar wrapping, no bytes to recover. Check that
        // BEFORE publishing an MQTT offer or opening a socket, so a bad share fails
        // immediately with real feedback instead of leaving comrade with a stray empty file.
        val firstByte = try {
            input.read()
        } catch (e: IOException) {
            AppLogger.warn(TAG, "cannot read \"$name\" — sender gave no real bytes (probably tried to share a folder directly)", e)
            notifier.finish(false, "couldn't read \"$name\"")
            notifyShareFailed(name)
            return
        }

        // Bind explicitly to one of the device's own addresses — never 0.0.0.0, matching the
        // relay's deliberate policy (file_transfer.rs documents why). Tailscale is preferred
        // because it's the only interface comrade is guaranteed to reach us on.
        val bindIp = tailscaleIpv4() ?: currentWifiIpv4(this) ?: LOOPBACK
        val server = ServerSocket().also {
            // Must precede bind() to have any effect.
            it.reuseAddress = true
            it.bind(InetSocketAddress(InetAddress.getByName(bindIp), 0))
        }
        val port = server.localPort

        if (bindIp == LOOPBACK) {
            AppLogger.warn(TAG, "No Tailscale or WiFi address; bound loopback, comrade cannot reach this offer")
        }
        // Advertise only what we actually listen on. Offering the LAN address while bound to
        // Tailscale made comrade's LAN-first attempt fail on every single transfer.
        val addrs = "$bindIp:$port"
        MqttBridge.publish(
            this,
            "cybersyn/comrade/action",
            "file:receive:$addrs $name",
        )

        server.soTimeout = 30_000

        try {
            val client = server.accept()
            client.getOutputStream().use { output ->
                var sent = 0L
                if (firstByte != -1) {
                    output.write(firstByte)
                    sent++
                }
                val buf = ByteArray(65536)
                var bytesRead: Int
                while (input.read(buf).also { bytesRead = it } != -1) {
                    output.write(buf, 0, bytesRead)
                    sent += bytesRead
                    notifier.update(sent, totalSize)
                }
                output.flush()
            }
            client.close()
            notifier.finish(true, "sent to comrade")
        } catch (e: Exception) {
            AppLogger.warn(TAG, "serveStream failed for $name", e)
            notifier.finish(false, "transfer failed")
        } finally {
            try { server.close() } catch (_: Exception) {}
        }
    }

    private fun notifyShareFailed(name: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                applicationContext,
                "Couldn't share \"$name\" — if it's a folder, share its filesystem path as text instead (copy path → paste into share)",
                Toast.LENGTH_LONG,
            ).show()
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

    /** -1 if the provider doesn't report a size (falls back to an indeterminate progress bar). */
    private fun resolveFileSize(uri: Uri): Long {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) return cursor.getLong(idx)
            }
        }
        return -1
    }

    /**
     * This device's Tailscale IPv4 address via the same UDP-connect trick the relay uses,
     * or null if it can't be determined.
     *
     * The result is validated against the CGNAT range Tailscale uses (100.64.0.0/10). An
     * unroutable tailnet makes the kernel hand back the wildcard address, and binding that
     * would silently listen on every interface — the exact exposure this bind is meant to
     * prevent.
     */
    private fun tailscaleIpv4(): String? {
        val addr = try {
            DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName(TAILSCALE_PROBE), 9)
                socket.localAddress?.hostAddress
            }
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Tailscale address probe failed", e)
            null
        } ?: return null
        return addr.takeIf { isTailscaleCgnat(it) }
            ?: run { AppLogger.warn(TAG, "Address probe returned non-tailnet address $addr; ignoring"); null }
    }

    /** True for 100.64.0.0/10, the CGNAT block Tailscale assigns from. */
    private fun isTailscaleCgnat(ip: String): Boolean {
        val octets = ip.split('.')
        if (octets.size != 4) return false
        val first = octets[0].toIntOrNull() ?: return false
        val second = octets[1].toIntOrNull() ?: return false
        return first == 100 && second in 64..127
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
        private const val LOOPBACK = "127.0.0.1"
        /** Tailscale's own DNS/coordination address — only used to pick a source interface. */
        private const val TAILSCALE_PROBE = "100.100.100.100"
    }
}
