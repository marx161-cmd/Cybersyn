package com.termux.cybersyn.core.engine

import android.os.Process
import com.termux.cybersyn.core.logging.AppLogger
import java.io.File

/**
 * Catches the bug class [Process.destroy]/GC can't see: an OS child process that outlives the
 * Java object that spawned it. Two prior incidents were exactly this shape and only found via
 * manual forensics -- a `su -c "exec logcat ..."` reader surviving destroy() because it re-execs
 * as root (destroy() can't signal a different UID), and duplicate MQTT `sub` helper processes
 * fighting over one client id because every subscriber independently spawned its own. Neither
 * is a JVM heap leak, so nothing that watches object graphs (LeakCanary, StrictMode) can see it;
 * the only way to catch it is asking the OS directly what's actually still running.
 *
 * Walks /proc for live children of this process (ppid == our pid), which stays accurate even for
 * the su-exec'd case: `su -c "exec logcat"` replaces the shell's own image, so the resulting
 * process keeps the original ppid for its whole life. Groups by full cmdline and flags any
 * signature with more than one concurrent instance -- every one of these helper binaries
 * (logcat reader, per-topic mqtt sub, mqtt pub) is designed to have at most one live instance
 * per distinct argv, so a duplicate is always the exact anomaly seen before, never a false
 * positive from legitimate concurrent use.
 */
object ChildProcessAudit {
    private const val TAG = "ChildProcessAudit"

    /** /proc/[pid]/cmdline separates and terminates argv elements with NUL, not spaces. */
    private const val ARGV_NUL = '\u0000'

    /** Logs a WARN per duplicated cmdline signature; returns the count of duplicate groups found. */
    fun check(): Int {
        val ownPid = Process.myPid()
        val children = runCatching { liveChildren(ownPid) }.getOrElse { error ->
            AppLogger.warn(TAG, "Could not enumerate /proc for child-process audit", error)
            return 0
        }

        if (children.isEmpty()) {
            AppLogger.debug(TAG, "No child processes")
            return 0
        }

        val byCmdline = children.groupBy { it.cmdline }
        val duplicates = byCmdline.filterValues { it.size > 1 }

        AppLogger.debug(TAG, "${children.size} child process(es), ${byCmdline.size} distinct cmdline(s)")

        for ((cmdline, procs) in duplicates) {
            AppLogger.warn(
                TAG,
                "Duplicate child processes for the same command (orphan/leak suspect): " +
                    "cmdline=\"$cmdline\" pids=${procs.map { it.pid }} count=${procs.size}",
            )
        }

        return duplicates.size
    }

    private data class ChildProc(val pid: Int, val cmdline: String)

    private fun liveChildren(ownPid: Int): List<ChildProc> {
        val procDir = File("/proc")
        val entries = procDir.listFiles() ?: return emptyList()
        val result = mutableListOf<ChildProc>()

        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            val stat = runCatching { File(entry, "stat").readText() }.getOrNull() ?: continue

            // Field 4 is ppid; fields are space-separated after the ")" that closes the (comm)
            // field, which itself may contain spaces/parens -- split from the last ')'.
            val afterComm = stat.substringAfterLast(')').trim()
            val fields = afterComm.split(' ')
            if (fields.size < 2) continue
            val ppid = fields[1].toIntOrNull() ?: continue
            if (ppid != ownPid) continue

            val rawCmdline = runCatching { File(entry, "cmdline").readText() }.getOrNull()
            val cmdline = rawCmdline
                ?.trim(ARGV_NUL)
                ?.replace(ARGV_NUL, ' ')
                ?.ifEmpty { null }
                ?: continue

            result.add(ChildProc(pid, cmdline))
        }
        return result
    }
}
