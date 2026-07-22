package com.termux.cybersyn.core.external

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import com.termux.cybersyn.app.CybersynApp_NoHilt
import com.termux.cybersyn.core.logging.AppLogger
import com.termux.cybersyn.core.transfer.OpenTaskerBundleCodec
import com.termux.cybersyn.core.transfer.OpenTaskerBundleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AutomationCliReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                AutomationTargetContract.ACTION_IMPORT_BUNDLE,
                AutomationTargetContract.ACTION_SET_PROFILE_ENABLED,
            )
        ) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val extras = Bundle()
            val resultCode = runCatching {
                when (intent.action) {
                    AutomationTargetContract.ACTION_IMPORT_BUNDLE -> importBundle(intent, extras)
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

    private suspend fun importBundle(intent: Intent, extras: Bundle): Int {
        val rawJson = intent.getStringExtra(AutomationTargetContract.EXTRA_BUNDLE_JSON)
            ?: intent.getStringExtra(AutomationTargetContract.EXTRA_BUNDLE_BASE64)
                ?.let { encoded -> String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8) }
            ?: error("Bundle import needs BUNDLE_JSON or BUNDLE_BASE64")
        val bundle = OpenTaskerBundleCodec.decode(rawJson)
        val report = OpenTaskerBundleRepository(CybersynApp_NoHilt.db).importBundle(bundle)
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

    private suspend fun acknowledgeImportedProfiles(profileNames: Set<String>, enable: Boolean) {
        if (profileNames.isEmpty()) return
        val db = CybersynApp_NoHilt.db
        db.profileDao().getAll()
            .filter { it.name in profileNames && it.requiresRiskAcknowledgement }
            .forEach { profile ->
                db.profileDao().update(profile.copy(enabled = enable, requiresRiskAcknowledgement = false))
            }
    }

    companion object {
        private const val TAG = "AutomationCliReceiver"
    }
}
