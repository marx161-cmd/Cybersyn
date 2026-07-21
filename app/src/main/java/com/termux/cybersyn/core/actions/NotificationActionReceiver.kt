package com.termux.cybersyn.core.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.termux.cybersyn.app.OpenTaskerApp_NoHilt
import com.termux.cybersyn.core.engine.executeAndLogTask
import com.termux.cybersyn.core.engine.logSkippedRun
import com.termux.cybersyn.core.logging.AppLogger
import com.termux.cybersyn.core.storage.recoveryMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_NOTIFICATION_BUTTON) return
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L).takeIf { it > 0 }
        val legacyTaskName = intent.getStringExtra(EXTRA_TASK_NAME)
        val reference = taskId?.let { NotificationTaskReference.Id(it) }
            ?: legacyTaskName?.let { NotificationTaskReference.LegacyName(it) }
            ?: return
        val buttonLabel = intent.getStringExtra(EXTRA_BUTTON_LABEL)
            ?: legacyTaskName
            ?: "Task ${taskId ?: "unknown"}"

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val db = OpenTaskerApp_NoHilt.db
                val entities = if (taskId != null) {
                    listOfNotNull(db.taskDao().getById(taskId))
                } else {
                    db.taskDao().getAll()
                }
                val resolution = NotificationTaskBindings.resolve(
                    reference = reference,
                    candidates = entities.map { NotificationTaskCandidate(it.id, it.name) },
                )
                if (resolution !is NotificationTaskResolution.Bound) {
                    AppLogger.warn(
                        TAG,
                        "Notification button '$buttonLabel' did not run: ${NotificationTaskBindings.failureMessage(resolution)}",
                    )
                    return@launch
                }
                val entity = entities.single { it.id == resolution.task.id }
                val decoded = entity.toDomainDecodeResult()
                val issue = decoded.issue
                if (issue != null) {
                    val reason = issue.recoveryMessage()
                    AppLogger.error(TAG, "Notification button '$buttonLabel' blocked: $reason")
                    logSkippedRun(
                        db = db,
                        task = decoded.value,
                        source = SOURCE,
                        reason = reason,
                        metadata = listOf("button=$buttonLabel"),
                    )
                    return@launch
                }
                val task = decoded.value
                val result = executeAndLogTask(
                    appContext = context.applicationContext,
                    db = db,
                    task = task,
                    source = SOURCE,
                    metadata = listOf("button=$buttonLabel"),
                )
                val status = if (result.report.success) "succeeded" else "failed"
                AppLogger.info(TAG, "Notification button '$buttonLabel' -> ${task.name} $status (${result.report.durationMs}ms)")
            } catch (e: Exception) {
                AppLogger.error(TAG, "Notification button '$buttonLabel' failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_NOTIFICATION_BUTTON = "com.termux.cybersyn.action.NOTIFICATION_BUTTON"
        const val EXTRA_TASK_ID = "com.termux.cybersyn.extra.TASK_ID"
        /** Compatibility only for PendingIntents created before immutable ID bindings shipped. */
        const val EXTRA_TASK_NAME = "com.termux.cybersyn.extra.TASK_NAME"
        const val EXTRA_BUTTON_LABEL = "com.termux.cybersyn.extra.BUTTON_LABEL"
        const val SOURCE = "Notification action"
        private const val TAG = "OpenTasker"
    }
}
