package com.termux.cybersyn.core.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers the stdio `topic\tpayload` line parsing shared by the helper and mosquitto fallback. */
class MqttBridgeTest {

    @Test
    fun parseLine_splitsTopicAndPayloadOnFirstTab() {
        val m = MqttBridge.parseLine("cybersyn/comrade/event\tpong")
        assertEquals("cybersyn/comrade/event", m?.topic)
        assertEquals("pong", m?.payload)
    }

    @Test
    fun parseLine_keepsLaterTabsInPayload() {
        val m = MqttBridge.parseLine("t\ta\tb")
        assertEquals("t", m?.topic)
        assertEquals("a\tb", m?.payload)
    }

    @Test
    fun parseLine_emptyPayloadWhenNoTab() {
        val m = MqttBridge.parseLine("cybersyn/comrade/event")
        assertEquals("cybersyn/comrade/event", m?.topic)
        assertEquals("", m?.payload)
    }

    @Test
    fun parseLine_blankLineIsNull() {
        assertNull(MqttBridge.parseLine(""))
    }
}
