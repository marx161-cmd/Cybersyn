package com.termux.cybersyn.core.plugins.locale

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.termux.cybersyn.core.external.AutomationTargetReceiver
import com.termux.cybersyn.core.logging.AppLogger

/**
 * Exposes Cybersyn as a Locale-compatible setting plugin so Tasker, MacroDroid,
 * and other Locale hosts can invoke approved Cybersyn tasks.
 *
 * Protocol:
 * - Host starts [LocaleSettingEditActivity] with ACTION_EDIT_SETTING
 * - User picks a task, activity returns a bundle with taskId + taskName
 * - Host fires [LocaleSettingFireReceiver] with ACTION_FIRE_SETTING and the saved bundle
 * - Receiver dispatches the task through the existing automation pipeline
 */
object LocalePluginTarget {
    const val BUNDLE_KEY_TASK_ID = "com.termux.cybersyn.locale.TASK_ID"
    const val BUNDLE_KEY_TASK_NAME = "com.termux.cybersyn.locale.TASK_NAME"
    const val BUNDLE_KEY_GRANT = "com.termux.cybersyn.locale.GRANT"
    private const val TAG = "LocalePluginTarget"

    fun buildResultBundle(taskId: Long, taskName: String, grant: String): Bundle =
        Bundle().apply {
            putLong(BUNDLE_KEY_TASK_ID, taskId)
            putString(BUNDLE_KEY_TASK_NAME, taskName)
            putString(BUNDLE_KEY_GRANT, grant)
        }

    fun buildBlurb(taskName: String): String =
        "Run task: $taskName"

    fun parseTaskId(bundle: Bundle?): Long? {
        if (bundle == null) return null
        val id = bundle.getLong(BUNDLE_KEY_TASK_ID, -1L)
        return if (id > 0) id else null
    }

    fun parseTaskName(bundle: Bundle?): String? =
        bundle?.getString(BUNDLE_KEY_TASK_NAME)?.ifBlank { null }

    fun parseGrant(bundle: Bundle?): String? =
        bundle?.getString(BUNDLE_KEY_GRANT)?.ifBlank { null }
}

class LocaleSettingFireReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LocalePluginContract.ACTION_FIRE_SETTING) return

        val bundle = intent.getBundleExtra(LocalePluginContract.EXTRA_BUNDLE)
        val taskId = LocalePluginTarget.parseTaskId(bundle)
        val taskName = LocalePluginTarget.parseTaskName(bundle) ?: "unknown"
        val grant = LocalePluginTarget.parseGrant(bundle)

        if (taskId == null) {
            AppLogger.warn("LocaleSettingFireReceiver", "Missing or invalid task ID in Locale bundle")
            return
        }

        if (!LocaleGrantStore(context).isValid(grant, taskId)) {
            AppLogger.warn(
                "LocaleSettingFireReceiver",
                "Rejected Locale fire for taskId=$taskId: missing, forged, mutated, or revoked grant",
            )
            return
        }

        AppLogger.info("LocaleSettingFireReceiver", "Locale fire: taskId=$taskId name=$taskName")

        val runIntent = Intent(context, AutomationTargetReceiver::class.java).apply {
            action = "com.termux.cybersyn.action.RUN_TASK"
            putExtra("com.termux.cybersyn.extra.TASK_ID", taskId)
            putExtra("com.termux.cybersyn.extra.SOURCE", "locale_plugin")
        }
        context.sendOrderedBroadcast(runIntent, "com.termux.cybersyn.permission.AUTOMATION")
    }
}
