package com.termux.cybersyn.core.evdev

import com.termux.cybersyn.core.logging.AppLogger
import com.termux.cybersyn.core.scripting.TermuxScriptBackend
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Declarative config for hardware-key triggers, read from
 * `~/.termux/tasker/key_triggers.json` (same directory the watchdog scripts live in, so
 * everything hand-editable about the phone side is in one place).
 *
 * Each entry becomes a [KeyTrigger] whose id is published as an `external_trigger` event
 * — the same path the Quick Tap stub already uses — so a key press drives an ordinary
 * Cybersyn profile and task dispatch stays entirely in the automation engine. Nothing
 * here knows what a trigger *does*.
 *
 * Example:
 * ```json
 * { "triggers": [
 *     { "id": "vol_down_double", "key": "VOLUME_DOWN", "click": "DOUBLE", "consume": false },
 *     { "id": "vol_both_long",   "keys": ["VOLUME_UP","VOLUME_DOWN"], "mode": "PARALLEL" }
 * ] }
 * ```
 *
 * Absent or malformed file → no triggers. That is a working state, not an error: gyro,
 * hold and browser nav all live on the raw down/up path and are unaffected.
 */
object KeyTriggerConfig {
    private const val TAG = "KeyTriggerConfig"

    val configFile: File
        get() = File(TermuxScriptBackend.TERMUX_HOME, ".termux/tasker/key_triggers.json")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Android keycodes for the keys we can actually grab. */
    private val KEY_NAMES = mapOf(
        "VOLUME_UP" to 24,
        "VOLUME_DOWN" to 25,
        "POWER" to 26,
    )

    @Serializable
    private data class ConfigFile(val triggers: List<Entry> = emptyList())

    @Serializable
    private data class Entry(
        val id: String,
        val key: String? = null,
        val keys: List<String> = emptyList(),
        val click: String = "SHORT",
        val mode: String = "SINGLE",
        @SerialName("consume") val consume: Boolean = false,
    )

    fun load(): List<KeyTrigger> {
        val file = configFile
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ConfigFile.serializer(), file.readText())
                .triggers
                .mapNotNull { it.toTrigger() }
        }.onFailure {
            AppLogger.error(TAG, "Failed to parse ${file.path}; no key triggers registered", it)
        }.getOrDefault(emptyList())
    }

    private fun Entry.toTrigger(): KeyTrigger? {
        val names = if (keys.isNotEmpty()) keys else listOfNotNull(key)
        if (names.isEmpty()) {
            AppLogger.error(TAG, "Trigger '$id' names no keys; skipped")
            return null
        }
        val clickType = when (click.uppercase()) {
            "SHORT", "SHORT_PRESS" -> ClickType.SHORT_PRESS
            "LONG", "LONG_PRESS" -> ClickType.LONG_PRESS
            "DOUBLE", "DOUBLE_PRESS" -> ClickType.DOUBLE_PRESS
            else -> {
                AppLogger.error(TAG, "Trigger '$id' has unknown click type '$click'; skipped")
                return null
            }
        }
        // Power is never consumable: the helper's callback handles KEY_POWER before it
        // consults the consume set, so long-press power keeps resolving natively. A
        // config asking to consume it would silently not do so.
        if (consume && names.any { it.uppercase() == "POWER" }) {
            AppLogger.error(TAG, "Trigger '$id' cannot consume POWER; consume ignored for it")
        }
        val triggerKeys = names.mapNotNull { name ->
            val code = KEY_NAMES[name.uppercase()]
            if (code == null) {
                AppLogger.error(TAG, "Trigger '$id' references unknown key '$name'; skipped")
                return null
            }
            TriggerKey(
                code = code,
                clickType = clickType,
                consumeEvent = consume && name.uppercase() != "POWER",
            )
        }
        val triggerMode = when (mode.uppercase()) {
            "SINGLE", "UNDEFINED" -> TriggerMode.Undefined
            "PARALLEL" -> TriggerMode.Parallel(clickType)
            "SEQUENCE" -> TriggerMode.Sequence
            else -> {
                AppLogger.error(TAG, "Trigger '$id' has unknown mode '$mode'; skipped")
                return null
            }
        }
        if (triggerMode is TriggerMode.Undefined && triggerKeys.size > 1) {
            AppLogger.error(TAG, "Trigger '$id' lists multiple keys but mode SINGLE; skipped")
            return null
        }
        return runCatching { KeyTrigger(id = id, keys = triggerKeys, mode = triggerMode) }
            .onFailure { AppLogger.error(TAG, "Trigger '$id' rejected", it) }
            .getOrNull()
    }
}
