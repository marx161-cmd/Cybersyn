package com.termux.cybersyn.core.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import java.util.concurrent.atomic.AtomicInteger

/**
 * One ongoing notification per transfer, same ID from start to finish so it morphs in place
 * (progress -> done/failed) instead of stacking duplicates — same shape as KDE Connect's
 * per-file transfer notification. Uses the stock Notification#setProgress, which populates
 * Android's own EXTRA_PROGRESS/EXTRA_PROGRESS_MAX/EXTRA_PROGRESS_INDETERMINATE extras, so any
 * NotificationListenerService (a punch-hole progress ring included) can read live progress
 * with zero custom protocol on our end.
 */
class TransferProgressNotifier(private val context: Context, private val title: String) {
    private val id = NEXT_ID.incrementAndGet()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var lastPercent = -1

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "File transfer progress", NotificationManager.IMPORTANCE_LOW)
            manager?.createNotificationChannel(channel)
        }
    }

    fun start(indeterminate: Boolean) {
        post(builder().setProgress(100, 0, indeterminate))
    }

    /** Throttled to whole-percent changes so this isn't a notify() call per 64KB chunk. */
    fun update(current: Long, total: Long) {
        if (total <= 0) return
        val percent = ((current * 100) / total).toInt().coerceIn(0, 100)
        if (percent == lastPercent) return
        lastPercent = percent
        post(builder().setProgress(100, percent, false).setContentText("$percent%"))
    }

    fun finish(success: Boolean, message: String) {
        post(
            builder()
                .setOngoing(false)
                .setAutoCancel(true)
                .setProgress(0, 0, false)
                .setSmallIcon(
                    if (success) android.R.drawable.stat_sys_download_done
                    else android.R.drawable.stat_notify_error,
                )
                .setContentText(message),
        )
    }

    private fun builder(): Notification.Builder {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        return b.setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
    }

    private fun post(builder: Notification.Builder) {
        manager?.notify(id, builder.build())
    }

    companion object {
        private const val CHANNEL_ID = "cybersyn_transfer_progress"
        private val NEXT_ID = AtomicInteger(9100)
    }
}
