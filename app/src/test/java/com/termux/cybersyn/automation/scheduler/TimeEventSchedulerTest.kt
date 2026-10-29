package com.termux.cybersyn.automation.scheduler

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeEventSchedulerTest {
    @Test
    fun nextMinuteBoundaryRoundsUpFromMiddleOfMinute() {
        assertEquals(120_000L, TimeEventScheduler.nextMinuteBoundaryMillis(61_234L))
    }

    @Test
    fun nextMinuteBoundaryAdvancesWhenAlreadyOnBoundary() {
        assertEquals(180_000L, TimeEventScheduler.nextMinuteBoundaryMillis(120_000L))
    }

    @Test
    fun recoveryAlarmIsScheduledPromptlyAfterTimeout() {
        assertEquals(15_000L, TimeEventScheduler.recoveryTriggerAtMillis(10_000L))
    }

    @Test
    fun recoveryNeverLandsPastTheNextMinuteTick() {
        assertEquals(60_000L, TimeEventScheduler.recoveryTriggerAtMillis(50_000L, delayMillis = 300_000L))
        assertEquals(35_000L, TimeEventScheduler.recoveryTriggerAtMillis(5_000L, delayMillis = 30_000L))
    }

    @Test
    fun recoveryBackoffStepsThenStops() {
        assertEquals(5_000L, recoveryDelayForAttempt(0))
        assertEquals(30_000L, recoveryDelayForAttempt(1))
        assertEquals(300_000L, recoveryDelayForAttempt(2))
        assertEquals(null, recoveryDelayForAttempt(3))
    }
}
