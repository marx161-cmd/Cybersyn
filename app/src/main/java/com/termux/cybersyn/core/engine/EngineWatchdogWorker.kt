package com.termux.cybersyn.core.engine

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.termux.cybersyn.automation.scheduler.TimeEventScheduler
import com.termux.cybersyn.app.CybersynApp_NoHilt
import com.termux.cybersyn.core.logging.AppLogger
import java.util.concurrent.TimeUnit

/** Periodic backstop for a killed/timed-out engine and a dropped self-rescheduling time alarm. */
class EngineWatchdogWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val scheduler = TimeEventScheduler(applicationContext)
        val heartbeat = EngineHeartbeatStore(applicationContext).read()
        ChildProcessAudit.check()
        // wal_checkpoint returns a row, and execSQL() is documented as not for statements
        // that return data -- it can no-op instead of checkpointing. query() runs it properly
        // and hands back (busy, log_pages, checkpointed_pages); busy != 0 means a reader held
        // it off and the WAL did not shrink, which is worth seeing rather than swallowing.
        runCatching {
            CybersynApp_NoHilt.db.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                    if (cursor.moveToFirst()) {
                        val busy = cursor.getInt(0)
                        if (busy != 0) {
                            AppLogger.warn(TAG, "WAL checkpoint blocked (busy=$busy, log=${cursor.getInt(1)} pages)")
                        }
                    }
                }
        }.onFailure { AppLogger.warn(TAG, "WAL checkpoint failed", it) }
        return runCatching {
            if (heartbeat.needsRecovery(now)) {
                scheduler.scheduleRecovery(now)
                AppLogger.warn(TAG, "Engine heartbeat stale; scheduled an alarm-backed restart")
            } else {
                scheduler.scheduleNextMinute(now)
                AppLogger.debug(TAG, "Engine heartbeat healthy; verified time alarm")
            }
            Result.success()
        }.getOrElse { error ->
            AppLogger.error(TAG, "Engine watchdog could not re-arm time delivery", error)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "EngineWatchdogWorker"
        internal const val WORK_NAME = "engine_watchdog"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<EngineWatchdogWorker>(
                15,
                TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
