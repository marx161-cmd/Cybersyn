package com.termux.cybersyn.core.transfer

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP client counterpart to the relay's `file_transfer.rs` — connects to an
 * ip:port from a `cybersyn/file/offer` message and streams the raw bytes down.
 * No framing beyond the offer's `size` field; the relay just writes the file
 * and closes the connection.
 *
 * Candidates are tried in order (LAN address first when the relay offered one,
 * Tailscale address last) — a short timeout on every candidate but the last, so
 * an unreachable LAN shortcut doesn't stall the guaranteed Tailscale fallback.
 */
object CybersynFileTransfer {
    private const val CHUNK_SIZE = 65536
    private const val LAN_CONNECT_TIMEOUT_MS = 1200
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Download into [destFile]. Returns bytes written, or null on failure (partial file removed).
     *  Caps at [maxBytes] to prevent a hostile/malformed offer from filling the phone's cache. */
    fun downloadToFile(
        candidates: List<Pair<String, Int>>,
        destFile: File,
        maxBytes: Long = 100 * 1024 * 1024,
        onProgress: ((bytesSoFar: Long) -> Unit)? = null,
    ): Long? {
        val socket = connect(candidates) ?: return null
        return try {
            socket.use {
                it.soTimeout = READ_TIMEOUT_MS
                destFile.parentFile?.mkdirs()
                var total = 0L
                val buf = ByteArray(CHUNK_SIZE)
                FileOutputStream(destFile).use { out ->
                    val input = it.getInputStream()
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        if (total + n > maxBytes) {
                            // exceeded cap — discard
                            return@use null
                        }
                        out.write(buf, 0, n)
                        total += n
                        onProgress?.invoke(total)
                    }
                }
                total.takeIf { it <= maxBytes }
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
    fun downloadToBytes(candidates: List<Pair<String, Int>>, maxBytes: Int = 20 * 1024 * 1024): ByteArray? {
        val socket = connect(candidates) ?: return null
        return try {
            socket.use {
                it.soTimeout = READ_TIMEOUT_MS
                val out = ByteArrayOutputStream()
                val buf = ByteArray(CHUNK_SIZE)
                val input = it.getInputStream()
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

    private fun connect(candidates: List<Pair<String, Int>>): Socket? {
        for ((index, candidate) in candidates.withIndex()) {
            val (ip, port) = candidate
            val timeout = if (index < candidates.size - 1) LAN_CONNECT_TIMEOUT_MS else CONNECT_TIMEOUT_MS
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, port), timeout)
                return socket
            } catch (_: Exception) {
                // try the next candidate
            }
        }
        return null
    }
}
