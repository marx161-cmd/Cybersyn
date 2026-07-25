package com.termux.cybersyn.core.scripting

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxScriptBackendTest {
    private val script = "~/.termux/tasker/backup.sh"

    @Test
    fun statusForReportsPackageAndPermissionReadiness() {
        val missingTermux = TermuxScriptBackend.statusFor(termuxInstalled = false, permissionGranted = false)
        val oldVersion = TermuxScriptBackend.statusFor(
            termuxInstalled = true,
            permissionGranted = true,
            resultProtocolSupported = false,
        )
        val missingPermission = TermuxScriptBackend.statusFor(termuxInstalled = true, permissionGranted = false)
        val ready = TermuxScriptBackend.statusFor(termuxInstalled = true, permissionGranted = true)

        assertEquals(TermuxScriptState.TermuxMissing, missingTermux.state)
        assertFalse(missingTermux.termuxInstalled)
        assertFalse(missingTermux.isReady)
        assertEquals(TermuxScriptState.VersionUnsupported, oldVersion.state)
        assertEquals(TermuxScriptState.PermissionRequired, missingPermission.state)
        assertTrue(missingPermission.termuxInstalled)
        assertFalse(missingPermission.isReady)
        assertEquals(TermuxScriptState.Ready, ready.state)
        assertTrue(ready.isReady)
    }

    @Test
    fun actionHintOnlyCoversTermuxScriptAction() {
        assertNotNull(TermuxScriptBackend.hintForAction(TermuxScriptBackend.ACTION_ID))
        assertNull(TermuxScriptBackend.hintForAction("plugin.locale.fire"))
    }

    @Test
    fun packageAndPermissionConstantsMatchTermuxContracts() {
        assertEquals("com.termux", TermuxScriptBackend.TERMUX_PACKAGE)
        assertEquals("com.termux.permission.RUN_COMMAND", TermuxScriptBackend.RUN_COMMAND_PERMISSION)
        assertEquals("~/.termux/tasker", TermuxScriptBackend.SCRIPT_DIRECTORY)
        assertFalse(TermuxScriptBackend.supportsResultProtocol("0.108.0"))
        assertTrue(TermuxScriptBackend.supportsResultProtocol("0.109.0-beta.1"))
        assertTrue(TermuxScriptBackend.supportsResultProtocol("0.118.3"))
    }

    @Test
    fun invocationDefaultsUseRootToFalse() {
        val inv = invocation()
        assertFalse(inv.useRoot)
    }

    @Test
    fun prepareAcceptsRootInvocationWithoutExtraValidation() {
        val inv = invocation(useRoot = true)
        val result = TermuxScriptPolicy.prepare(inv)
        assertTrue(result is TermuxPreparationResult.Ready)
    }

    @Test
    fun prepareRejectsTraversalAndUnnormalizedPaths() {
        val traversal = TermuxScriptPolicy.prepare(invocation("~/.termux/tasker/../escape.sh"))
        val outside = TermuxScriptPolicy.prepare(invocation("/system/bin/reboot"))
        assertTrue(traversal is TermuxPreparationResult.Invalid)
        assertTrue(outside is TermuxPreparationResult.Invalid)
    }

    @Test
    fun normalizeExecutableRejectsControlsBareDirectoryAndDotSegments() {
        assertNull(TermuxScriptPolicy.normalizeExecutable("~/.termux/tasker/"))
        assertNull(TermuxScriptPolicy.normalizeExecutable("~/.termux/tasker/."))
        assertNull(TermuxScriptPolicy.normalizeExecutable("~/.termux/tasker/.."))
        assertNull(TermuxScriptPolicy.normalizeExecutable("~/.termux/tasker/sub/../script.sh"))
        assertNull(TermuxScriptPolicy.normalizeExecutable("~/.termux/tasker/\u0000.sh"))
        assertNotNull(TermuxScriptPolicy.normalizeExecutable("~/.termux/tasker/ok.sh"))
    }

    @Test
    fun normalizeExecutableAcceptsAbsoluteTermuxHomePath() {
        assertNotNull(TermuxScriptPolicy.normalizeExecutable("/data/data/com.termux/files/home/.termux/tasker/pixelsnap.sh"))
        assertNotNull(TermuxScriptPolicy.normalizeExecutable("/data/data/com.termux/files/home/.termux/tasker/sub/ok.sh"))
    }

    @Test
    fun builtCommandWrapsWithSuWhenUseRootIsSet() {
        val req = TermuxCommandRequest(
            executable = "~/.termux/tasker/test.sh",
            arguments = listOf("arg1", "arg two"),
            timeoutMs = 30_000L,
            useRoot = true,
        )
        val (exec, args) = buildCommand(req)
        assertEquals("/data/data/com.termux/files/usr/bin/su", exec)
        assertEquals(2, args.size)
        assertEquals("-c", args[0])
        assertTrue(args[1].contains("'arg1'"))
        assertTrue(args[1].contains("'arg two'"))
        assertTrue(args[1].startsWith("'") || args[1].startsWith("/"))
    }

    @Test
    fun builtCommandPassesThroughWhenUseRootIsFalse() {
        val req = TermuxCommandRequest(
            executable = "~/.termux/tasker/test.sh",
            arguments = listOf("arg1"),
            timeoutMs = 30_000L,
            useRoot = false,
        )
        val (exec, args) = buildCommand(req)
        assertTrue(exec.endsWith("test.sh"))
        assertEquals(listOf("arg1"), args)
    }

    @Test
    fun sourceContractKeepsResultChannelPrivate() {
        val moduleRoot = listOf(Path.of("."), Path.of("app")).first { Files.isDirectory(it.resolve("src/main")) }
        val manifest = moduleRoot.resolve("src/main/AndroidManifest.xml").toFile().readText()
        val broker = moduleRoot.resolve("src/main/java/com/termux/cybersyn/core/scripting/TermuxCommandBroker.kt").toFile().readText()

        assertTrue("RUN_COMMAND permission must be declared", "com.termux.permission.RUN_COMMAND" in manifest)
        assertTrue("Result receiver must be non-exported", Regex("TermuxResultReceiver[\\s\\S]{0,120}android:exported=\"false\"").containsMatchIn(manifest))
        assertTrue("Termux callback must use a one-shot pending intent", "PendingIntent.FLAG_ONE_SHOT" in broker)
        assertTrue("Termux original lengths must parse their documented string encoding", "bundle.getString(key)?.toIntOrNull()" in broker)
    }

    companion object {
        internal fun buildCommand(request: TermuxCommandRequest): Pair<String, List<String>> {
            val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
            val TERMUX_HOME = "/data/data/com.termux/files/home"
            fun resolveTermuxPath(path: String): String = when {
                path == "\$PREFIX" -> TERMUX_PREFIX
                path.startsWith("\$PREFIX/") -> TERMUX_PREFIX + path.removePrefix("\$PREFIX")
                path == "~" -> TERMUX_HOME
                path.startsWith("~/") -> TERMUX_HOME + path.removePrefix("~")
                else -> path
            }
            val executable = resolveTermuxPath(request.executable)
            if (!request.useRoot) return executable to request.arguments
            val cmd = listOf(executable) + request.arguments
            val shellCmd = cmd.joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
            return resolveTermuxPath(TermuxScriptPolicy.SU_EXECUTABLE) to listOf("-c", shellCmd)
        }
    }

    private fun invocation(
        executable: String = script,
        argumentText: String? = null,
        stdin: String? = null,
        useRoot: Boolean = false,
    ) = TermuxScriptInvocation(
        executable = executable,
        argumentText = argumentText,
        workingDirectory = null,
        stdin = stdin,
        timeoutMs = TermuxScriptPolicy.DEFAULT_TIMEOUT_MS,
        useRoot = useRoot,
    )
}
