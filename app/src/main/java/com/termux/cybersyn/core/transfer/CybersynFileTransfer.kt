package com.termux.cybersyn.core.transfer

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP client counterpart to the relay's `file_transfer.rs` — connects to the
 * ip:port from a `cybersyn/file/offer` message and streams the raw bytes down.
 * No framing beyond the offer's `size` field; the relay just writes the file
 * and closes the connection.
 */
object CybersynFileTransfer {
    private const val CHUNK_SIZE = 65536
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Download into [destFile]. Returns bytes written, or null on failure (partial file removed). */
    fun downloadToFile(ip: String, port: Int, destFile: File): Long? {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                destFile.parentFile?.mkdirs()
                var total = 0L
                val buf = ByteArray(CHUNK_SIZE)
                FileOutputStream(destFile).use { out ->
                    val input = socket.getInputStream()
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        total += n
                    }
                }
                total
            }
        } catch (_: Exception) {
            destFile.delete()
            null
        }
    }

    /**
     * Download into memory — for small payloads like album art. [maxBytes] caps memory use
     * against a malformed/oversized offer; returns null if exceeded.
     */
    fun downloadToBytes(ip: String, port: Int, maxBytes: Int = 20 * 1024 * 1024): ByteArray? {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                val out = ByteArrayOutputStream()
                val buf = ByteArray(CHUNK_SIZE)
                val input = socket.getInputStream()
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    if (out.size() + n > maxBytes) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }
}
