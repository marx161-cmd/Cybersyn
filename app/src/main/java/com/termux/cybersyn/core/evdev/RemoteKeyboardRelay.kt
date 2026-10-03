package com.termux.cybersyn.core.evdev

import android.content.Context
import com.termux.cybersyn.core.mqtt.MqttBridge

/**
 * The RAPOO Bluetooth keyboard kept in another room, relayed keystroke-by-keystroke to
 * comrade.
 *
 * It is grabbed as an extra evdev target while present (the Rust watcher hot-grabs it on
 * connect and drops it on disconnect); every key is consumed on the Pixel and forwarded
 * raw to [TOPIC], where `cybersyn-hid-relay.py` replays it into a real uinput keyboard.
 * Consuming means the keyboard never types on the phone — phone touchscreen and other
 * input are untouched.
 *
 * Matching is deliberately loose. The RAPOO's evdev name carries a variable HID instance
 * number ("RAPOO Keyboard-1 Keyboard", "...-2..."), and the user only ever connects this
 * keyboard, so [TARGET_NAME_PATTERN] is a suffix wildcard on "Keyboard" and the product id
 * is unpinned. Only the built-in "gpio_keys" (exact name) and this keyboard are grabbed.
 */
object RemoteKeyboardRelay {
    /** Suffix wildcard understood by the native matcher (see grab_target.rs). */
    const val TARGET_NAME_PATTERN = "*Keyboard"

    const val TARGET_BUS = 0x05        // Bluetooth
    const val TARGET_VENDOR = 0x245a   // RAPOO
    const val ANY = 0xFFFF             // native matcher's "match any" sentinel

    /** Raw Linux keycode + value, consumed by the relay's uinput keyboard. */
    const val TOPIC = "cybersyn/hid/keyraw"

    private const val EV_KEY = 1
    private const val VALUE_RELEASE = 0
    private const val VALUE_PRESS = 1

    /** The suffix the native matcher keys on; kept in one place for the unit test. */
    private const val NAME_SUFFIX = "Keyboard"

    /**
     * The evdev device family to grab alongside the built-in keys. Empty extra codes: a
     * keyboard already advertises every key it can produce, unlike gpio_keys.
     */
    val grabTarget = GrabTargetKeyCode(
        name = TARGET_NAME_PATTERN,
        bus = TARGET_BUS,
        vendor = TARGET_VENDOR,
        product = ANY,
        extraKeyCodes = IntArray(0),
    )

    /**
     * True when a grabbed evdev device is the relayed keyboard. Mirrors the grab target's
     * name rule; the bus/vendor narrowing already happened at grab time.
     */
    fun isRemoteKeyboard(name: String): Boolean = name.endsWith(NAME_SUFFIX, ignoreCase = true)

    /**
     * MQTT payload for comrade, or null when the event must not be forwarded.
     *
     * Only key press/release are relayed. Autorepeat (value 2) is dropped: comrade's own
     * X server generates repeat from the held key, so forwarding the device's repeats too
     * would double them up.
     */
    fun payload(type: Int, code: Int, value: Int): String? {
        if (type != EV_KEY) return null
        if (value != VALUE_PRESS && value != VALUE_RELEASE) return null
        return "$code $value"
    }

    fun forward(context: Context, type: Int, code: Int, value: Int) {
        val payload = payload(type, code, value) ?: return
        MqttBridge.publish(context, TOPIC, payload)
    }
}
