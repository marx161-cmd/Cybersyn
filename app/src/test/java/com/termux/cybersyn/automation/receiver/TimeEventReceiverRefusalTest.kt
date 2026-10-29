package com.termux.cybersyn.automation.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeEventReceiverRefusalTest {
    @Test
    fun recognisesTheApi31RefusalClassByName() {
        assertTrue(namesBackgroundStartRefusal(listOf("java.lang.RuntimeException", "android.app.ForegroundServiceStartNotAllowedException")))
        assertFalse(namesBackgroundStartRefusal(listOf("java.lang.IllegalStateException")))
    }

    @Test
    fun recognisesThePreApi31RefusalMessage() {
        val error = IllegalStateException("Not allowed to start service Intent { cmp=x/.AutomationService }: app is in background")
        assertTrue(isBackgroundStartRefusal(error))
        assertTrue(isBackgroundStartRefusal(RuntimeException("wrapped", error)))
    }

    @Test
    fun otherFailuresStillGetARecovery() {
        assertFalse(isBackgroundStartRefusal(SecurityException("Permission denial")))
        assertFalse(isBackgroundStartRefusal(IllegalStateException(null as String?)))
    }
}
