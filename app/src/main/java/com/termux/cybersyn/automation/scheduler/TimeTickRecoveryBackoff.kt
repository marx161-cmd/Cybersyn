package com.termux.cybersyn.automation.scheduler

import android.content.Context

/**
 * How long each consecutive time-tick recovery alarm waits, and how many there are. A recovery
 * fires the same receiver that just failed, so a flat five-second retry woke the device every five
 * seconds for as long as the failure lasted. After the last step the receiver stops booking them;
 * [com.termux.cybersyn.core.engine.EngineWatchdogWorker] still re-arms time delivery on its own
 * schedule.
 *
 * Ported from upstream OpenTasker f5e709b7, where the counter lives in `ExpectedTriggerLedger`;
 * this fork has no ledger, so the attempt count is kept in its own preferences file and survives
 * process death the same way.
 */
internal val RECOVERY_BACKOFF_MILLIS: List<Long> = listOf(5_000L, 30_000L, 300_000L)

/** The delay for recovery attempt [attempts] (0-based), or null once the backoff is used up. */
internal fun recoveryDelayForAttempt(attempts: Int): Long? = RECOVERY_BACKOFF_MILLIS.getOrNull(attempts)

class TimeTickRecoveryBackoff(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** The delay before the next recovery alarm, or null once the backoff is used up. */
    fun claimRecoveryDelay(): Long? {
        val attempts = preferences.getInt(KEY_ATTEMPTS, 0).coerceAtLeast(0)
        val delay = recoveryDelayForAttempt(attempts) ?: return null
        preferences.edit().putInt(KEY_ATTEMPTS, attempts + 1).apply()
        return delay
    }

    /** A tick reached the engine, so the next failure starts the backoff from the beginning. */
    fun reset() {
        if (preferences.getInt(KEY_ATTEMPTS, 0) != 0) {
            preferences.edit().putInt(KEY_ATTEMPTS, 0).apply()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "time_tick_recovery"
        const val KEY_ATTEMPTS = "attempts"
    }
}
