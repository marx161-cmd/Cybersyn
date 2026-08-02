package com.termux.cybersyn.core.external

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import androidx.core.content.ContextCompat
import com.termux.cybersyn.app.CybersynApp_NoHilt
import com.termux.cybersyn.app.BuildConfig
import com.termux.cybersyn.core.engine.AutomationService
import com.termux.cybersyn.core.engine.executeAndLogTask
import com.termux.cybersyn.core.logging.AppLogger
import com.termux.cybersyn.core.transfer.OpenTaskerBundleCodec
import com.termux.cybersyn.core.transfer.OpenTaskerBundleRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class AutomationCliReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                AutomationTargetContract.ACTION_IMPORT_BUNDLE,
                AutomationTargetContract.ACTION_EXPORT_BUNDLE,
                AutomationTargetContract.ACTION_RUN_TASK,
                AutomationTargetContract.ACTION_SET_PROFILE_ENABLED,
            )
        ) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val extras = Bundle()
            val resultCode = runCatching {
                when (intent.action) {
                    AutomationTargetContract.ACTION_IMPORT_BUNDLE -> importBundle(context, intent, extras)
                    AutomationTargetContract.ACTION_EXPORT_BUNDLE -> exportBundle(intent, extras)
                    AutomationTargetContract.ACTION_RUN_TASK -> runTask(context.applicationContext, intent, extras)
                    AutomationTargetContract.ACTION_SET_PROFILE_ENABLED -> setProfileEnabled(intent, extras)
                    else -> Activity.RESULT_CANCELED
                }
            }.getOrElse { error ->
                AppLogger.error(TAG, "CLI import failed", error)
                extras.putString(AutomationTargetContract.EXTRA_ERROR, error.message ?: "CLI import failed")
                Activity.RESULT_CANCELED
            }
            pending.setResultCode(resultCode)
            pending.setResultExtras(extras)
            pending.finish()
        }
    }

    private suspend fun importBundle(context: Context, intent: Intent, extras: Bundle): Int {
        val rawJson = intent.getStringExtra(AutomationTargetContract.EXTRA_BUNDLE_JSON)
            ?: intent.getStringExtra(AutomationTargetContract.EXTRA_BUNDLE_BASE64)
                ?.let { encoded -> String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8) }
            ?: error("Bundle import needs BUNDLE_JSON or BUNDLE_BASE64")
        val bundle = OpenTaskerBundleCodec.decode(rawJson)
        if (intent.getBooleanExtra(AutomationTargetContract.EXTRA_REPLACE_BY_NAME, false)) {
            replaceExistingByName(bundle.tasks.map { it.name }.toSet(), bundle.profiles.map { it.name }.toSet())
        }
        val report = OpenTaskerBundleRepository(CybersynApp_NoHilt.db).importBundle(bundle)
        runCatching {
            CybersynApp_NoHilt.db.openHelper.writableDatabase
                .execSQL("PRAGMA wal_checkpoint(FULL)")
        }
        ContextCompat.startForegroundService(
            context.applicationContext,
            Intent(context, AutomationService::class.java)
                .setAction(AutomationService.ACTION_RELOAD_PROFILES),
        )
        if (intent.getBooleanExtra(AutomationTargetContract.EXTRA_ACKNOWLEDGE_RISK, false)) {
            val enable = intent.getBooleanExtra(AutomationTargetContract.EXTRA_ENABLED, false)
            acknowledgeImportedProfiles(bundle.profiles.map { it.name }.toSet(), enable)
        }
        extras.putInt(AutomationTargetContract.EXTRA_INSERTED_TASKS, report.insertedTasks)
        extras.putInt(AutomationTargetContract.EXTRA_INSERTED_PROFILES, report.insertedProfiles)
        extras.putString(AutomationTargetContract.EXTRA_IMPORT_WARNINGS, (report.warnings + report.lossyWarnings).joinToString("\n"))
        return Activity.RESULT_OK
    }

    private suspend fun setProfileEnabled(intent: Intent, extras: Bundle): Int {
        val db = CybersynApp_NoHilt.db
        val id = intent.getLongExtra(AutomationTargetContract.EXTRA_PROFILE_ID, 0L)
        val name = intent.getStringExtra(AutomationTargetContract.EXTRA_PROFILE_NAME)?.trim().orEmpty()
        val profile = db.profileDao().getAll().firstOrNull { candidate ->
            (id > 0 && candidate.id == id) || (name.isNotBlank() && candidate.name.equals(name, ignoreCase = true))
        } ?: error("Profile not found")
        val enabled = intent.getBooleanExtra(AutomationTargetContract.EXTRA_ENABLED, profile.enabled)
        db.profileDao().update(profile.copy(enabled = enabled, requiresRiskAcknowledgement = false))
        extras.putBoolean(AutomationTargetContract.EXTRA_PROFILE_FOUND, true)
        extras.putBoolean(AutomationTargetContract.EXTRA_PROFILE_ENABLED, enabled)
        return Activity.RESULT_OK
    }

    private suspend fun exportBundle(intent: Intent, extras: Bundle): Int {
        val outputPath = intent.getStringExtra(AutomationTargetContract.EXTRA_OUTPUT_PATH)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: error("Missing output path")
        val output = File(outputPath)
        val allowedRoot = File(TERMUX_HOME).canonicalFile
        val canonicalOutput = output.canonicalFile
        if (!canonicalOutput.path.startsWith(allowedRoot.path + File.separator)) {
            error("Output path must be under $TERMUX_HOME")
        }
        val bundle = OpenTaskerBundleRepository(CybersynApp_NoHilt.db).exportBundle(
            appVersion = BuildConfig.VERSION_NAME,
            name = "Cybersyn CLI export",
            description = "Exported by cybersynctl",
        )
        canonicalOutput.parentFile?.mkdirs()
        canonicalOutput.writeText(OpenTaskerBundleCodec.encode(bundle))
        canonicalOutput.setReadable(true, false)
        extras.putString(AutomationTargetContract.EXTRA_OUTPUT_PATH, canonicalOutput.path)
        return Activity.RESULT_OK
    }

    private suspend fun runTask(appContext: Context, intent: Intent, extras: Bundle): Int {
        val task = resolveTask(intent) ?: error("Task not found")
        // See AutomationTargetReceiver.runTask() for why this is timeout-bounded: a
        // self-recursive task (task.run -> wait -> task.run itself again, forever) never
        // returns, and awaiting it directly here used to hang this broadcast's goAsync()
        // pending result until Android's ANR watchdog killed the whole app ~60s later (real
        // incident 2026-07-31, see project_cybersyn_runtask_anr_bug memory).
        val result = withTimeoutOrNull(5_000) {
            executeAndLogTask(
                appContext = appContext,
                db = CybersynApp_NoHilt.db,
                task = task,
                source = "CLI",
                logTag = TAG,
            )
        }
        if (result == null) {
            extras.putBoolean(AutomationTargetContract.EXTRA_TASK_TIMED_OUT, true)
            extras.putBoolean(AutomationTargetContract.EXTRA_TASK_SUCCESS, false)
            return Activity.RESULT_OK
        }
        extras.putBoolean(AutomationTargetContract.EXTRA_TASK_SUCCESS, result.report.success)
        extras.putLong(AutomationTargetContract.EXTRA_TASK_DURATION_MS, result.report.durationMs)
        return if (result.report.success) Activity.RESULT_OK else Activity.RESULT_CANCELED
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

    private suspend fun acknowledgeImportedProfiles(profileNames: Set<String>, enable: Boolean) {
        if (profileNames.isEmpty()) return
        val db = CybersynApp_NoHilt.db
        db.profileDao().getAll()
            .filter { it.name in profileNames && it.requiresRiskAcknowledgement }
            .forEach { profile ->
                db.profileDao().update(profile.copy(enabled = enable, requiresRiskAcknowledgement = false))
            }
    }

    private suspend fun replaceExistingByName(taskNames: Set<String>, profileNames: Set<String>) {
        val db = CybersynApp_NoHilt.db
        if (profileNames.isNotEmpty()) {
            db.profileDao().getAll()
                .filter { profile -> profile.name in profileNames }
                .forEach { db.profileDao().delete(it) }
        }
        if (taskNames.isNotEmpty()) {
            db.taskDao().getAll()
                .filter { task -> task.name in taskNames }
                .forEach { db.taskDao().delete(it) }
        }
    }

    companion object {
        private const val TAG = "AutomationCliReceiver"
        private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    }
}
