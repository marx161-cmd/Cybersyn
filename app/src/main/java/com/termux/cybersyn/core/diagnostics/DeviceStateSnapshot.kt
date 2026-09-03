package com.termux.cybersyn.core.diagnostics

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Live memory/CPU/process/battery/thermal snapshot for the Explain manifest.
 *
 * This is deliberately the "device state" half of `~/bin/android-snapshot.sh`
 * (meminfo/cpuinfo/battery/thermal/processes), not the full diagnostic dump
 * (settings/packages/appops/storage/wifi/display stay out of scope here — this
 * is live task-authoring context, not a full device audit).
 *
 * Every shell capture goes through `su -c "timeout N ..."`, never a raw
 * unbounded call — see ChildProcessAudit's runaway-`tr` incident
 * (scope.md 2026-08-29) for why an unbounded /proc-adjacent shell read burned
 * ~1255 mAh over 9.5 hours.
 */
object DeviceStateSnapshot {

    /** One entry per section; a failed/timed-out section reports its own error string
     * instead of taking the rest of the snapshot down with it. */
    fun capture(): Map<String, String> = linkedMapOf(
        "meminfo" to runCatching { readFile("/proc/meminfo", maxLines = 15) }
            .getOrElse { "(unavailable: ${it.message})" },
        "battery" to runCatching { runShellCapped(listOf("dumpsys", "battery"), maxLines = 20) }
            .getOrElse { "(unavailable: ${it.message})" },
        "thermal" to runCatching { runShellCapped(listOf("dumpsys", "thermalservice"), maxLines = 20) }
            .getOrElse { "(unavailable: ${it.message})" },
        "processes" to runCatching { processSnapshot(maxLines = 40) }
            .getOrElse { "(unavailable: ${it.message})" },
    )

    private fun readFile(path: String, maxLines: Int): String =
        File(path).bufferedReader().useLines { lines ->
            lines.take(maxLines).joinToString("\n")
        }

    /**
     * Top processes by RSS. Plain `ps -A` orders by PID, so on a real device the
     * first ~50 rows are zero-RSS kernel threads and the field never reaches an
     * actual app within any reasonable cap (real incident 2026-09-02: diagnosing
     * live sluggishness needed a manual `ps | sort` because this field was
     * silently useless). Sorting descending by RSS (`sort -k3 -nr`) pushes real
     * processes to the top and kernel threads off the end of the cap for free.
     */
    private fun processSnapshot(maxLines: Int): String =
        runRawShell("timeout $TIMEOUT_SECONDS ps -A -o PID,PPID,RSS,ARGS | sort -k3 -nr", maxLines)

    /** Bounded `su -c "timeout <TIMEOUT_SECONDS> <cmd>"`, output capped to [maxLines]. */
    private fun runShellCapped(cmd: List<String>, maxLines: Int): String =
        runRawShell("timeout $TIMEOUT_SECONDS ${cmd.joinToString(" ")}", maxLines)

    private fun runRawShell(shellCmd: String, maxLines: Int): String {
        val proc = ProcessBuilder("su", "-c", shellCmd)
            .redirectErrorStream(true)
            .start()
        val output = BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
            reader.lineSequence().take(maxLines).joinToString("\n")
        }
        proc.waitFor()
        return output
    }

    private const val TIMEOUT_SECONDS = 5
}
