package com.termux.cybersyn.core.evdev

/**
 * Trigger model for grabbed hardware keys, ported from Key Mapper's concepts
 * (`base/trigger/TriggerKey.kt`, `TriggerMode.kt`, `keymaps/ClickType.kt`) rather than
 * its implementation -- Key Mapper's `KeyMapAlgorithm` is ~2000 lines welded to its own
 * KeyMap/Action/Constraint domain, which would duplicate Cybersyn's Task/Profile model.
 *
 * What's worth having is the part Cybersyn had no equivalent of: press classification
 * (short / long / double), multi-key triggers, and a per-key consume decision.
 */

/** How a key press is classified. Mirrors Key Mapper's ClickType. */
enum class ClickType {
    SHORT_PRESS,
    LONG_PRESS,
    DOUBLE_PRESS,
}

/** How the keys of a multi-key trigger relate to each other. */
sealed class TriggerMode {
    /** All keys held down together; the click type applies to the combination. */
    data class Parallel(val clickType: ClickType) : TriggerMode()

    /** Keys pressed one after another, in order, each within [KeyTriggerDetector.sequenceTimeoutMs]. */
    object Sequence : TriggerMode()

    /** Single-key trigger; the click type lives on the key itself. */
    object Undefined : TriggerMode()
}

/**
 * One key within a trigger.
 *
 * @param code Android keycode (KEYCODE_VOLUME_DOWN 25, ...) as delivered by
 *   EvdevRootHelper's EV_DOWN/EV_UP lines, which map from raw evdev via the native
 *   keylayout. The helper's own consume logic works in Linux codes (114/115/116);
 *   don't mix the two.
 * @param clickType how this key must be pressed for the trigger to match.
 * @param consumeEvent whether matching swallows the key. This is Key Mapper's
 *   "override key" behaviour: false lets the key through to Android as well, which is
 *   free here because the native layer re-emits anything the callback doesn't consume.
 */
data class TriggerKey(
    val code: Int,
    val clickType: ClickType = ClickType.SHORT_PRESS,
    val consumeEvent: Boolean = true,
)

/**
 * A trigger and the opaque id reported when it fires. The id is whatever the caller wants
 * to route on -- a Cybersyn task id, an action name, an enum.
 */
data class KeyTrigger(
    val id: String,
    val keys: List<TriggerKey>,
    val mode: TriggerMode = TriggerMode.Undefined,
) {
    init {
        require(keys.isNotEmpty()) { "trigger $id has no keys" }
    }

    /** Every key code this trigger cares about. */
    val codes: Set<Int> = keys.map { it.code }.toSet()

    /** True if any constituent key wants the event swallowed. */
    val consumes: Boolean = keys.any { it.consumeEvent }
}
