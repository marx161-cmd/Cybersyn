package com.termux.cybersyn.core.transfer

/**
 * Spawns Termux CLI binaries (tar, zstd) with the linker env they need — same pattern as
 * MqttBridge's mosquitto fallback, since Cybersyn shares Termux's UID and can exec its
 * `$PREFIX/bin` tools directly.
 */
object TermuxExec {
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

    /** [relativeArgv] is e.g. `listOf("bin/tar", "-cf", ...)` — argv[0] is resolved under $PREFIX. */
    fun exec(relativeArgv: List<String>): Process {
        val argv = relativeArgv.toMutableList()
        argv[0] = "$TERMUX_PREFIX/${argv[0]}"
        val pb = ProcessBuilder(argv)
        pb.environment()["PREFIX"] = TERMUX_PREFIX
        pb.environment()["LD_LIBRARY_PATH"] = "$TERMUX_PREFIX/lib"
        return pb.start()
    }
}
