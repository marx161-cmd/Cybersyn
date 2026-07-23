package com.termux.cybersyn.core.controls

import android.app.PendingIntent
import android.content.Intent
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.service.controls.DeviceTypes
import android.service.controls.actions.ControlAction
import android.service.controls.templates.StatelessTemplate
import com.termux.cybersyn.app.CybersynApp_NoHilt
import com.termux.cybersyn.app.MainActivity
import com.termux.cybersyn.core.engine.executeAndLogTask
import com.termux.cybersyn.core.logging.AppLogger
import com.termux.cybersyn.core.model.Task
import java.util.concurrent.Flow
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TaskControlsService : ControlsProviderService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun createPublisherForAllAvailable(): Flow.Publisher<Control> = ControlsPublisher {
        loadTasks().map { task -> availableControl(task) }
    }

    override fun createPublisherFor(controlIds: MutableList<String>): Flow.Publisher<Control> = ControlsPublisher {
        val requestedIds = controlIds.toSet()
        loadTasks()
            .filter { task -> controlIdFor(task.id) in requestedIds }
            .map { task -> statefulControl(task) }
    }

    override fun performControlAction(controlId: String, action: ControlAction, consumer: Consumer<Int>) {
        val taskId = taskIdFor(controlId)
        if (taskId == null) {
            consumer.accept(ControlAction.RESPONSE_FAIL)
            return
        }
        consumer.accept(ControlAction.RESPONSE_OK)
        scope.launch {
            val task = CybersynApp_NoHilt.db.taskDao().getById(taskId)?.toDomainDecodeResult()?.value
            if (task == null) {
                AppLogger.warn(TAG, "Device Controls task not found: $controlId")
                return@launch
            }
            runCatching {
                executeAndLogTask(
                    appContext = applicationContext,
                    db = CybersynApp_NoHilt.db,
                    task = task,
                    source = SOURCE_DEVICE_CONTROLS,
                    logTag = TAG,
                )
            }.onFailure { error ->
                AppLogger.error(TAG, "Device Controls task failed: ${task.name}", error)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun loadTasks(): List<Task> = CybersynApp_NoHilt.db.taskDao().getAll()
        .mapNotNull { entity ->
            val decoded = entity.toDomainDecodeResult()
            if (decoded.issue != null) {
                AppLogger.warn(TAG, "Skipping corrupt Device Controls task ${entity.id}: ${decoded.issue.message}")
            }
            decoded.value
        }
        .sortedWith(compareBy<Task> { it.name.lowercase() }.thenBy { it.id })

    private fun availableControl(task: Task): Control = Control.StatelessBuilder(controlIdFor(task.id), appIntent(task))
        .setTitle(task.name)
        .setSubtitle(subtitleFor(task))
        .setDeviceType(DeviceTypes.TYPE_GENERIC_ON_OFF)
        .build()

    private fun statefulControl(task: Task): Control = Control.StatefulBuilder(controlIdFor(task.id), appIntent(task))
        .setTitle(task.name)
        .setSubtitle(subtitleFor(task))
        .setStatus(Control.STATUS_OK)
        .setControlTemplate(StatelessTemplate(controlIdFor(task.id)))
        .setDeviceType(DeviceTypes.TYPE_GENERIC_ON_OFF)
        .build()

    private fun appIntent(task: Task): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_TASK_ID, task.id)
        return PendingIntent.getActivity(
            this,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun subtitleFor(task: Task): String = when (task.actions.size) {
        1 -> "1 action"
        else -> "${task.actions.size} actions"
    }

    private fun controlIdFor(taskId: Long): String = "$CONTROL_PREFIX$taskId"

    private fun taskIdFor(controlId: String): Long? = controlId
        .removePrefix(CONTROL_PREFIX)
        .takeIf { it != controlId }
        ?.toLongOrNull()

    private inner class ControlsPublisher(
        private val loadControls: suspend () -> List<Control>,
    ) : Flow.Publisher<Control> {
        override fun subscribe(subscriber: Flow.Subscriber<in Control>) {
            subscriber.onSubscribe(object : Flow.Subscription {
                private var started = false
                private var cancelled = false

                override fun request(n: Long) {
                    if (n <= 0) {
                        subscriber.onError(IllegalArgumentException("request must be positive"))
                        return
                    }
                    if (started || cancelled) return
                    started = true
                    scope.launch {
                        runCatching { loadControls() }
                            .onSuccess { controls ->
                                controls.forEach { control ->
                                    if (!cancelled) subscriber.onNext(control)
                                }
                                if (!cancelled) subscriber.onComplete()
                            }
                            .onFailure { error ->
                                if (!cancelled) subscriber.onError(error)
                            }
                    }
                }

                override fun cancel() {
                    cancelled = true
                }
            })
        }
    }

    companion object {
        private const val TAG = "TaskControlsService"
        private const val CONTROL_PREFIX = "task:"
        private const val EXTRA_TASK_ID = "com.termux.cybersyn.extra.CONTROL_TASK_ID"
        private const val SOURCE_DEVICE_CONTROLS = "Device Controls"
    }
}
