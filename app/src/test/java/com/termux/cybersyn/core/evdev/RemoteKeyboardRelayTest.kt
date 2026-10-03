package com.termux.cybersyn.core.evdev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteKeyboardRelayTest {

    @Test
    fun forwardsKeyPressAndRelease() {
        assertEquals("42 1", RemoteKeyboardRelay.payload(1, 42, 1))
        assertEquals("42 0", RemoteKeyboardRelay.payload(1, 42, 0))
    }

    @Test
    fun dropsAutorepeatAndNonKeyEvents() {
        assertNull(RemoteKeyboardRelay.payload(1, 42, 2))
        assertNull(RemoteKeyboardRelay.payload(2, 0, 1))
    }

    @Test
    fun matchesAnyRapooKeyboardInterfaceButNotItsMouse() {
        assertTrue(RemoteKeyboardRelay.isRemoteKeyboard("RAPOO Keyboard-1 Keyboard"))
        assertTrue(RemoteKeyboardRelay.isRemoteKeyboard("RAPOO Keyboard-7 Keyboard"))
        assertFalse(RemoteKeyboardRelay.isRemoteKeyboard("RAPOO Keyboard-1 Mouse"))
        assertFalse(RemoteKeyboardRelay.isRemoteKeyboard("gpio_keys"))
    }
}
