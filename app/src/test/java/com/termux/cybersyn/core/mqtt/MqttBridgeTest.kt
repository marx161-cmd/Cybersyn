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

/** MQTT filter matching used to route hub messages to collectors. */
class MqttTopicMatchTest {
    private fun m(f: String, t: String) = MqttBridge.topicMatches(f, t)

    @org.junit.Test fun exact() {
        org.junit.Assert.assertTrue(m("cybersyn/file/offer", "cybersyn/file/offer"))
        org.junit.Assert.assertFalse(m("cybersyn/file/offer", "cybersyn/file/offer2"))
        org.junit.Assert.assertFalse(m("cybersyn/file/offer", "cybersyn/file"))
        org.junit.Assert.assertFalse(m("cybersyn/file", "cybersyn/file/offer"))
    }

    @org.junit.Test fun plusIsExactlyOneLevel() {
        org.junit.Assert.assertTrue(m("cybersyn/+/event", "cybersyn/comrade/event"))
        org.junit.Assert.assertFalse(m("cybersyn/+/event", "cybersyn/a/b/event"))
        org.junit.Assert.assertFalse(m("cybersyn/+/event", "cybersyn/event"))
    }

    @org.junit.Test fun hashIsRestOfTree() {
        org.junit.Assert.assertTrue(m("cybersyn/#", "cybersyn/a/b/c"))
        org.junit.Assert.assertTrue(m("cybersyn/#", "cybersyn"))
        org.junit.Assert.assertTrue(m("#", "anything/at/all"))
        org.junit.Assert.assertFalse(m("cybersyn/#", "other/a"))
    }
}
