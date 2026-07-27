package com.termux.cybersyn.core.external

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.termux.cybersyn.app.BuildConfig
import com.termux.cybersyn.app.CybersynApp_NoHilt
import com.termux.cybersyn.core.actions.ActionMetadataRegistry
import com.termux.cybersyn.core.capabilities.ActionCapabilityRegistry
import com.termux.cybersyn.core.diagnostics.DiagnosticExport
import com.termux.cybersyn.core.diagnostics.EngineHealthReader
import com.termux.cybersyn.core.logging.AppLogger
import com.termux.cybersyn.core.power.ShizukuPowerBackend
import com.termux.cybersyn.core.scripting.TermuxScriptBackend
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ExplainReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXPLAIN) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val extras = Bundle()
            val resultCode = runCatching {
                val report = buildReport(context.applicationContext, intent)
                val outputDir = File(TERMUX_HOME, ".cybersyn/reports").apply { mkdirs() }
                val jsonFile = File(outputDir, "latest.json")
                val textFile = File(outputDir, "latest.txt")
                jsonFile.writeText(report.json.toString(2))
                textFile.writeText(report.text)
                jsonFile.setReadable(true, false)
                textFile.setReadable(true, false)
                extras.putString(EXTRA_JSON_PATH, jsonFile.absolutePath)
                extras.putString(EXTRA_TEXT_PATH, textFile.absolutePath)
                extras.putString(EXTRA_SUMMARY, report.summary)
                Activity.RESULT_OK
            }.getOrElse { error ->
                AppLogger.error(TAG, "Explain request failed", error)
                extras.putString(AutomationTargetContract.EXTRA_ERROR, error.message ?: "Explain request failed")
                Activity.RESULT_CANCELED
            }
            pending.setResultCode(resultCode)
            pending.setResultExtras(extras)
            pending.finish()
        }
    }

    private suspend fun buildReport(context: Context, intent: Intent): ExplainReport {
        val scope = intent.getStringExtra(EXTRA_SCOPE)?.lowercase(Locale.ROOT) ?: "all"
        val now = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)
        val db = CybersynApp_NoHilt.db
        val tasks = db.taskDao().getAll().mapNotNull { it.toDomainDecodeResult().value }
        val profiles = db.profileDao().getAll().mapNotNull { it.toDomainDecodeResult().value }
        val runLogs = db.runLogDao().getRecent()
        val appLog = AppLogger.snapshot().takeLast(80)
        val engineHealth = runCatching { EngineHealthReader.read(context, now) }.getOrNull()
        val termuxStatus = TermuxScriptBackend.inspect(context)
        val shizukuStatus = ShizukuPowerBackend.inspect(context)

        val runtime = JSONObject()
            .put("generated_at", dateFormat.format(Date(now)))
            .put("scope", scope)
            .put("counts", JSONObject()
                .put("tasks", tasks.size)
                .put("profiles", profiles.size)
                .put("enabled_profiles", profiles.count { it.enabled && !it.requiresRiskAcknowledgement })
                .put("run_logs", db.runLogDao().count()))
            .put("engine", JSONObject()
                .put("service_running", engineHealth?.serviceRunning)
                .put("last_heartbeat_at", engineHealth?.lastHeartbeatAtMillis?.takeIf { it > 0 }?.let { dateFormat.format(Date(it)) })
                .put("exact_alarm", engineHealth?.exactAlarmStatus)
                .put("standby_bucket", engineHealth?.standbyBucket)
                .put("last_matcher_error", engineHealth?.lastMatcherError))
            .put("bridges", JSONObject()
                .put("termux", JSONObject()
                    .put("state", termuxStatus.state.name)
                    .put("ready", termuxStatus.isReady)
                    .put("summary", termuxStatus.summary))
                .put("shizuku", JSONObject()
                    .put("state", shizukuStatus.state.name)
                    .put("ready", shizukuStatus.isReady)
                    .put("summary", shizukuStatus.summary)))
            .put("profiles", JSONArray(profiles.map { profile ->
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("enabled", profile.enabled)
                    .put("review_required", profile.requiresRiskAcknowledgement)
                    .put("contexts", profile.contexts.size)
                    .put("enter_task_id", profile.enterTaskId)
            }))
            .put("tasks", JSONArray(tasks.map { task ->
                JSONObject()
                    .put("id", task.id)
                    .put("name", task.name)
                    .put("actions", task.actions.size)
                    .put("action_types", JSONArray(task.actions.map { it.type }))
            }))
            .put("recent_runs", JSONArray(runLogs.take(30).map { entry ->
                JSONObject()
                    .put("id", entry.id)
                    .put("task_id", entry.taskId)
                    .put("task_name", entry.taskName)
                    .put("timestamp", dateFormat.format(Date(entry.timestamp)))
                    .put("success", entry.success)
                    .put("duration_ms", entry.durationMs)
                    .put("source", entry.sourceLabel ?: entry.source)
                    .put("message", DiagnosticExport.redactSensitive(entry.message).take(500))
            }))
            .put("app_log_tail", JSONArray(appLog.map { entry ->
                JSONObject()
                    .put("timestamp", dateFormat.format(Date(entry.timestampMillis)))
                    .put("level", entry.level.name)
                    .put("tag", entry.tag)
                    .put("message", DiagnosticExport.redactSensitive(entry.message).take(500))
            }))

        val actionCapabilities = JSONArray(ActionMetadataRegistry.all().sortedBy { it.id }.map { metadata ->
            val capability = ActionCapabilityRegistry.get(metadata.id)
            JSONObject()
                .put("id", metadata.id)
                .put("name", context.getString(metadata.nameRes))
                .put("category", context.getString(metadata.categoryRes))
                .put("capability", capability.level.name)
                .put("can_add", capability.canAdd)
                .put("reason", capability.reason)
        })

        val buildState = JSONObject()
            .put("app", JSONObject()
                .put("package", context.packageName)
                .put("version_name", BuildConfig.VERSION_NAME)
                .put("version_code", BuildConfig.VERSION_CODE)
                .put("distribution", BuildConfig.DISTRIBUTION)
                .put("debug", BuildConfig.DEBUG)
                .put("sms_action_available", BuildConfig.SMS_ACTION_AVAILABLE))
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("android_release", Build.VERSION.RELEASE)
                .put("api", Build.VERSION.SDK_INT)
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())))
            .put("actions", actionCapabilities)

        val root = JSONObject()
            .put("runtime_info", runtime)
            .put("build_state", buildState)
            .put("operational_notes", JSONArray(OPERATIONAL_NOTES))
            .put("next_commands", JSONArray(listOf(
                "adb shell am broadcast -a $ACTION_EXPLAIN --es scope all",
                "adb shell am broadcast -a ${AutomationTargetContract.ACTION_QUERY_STATUS}",
                "adb shell am broadcast -a ${AutomationTargetContract.ACTION_RUN_TASK} --es ${AutomationTargetContract.EXTRA_TASK_NAME} '<task name>'",
            )))

        val text = buildTextReport(runtime, buildState, dateFormat, now)
        val summary = "Cybersyn explain: ${tasks.size} tasks, ${profiles.count { it.enabled && !it.requiresRiskAcknowledgement }}/${profiles.size} enabled profiles, ${runLogs.count { !it.success }} failures in recent log window."
        return ExplainReport(root, text, summary)
    }

    private fun buildTextReport(runtime: JSONObject, buildState: JSONObject, dateFormat: SimpleDateFormat, now: Long): String = buildString {
        appendLine("=== Cybersyn Explain ===")
        appendLine("Generated: ${dateFormat.format(Date(now))}")
        appendLine()
        appendLine("--- Operational Notes (read before editing schema/tasks/profiles) ---")
        OPERATIONAL_NOTES.forEach { appendLine("- $it") }
        appendLine()
        appendLine("--- Runtime Info ---")
        appendLine(runtime.toString(2))
        appendLine()
        appendLine("--- Build State ---")
        appendLine(buildState.toString(2))
        appendLine("=== End Explain ===")
    }

    companion object {
        const val ACTION_EXPLAIN = "com.termux.cybersyn.action.EXPLAIN"
        const val EXTRA_SCOPE = "scope"
        const val EXTRA_JSON_PATH = "com.termux.cybersyn.extra.EXPLAIN_JSON_PATH"
        const val EXTRA_TEXT_PATH = "com.termux.cybersyn.extra.EXPLAIN_TEXT_PATH"
        const val EXTRA_SUMMARY = "com.termux.cybersyn.extra.EXPLAIN_SUMMARY"
        private const val TAG = "ExplainReceiver"
        private const val TERMUX_HOME = "/data/data/com.termux/files/home"

        // Hard-won lessons from real incidents, kept here (not just in commit messages) so
        // an LLM querying this endpoint gets them without re-deriving them from source or
        // repeating the same mistake. Add to this list when a real bug/gotcha is found and
        // fixed — don't let it live only in a commit message.
        private val OPERATIONAL_NOTES = listOf(
            "Task.collisionMode is a Kotlin enum (ABORT_NEW/ABORT_EXISTING/RUN_BOTH/WAIT) but stored " +
                "as a raw TEXT column. A raw sqlite3 INSERT with any other string (e.g. \"QUEUE\") is " +
                "accepted by the DB but fails CollisionMode.valueOf() on decode, silently blocking that " +
                "task from running/editing (real incident: task 135 'sshd: poll watchdog check', fixed " +
                "2026-07-27). Prefer AutomationCliReceiver's ACTION_IMPORT_BUNDLE (goes through the real " +
                "Task/Profile Kotlin data classes + kotlinx.serialization, can't produce an invalid enum) " +
                "over raw sqlite3 INSERT for adding tasks. If you must use raw SQL, the only valid " +
                "collisionMode strings are ABORT_NEW, ABORT_EXISTING, RUN_BOTH, WAIT — check the current " +
                "enum in core/model/Task.kt first, it can change.",
            "To make something self-heal whenever the Cybersyn engine (re)starts — not just full device " +
                "boot, but also a plain app relaunch after a force-stop — add a Profile with contexts " +
                "[{\"type\":\"EVENT\",\"config\":{\"event\":\"boot_completed\"}}] pointing at a healing Task. " +
                "This fires on ACTION_BOOT_COMPLETED_TRIGGER, which MainActivity also uses when starting " +
                "the service normally, not just the real boot receiver. Existing examples: SshdBoot (task " +
                "'Restore SSHD'), NpudBoot (task 'npud: restart on log evidence', added 2026-07-27). This " +
                "is the pattern to extend for anything new that needs restart-recovery — no new Kotlin " +
                "code needed, just a Task + Profile pair.",
            "com.termux.cybersyn shares UID 1000 (android.uid.system) with the whole com.termux.* family. " +
                "Force-stopping this app (including the force-stop blazer-sysapp-update's install step " +
                "does automatically) kills sshd, npud, and anything else sharing that UID too. Never " +
                "force-stop any com.termux.* package expecting it to be isolated.",
            "MqttBridge.subscribe() spawns one OS process per distinct topic filter, each connecting to " +
                "the broker with an MQTT client id. Every process must use a DISTINCT id (real incident: " +
                "they all shared the helper's default id until 2026-07-27, so with 4+ concurrent " +
                "subscriptions each new connection kicked the previous one off the broker in a constant " +
                "churn — cybersyn/file/offer looked 'flaky' but was actually just disconnected most of " +
                "the time). If you add a new call path that spawns the mqtt-helper 'sub' subprocess " +
                "directly, it needs its own --id too, not just topic + broker.",
            "File transfer (file:serve/file:receive/albumart) binds explicit Tailscale + LAN addresses, " +
                "never 0.0.0.0 — the MQTT control plane (cybersyn/comrade/action) is the real trust " +
                "boundary (Tailscale-only), the bulk-transfer socket is just a dumb pipe that only opens " +
                "because something already authenticated over Tailscale asked for it. Don't widen the " +
                "listen scope further without re-reading that reasoning (Fedora_src/cybersyn-relay/src/" +
                "file_transfer.rs).",
        )
    }
}

private data class ExplainReport(
    val json: JSONObject,
    val text: String,
    val summary: String,
)
