package com.termux.cybersyn.core.external

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import com.termux.cybersyn.app.CybersynApp_NoHilt
import com.termux.cybersyn.core.engine.executeAndLogTask
import com.termux.cybersyn.core.logging.AppLogger
import com.termux.cybersyn.core.transfer.OpenTaskerBundleCodec
import com.termux.cybersyn.core.transfer.OpenTaskerBundleRepository
import com.termux.cybersyn.core.storage.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

object AutomationTargetContract {
    const val PERMISSION = "com.termux.cybersyn.permission.AUTOMATION"

    const val ACTION_RUN_TASK = "com.termux.cybersyn.action.RUN_TASK"
    const val ACTION_SET_PROFILE_ENABLED = "com.termux.cybersyn.action.SET_PROFILE_ENABLED"
    const val ACTION_QUERY_STATUS = "com.termux.cybersyn.action.QUERY_STATUS"
    const val ACTION_IMPORT_BUNDLE = "com.termux.cybersyn.action.IMPORT_BUNDLE"
    const val ACTION_EXPORT_BUNDLE = "com.termux.cybersyn.action.EXPORT_BUNDLE"

    const val EXTRA_TASK_ID = "com.termux.cybersyn.extra.TASK_ID"
    const val EXTRA_TASK_NAME = "com.termux.cybersyn.extra.TASK_NAME"
    const val EXTRA_PROFILE_ID = "com.termux.cybersyn.extra.PROFILE_ID"
    const val EXTRA_PROFILE_NAME = "com.termux.cybersyn.extra.PROFILE_NAME"
    const val EXTRA_ENABLED = "com.termux.cybersyn.extra.ENABLED"
    const val EXTRA_ERROR = "com.termux.cybersyn.extra.ERROR"
    const val EXTRA_TASK_SUCCESS = "com.termux.cybersyn.extra.TASK_SUCCESS"
    const val EXTRA_TASK_DURATION_MS = "com.termux.cybersyn.extra.TASK_DURATION_MS"
    const val EXTRA_TASK_TIMED_OUT = "com.termux.cybersyn.extra.TASK_TIMED_OUT"
    const val EXTRA_PROFILE_FOUND = "com.termux.cybersyn.extra.PROFILE_FOUND"
    const val EXTRA_PROFILE_ENABLED = "com.termux.cybersyn.extra.PROFILE_ENABLED"
    const val EXTRA_PROFILE_CONTEXT_COUNT = "com.termux.cybersyn.extra.PROFILE_CONTEXT_COUNT"
    const val EXTRA_TASK_COUNT = "com.termux.cybersyn.extra.TASK_COUNT"
    const val EXTRA_PROFILE_COUNT = "com.termux.cybersyn.extra.PROFILE_COUNT"
    const val EXTRA_ENABLED_PROFILE_COUNT = "com.termux.cybersyn.extra.ENABLED_PROFILE_COUNT"
    const val EXTRA_BUNDLE_JSON = "com.termux.cybersyn.extra.BUNDLE_JSON"
    const val EXTRA_BUNDLE_BASE64 = "com.termux.cybersyn.extra.BUNDLE_BASE64"
    const val EXTRA_ACKNOWLEDGE_RISK = "com.termux.cybersyn.extra.ACKNOWLEDGE_RISK"
    const val EXTRA_REPLACE_BY_NAME = "com.termux.cybersyn.extra.REPLACE_BY_NAME"
    const val EXTRA_INSERTED_TASKS = "com.termux.cybersyn.extra.INSERTED_TASKS"
    const val EXTRA_INSERTED_PROFILES = "com.termux.cybersyn.extra.INSERTED_PROFILES"
    const val EXTRA_IMPORT_WARNINGS = "com.termux.cybersyn.extra.IMPORT_WARNINGS"
    const val EXTRA_OUTPUT_PATH = "com.termux.cybersyn.extra.OUTPUT_PATH"

    const val VARIABLE_EXTRA_PREFIX = "com.termux.cybersyn.var."
    private val variableNamePattern = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")

    fun isValidVariableName(name: String): Boolean = variableNamePattern.matches(name)

    fun variableExtraName(variableName: String): String {
        require(isValidVariableName(variableName)) { "Invalid variable name." }
        return VARIABLE_EXTRA_PREFIX + variableName
    }
}

class AutomationTargetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val response = runCatching {
                when (intent.action) {
                    AutomationTargetContract.ACTION_RUN_TASK -> runTask(context.applicationContext, intent)
                    AutomationTargetContract.ACTION_SET_PROFILE_ENABLED -> setProfileEnabled(intent)
                    AutomationTargetContract.ACTION_QUERY_STATUS -> queryStatus(intent)
                    AutomationTargetContract.ACTION_IMPORT_BUNDLE -> importBundle(intent)
                    else -> failure("Unsupported action: ${intent.action}")
                }
            }.getOrElse { failure(it.message ?: "Automation target request failed") }
            try {
                pending.setResultCode(response.resultCode)
                pending.setResultExtras(response.extras)
            } catch (e: Exception) {
                AppLogger.error(TAG, "Failed to publish automation target result", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun runTask(appContext: Context, intent: Intent): TargetResponse {
        val db = CybersynApp_NoHilt.db
        val task = resolveTask(intent)
            ?: return failure("Task not found. Provide ${AutomationTargetContract.EXTRA_TASK_ID} or ${AutomationTargetContract.EXTRA_TASK_NAME}.")

        val suppliedVariables = extractVariables(intent.extras)
        // A task that never returns (the self-recursive watchdog family — task.run -> wait ->
        // task.run itself again, forever, by design) used to hang this whole broadcast's
        // goAsync() pending result until Android's own ANR watchdog killed the app ~60s later
        // (real incident 2026-07-31, see project_cybersyn_runtask_anr_bug memory). withTimeoutOrNull
        // cancels the execution (not just the wait) once this fires, so the caller gets a clean
        // "still running" response instead of an ANR — RUN_TASK was never a meaningful way to
        // invoke a self-recursive task anyway; use its underlying action directly for that.
        val result = withTimeoutOrNull(5_000) {
            executeAndLogTask(
                appContext = appContext,
                db = db,
                task = task,
                source = "External intent",
                metadata = listOf("Variables: ${suppliedVariables.size} provided"),
                initialVariables = suppliedVariables,
                logTag = TAG,
            )
        }

        if (result == null) {
            return TargetResponse(
                Activity.RESULT_OK,
                Bundle().apply {
                    putBoolean(AutomationTargetContract.EXTRA_TASK_TIMED_OUT, true)
                    putBoolean(AutomationTargetContract.EXTRA_TASK_SUCCESS, false)
                },
            )
        }

        return TargetResponse(
            if (result.report.success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply {
                putBoolean(AutomationTargetContract.EXTRA_TASK_SUCCESS, result.report.success)
                putLong(AutomationTargetContract.EXTRA_TASK_DURATION_MS, result.report.durationMs)
            },
        )
    }

    private suspend fun setProfileEnabled(intent: Intent): TargetResponse {
        val db = CybersynApp_NoHilt.db
        val profile = resolveProfile(intent)
            ?: return failure("Profile not found. Provide ${AutomationTargetContract.EXTRA_PROFILE_ID} or ${AutomationTargetContract.EXTRA_PROFILE_NAME}.")
        val enabled = intent.getBooleanExtra(AutomationTargetContract.EXTRA_ENABLED, profile.enabled)
        if (enabled && profile.requiresRiskAcknowledgement) {
            return failure("Imported profile requires in-app power review before its first enable.")
        }
        db.profileDao().update(profile.copy(enabled = enabled).toEntity())
        return TargetResponse(
            Activity.RESULT_OK,
            Bundle().apply {
                putBoolean(AutomationTargetContract.EXTRA_PROFILE_FOUND, true)
                putBoolean(AutomationTargetContract.EXTRA_PROFILE_ENABLED, enabled)
            },
        )
    }

    private suspend fun queryStatus(intent: Intent): TargetResponse {
        val db = CybersynApp_NoHilt.db
        val profileEntities = db.profileDao().getAll()
        val tasks = db.taskDao().getAll()
        val profile = resolveProfileEntity(intent, profileEntities)?.toDomain()
        return TargetResponse(
            Activity.RESULT_OK,
            Bundle().apply {
                putInt(AutomationTargetContract.EXTRA_TASK_COUNT, tasks.size)
                putInt(AutomationTargetContract.EXTRA_PROFILE_COUNT, profileEntities.size)
                putInt(
                    AutomationTargetContract.EXTRA_ENABLED_PROFILE_COUNT,
                    profileEntities.count { it.enabled && !it.requiresRiskAcknowledgement },
                )
                putBoolean(AutomationTargetContract.EXTRA_PROFILE_FOUND, profile != null)
                profile?.let {
                    putBoolean(AutomationTargetContract.EXTRA_PROFILE_ENABLED, it.enabled)
                    putInt(AutomationTargetContract.EXTRA_PROFILE_CONTEXT_COUNT, it.contexts.size)
                }
            },
        )
    }

    private suspend fun importBundle(intent: Intent): TargetResponse {
        val rawJson = intent.getStringExtra(AutomationTargetContract.EXTRA_BUNDLE_JSON)
            ?: intent.getStringExtra(AutomationTargetContract.EXTRA_BUNDLE_BASE64)
                ?.let { encoded -> String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8) }
            ?: return failure("Bundle import needs ${AutomationTargetContract.EXTRA_BUNDLE_JSON} or ${AutomationTargetContract.EXTRA_BUNDLE_BASE64}.")
        val bundle = OpenTaskerBundleCodec.decode(rawJson)
        val report = OpenTaskerBundleRepository(CybersynApp_NoHilt.db).importBundle(bundle)
        if (intent.getBooleanExtra(AutomationTargetContract.EXTRA_ACKNOWLEDGE_RISK, false)) {
            acknowledgeImportedProfiles(bundle.profiles.map { it.name }.toSet(), enable = intent.getBooleanExtra(AutomationTargetContract.EXTRA_ENABLED, false))
        }
        return TargetResponse(
            Activity.RESULT_OK,
            Bundle().apply {
                putInt(AutomationTargetContract.EXTRA_INSERTED_TASKS, report.insertedTasks)
                putInt(AutomationTargetContract.EXTRA_INSERTED_PROFILES, report.insertedProfiles)
                putString(AutomationTargetContract.EXTRA_IMPORT_WARNINGS, (report.warnings + report.lossyWarnings).joinToString("\n"))
            },
        )
    }

    private suspend fun acknowledgeImportedProfiles(profileNames: Set<String>, enable: Boolean) {
        if (profileNames.isEmpty()) return
        val db = CybersynApp_NoHilt.db
        db.profileDao().getAll()
            .filter { it.name in profileNames && it.requiresRiskAcknowledgement }
            .forEach { profile ->
                db.profileDao().update(profile.copy(enabled = enable, requiresRiskAcknowledgement = false))
            }
    }

    private suspend fun resolveTask(intent: Intent) =
        intent.getLongExtra(AutomationTargetContract.EXTRA_TASK_ID, 0L)
            .takeIf { it > 0 }
            ?.let { CybersynApp_NoHilt.db.taskDao().getById(it)?.toDomain() }
            ?: intent.getStringExtra(AutomationTargetContract.EXTRA_TASK_NAME)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { name ->
                    CybersynApp_NoHilt.db.taskDao().getAll()
                        .firstOrNull { it.name.equals(name, ignoreCase = true) }
                        ?.toDomain()
                }

    private suspend fun resolveProfile(intent: Intent) =
        resolveProfileEntity(intent, CybersynApp_NoHilt.db.profileDao().getAll())?.toDomain()

    private fun resolveProfileEntity(
        intent: Intent,
        profiles: List<com.termux.cybersyn.core.storage.ProfileEntity>,
    ) =
        intent.getLongExtra(AutomationTargetContract.EXTRA_PROFILE_ID, 0L)
            .takeIf { it > 0 }
            ?.let { id -> profiles.firstOrNull { it.id == id } }
            ?: intent.getStringExtra(AutomationTargetContract.EXTRA_PROFILE_NAME)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { name -> profiles.firstOrNull { it.name.equals(name, ignoreCase = true) } }

    private fun extractVariables(extras: Bundle?): Map<String, String> {
        if (extras == null) return emptyMap()
        return extras.keySet()
            .asSequence()
            .filter { it.startsWith(AutomationTargetContract.VARIABLE_EXTRA_PREFIX) }
            .sorted() // deterministic which variables survive the cap
            .mapNotNull { key ->
                val name = key.removePrefix(AutomationTargetContract.VARIABLE_EXTRA_PREFIX)
                if (!AutomationTargetContract.isValidVariableName(name)) return@mapNotNull null
                val value = extras.getString(key) ?: return@mapNotNull null
                name to value.take(MAX_VARIABLE_VALUE_CHARS)
            }
            .take(MAX_SUPPLIED_VARIABLES)
            .toMap()
    }

    private fun failure(message: String): TargetResponse {
        AppLogger.warn(TAG, message)
        return TargetResponse(
            Activity.RESULT_CANCELED,
            Bundle().apply { putString(AutomationTargetContract.EXTRA_ERROR, message) },
        )
    }

    companion object {
        private const val TAG = "AutomationTargetReceiver"
        private const val MAX_VARIABLE_VALUE_CHARS = 4_096
        private const val MAX_SUPPLIED_VARIABLES = 64
    }
}

private data class TargetResponse(
    val resultCode: Int,
    val extras: Bundle,
)
