package com.termux.cybersyn.core.engine

import android.os.Process
import com.termux.cybersyn.app.CybersynApp_NoHilt
import com.termux.cybersyn.core.logging.AppLogger
import com.termux.cybersyn.core.model.RunLogEntry
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Catches the bug class [Process.destroy]/GC can't see: an OS child process that outlives the
 * Java object that spawned it. Two prior incidents were exactly this shape and only found via
 * manual forensics -- a `su -c "exec logcat ..."` reader surviving destroy() because it re-execs
 * as root (destroy() can't signal a different UID), and duplicate MQTT `sub` helper processes
 * fighting over one client id because every subscriber independently spawned its own. Neither
 * is a JVM heap leak, so nothing that watches object graphs (LeakCanary, StrictMode) can see it;
 * the only way to catch it is asking the OS directly what's actually still running.
 *
 * Enumerates via `su -c 'ls /proc'` and per-pid stat/cmdline reads, because blazer's /proc is
 * mounted `hidepid=invisible,gid=3009` and this app (supplementary groups include AID_LOG 1007
 * but NOT AID_READPROC 3009) can only see same-UID children through a direct Java File() walk.
 * Falls back to the direct walk if su is unavailable, which catches same-UID children only.
 *
 * Groups by full cmdline and flags any signature with more than one concurrent instance --
 * every one of these helper binaries (logcat reader, per-topic mqtt sub, mqtt pub) is designed
 * to have at most one live instance per distinct argv, so a duplicate is always the exact
 * anomaly seen before, never a false positive from legitimate concurrent use.
 */
object ChildProcessAudit {
    private const val TAG = "ChildProcessAudit"

    /** /proc/[pid]/cmdline separates and terminates argv elements with NUL, not spaces. */
    private const val ARGV_NUL = '\u0000'

    /** Sentinel task id for system-level run_log entries this audit generates. */
    private const val SYSTEM_TASK_ID = 0L

    /**
     * Returns the count of duplicate groups found.
     * Writes a run_log entry when duplicates are detected so leaks surface without manual
     * logcat inspection.
     */
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
            val msg = "Child process(es) for the same command (orphan/leak suspect): " +
                "cmdline=\"$cmdline\" pids=${procs.map { it.pid }} count=${procs.size}"
            AppLogger.warn(TAG, msg)
        }

        if (duplicates.isNotEmpty()) {
            val summary = buildString {
                appendLine("ChildProcessAudit: ${children.size} children, ${duplicates.size} duplicate group(s)")
                for ((cmdline, procs) in duplicates) {
                    appendLine("  $cmdline: ${procs.size} instances, pids=${procs.map { it.pid }}")
                }
            }
            writeRunLog(summary)
        }

        return duplicates.size
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun writeRunLog(message: String) {
        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                val entry = RunLogEntry(
                    taskId = SYSTEM_TASK_ID,
                    taskName = "ChildProcessAudit",
                    timestamp = System.currentTimeMillis(),
                    durationMs = 0,
                    success = false,
                    message = message,
                    source = "engine",
                    sourceLabel = "ChildProcessAudit",
                )
                insertRunLog(CybersynApp_NoHilt.db, entry)
            }.onFailure {
                AppLogger.warn(TAG, "Failed to write child-process audit run_log", it)
            }
        }
    }

    private data class ChildProc(val pid: Int, val cmdline: String)

    /**
     * Primary path: shell out `su -c 'ls /proc'` to enumerate ALL pids regardless of the
     * hidepid mount, then read each /proc/<pid>/stat and /proc/<pid>/cmdline under the same
     * root shell. Falls back to a direct Java File() walk (same-UID only) if su is unavailable.
     */
    private fun liveChildren(ownPid: Int): List<ChildProc> {
        val fromSu = runCatching { liveChildrenViaSu(ownPid) }.getOrNull()
        if (fromSu != null) return fromSu

        AppLogger.warn(TAG, "su-based /proc walk unavailable; falling back to direct read (same-UID children only)")
        return liveChildrenDirect(ownPid)
    }

    private fun liveChildrenViaSu(ownPid: Int): List<ChildProc> {
        val pids = mutableListOf<Int>()
        val lsProc = ProcessBuilder("su", "-c", "ls /proc")
            .redirectErrorStream(true)
            .start()
        BufferedReader(InputStreamReader(lsProc.inputStream)).use { reader ->
            for (line in reader.lines()) {
                line.trim().toIntOrNull()?.let { pids.add(it) }
            }
        }
        lsProc.waitFor()

        if (pids.isEmpty()) return emptyList()

        val pidsArg = pids.joinToString(" ") { it.toString() }
        // Let the shell compute ppid from /proc/<pid>/stat field 4 (after ")" close-paren),
        // then cat the cmdline. One su invocation for all pids.
        val script = buildString {
            appendLine("for p in $pidsArg; do")
            appendLine("  test -r /proc/\$p/stat || continue")
            // sed strips everything up to ") " (the close-paren + space that ends the comm field),
            // then cut picks field 2 (ppid) -- safe even when comm contains spaces/parens.
            appendLine("  ppid=\$(sed 's/[^)]*) //' /proc/\$p/stat 2>/dev/null | cut -d' ' -f2)")
            appendLine("  echo PID:\$p:\$ppid")
            appendLine("  tr '\\0' ' ' < /proc/\$p/cmdline 2>/dev/null")
            appendLine("  echo")
            appendLine("done")
        }
        val batch = ProcessBuilder("su", "-c", script)
            .redirectErrorStream(true)
            .start()

        val result = mutableListOf<ChildProc>()
        var currentPid = -1
        var currentPpid = -1
        var expectingCmdline = false

        BufferedReader(InputStreamReader(batch.inputStream)).use { reader ->
            for (line in reader.lines()) {
                if (line.startsWith("PID:") && ':' in line.substring(4)) {
                    val parts = line.removePrefix("PID:").split(":")
                    if (parts.size < 2) continue
                    currentPid = parts[0].trim().toIntOrNull() ?: -1
                    currentPpid = parts[1].trim().toIntOrNull() ?: -1
                    expectingCmdline = (currentPpid == ownPid)
                } else if (expectingCmdline) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && currentPid >= 0) {
                        result.add(ChildProc(currentPid, trimmed))
                    }
                    expectingCmdline = false
                    currentPid = -1
                    currentPpid = -1
                }
            }
        }
        batch.waitFor()

        return result
    }

    private fun liveChildrenDirect(ownPid: Int): List<ChildProc> {
        val procDir = File("/proc")
        val entries = procDir.listFiles() ?: return emptyList()
        val result = mutableListOf<ChildProc>()

        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            val stat = runCatching { File(entry, "stat").readText() }.getOrNull() ?: continue

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
