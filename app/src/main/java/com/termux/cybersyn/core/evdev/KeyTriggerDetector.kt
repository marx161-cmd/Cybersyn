package com.termux.cybersyn.core.evdev

/**
 * Turns the raw evdev stream (code + 0 up / 1 down / 2 repeat) into trigger matches,
 * classifying presses as short, long or double.
 *
 * Ported from Key Mapper's detection concepts. The hard part it solves, which the old
 * hand-rolled routing did not, is that **a short press cannot be recognised at key-down**:
 *
 *  - it might still become a long press, if held past [longPressDelayMs]
 *  - it might still become a double press, if pressed again within [doublePressTimeoutMs]
 *
 * so a short press is only emitted once both possibilities are ruled out. That is why
 * [onKeyEvent] returns a decision immediately (for the consume/passthrough answer the
 * native callback needs synchronously) while matches are delivered later via [onTrigger].
 *
 * Not thread-safe: drive it from a single reader thread.
 */
class KeyTriggerDetector(
    private val triggers: List<KeyTrigger>,
    private val onTrigger: (KeyTrigger) -> Unit,
    /** Held at least this long is a long press. Android's own default is 500ms. */
    val longPressDelayMs: Long = 500,
    /** Second press within this window is a double press. */
    val doublePressTimeoutMs: Long = 300,
    /** Max gap between consecutive keys of a Sequence trigger. */
    val sequenceTimeoutMs: Long = 1000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val downAt = mutableMapOf<Int, Long>()
    private val lastUpAt = mutableMapOf<Int, Long>()
    private val heldKeys = mutableSetOf<Int>()
    private val longPressFired = mutableSetOf<Int>()
    private val pendingShort = mutableMapOf<Int, Long>()
    private val sequence = mutableListOf<Pair<Int, Long>>()

    /** Every code any trigger references — anything else is never consumed. */
    val watchedCodes: Set<Int> = triggers.flatMap { it.codes }.toSet()

    /**
     * Feed one event. Returns true if it should be consumed (swallowed), false to let the
     * native layer re-emit it. Answered synchronously; matches arrive via [onTrigger].
     */
    fun onKeyEvent(code: Int, value: Int): Boolean {
        if (code !in watchedCodes) return false
        val t = now()

        when (value) {
            1 -> onDown(code, t)
            2 -> onRepeat(code, t)
            0 -> onUp(code, t)
        }

        // Consume only if some trigger involving this key asks to. Decided on the key,
        // not the match, because the match may not be known yet.
        return triggers.any { code in it.codes && it.consumes }
    }

    /**
     * Drive time-based transitions: long presses that crossed the threshold while held,
     * and short presses whose double-press window expired. Call periodically (the reader
     * loop's idle tick is fine) or matches will be late, not lost.
     */
    fun tick() {
        val t = now()

        for (code in heldKeys.toList()) {
            val since = downAt[code] ?: continue
            if (code !in longPressFired && t - since >= longPressDelayMs) {
                longPressFired.add(code)
                pendingShort.remove(code)
                fireMatching(code, ClickType.LONG_PRESS)
            }
        }

        for ((code, at) in pendingShort.toList()) {
            if (t - at >= doublePressTimeoutMs) {
                pendingShort.remove(code)
                fireMatching(code, ClickType.SHORT_PRESS)
            }
        }

        if (sequence.isNotEmpty() && t - sequence.last().second > sequenceTimeoutMs) {
            sequence.clear()
        }
    }

    /** Forget all in-flight state, e.g. after the helper restarts. */
    fun reset() {
        downAt.clear(); lastUpAt.clear(); heldKeys.clear()
        longPressFired.clear(); pendingShort.clear(); sequence.clear()
    }

    private fun onDown(code: Int, t: Long) {
        heldKeys.add(code)
        downAt[code] = t
        longPressFired.remove(code)

        // Double press: a second down inside the window, and the first short press must
        // not have been emitted yet (that's what pendingShort defers it for).
        val prevUp = lastUpAt[code]
        if (prevUp != null && t - prevUp <= doublePressTimeoutMs && pendingShort.containsKey(code)) {
            pendingShort.remove(code)
            fireMatching(code, ClickType.DOUBLE_PRESS)
            lastUpAt.remove(code)
            return
        }

        sequence.add(code to t)
        matchSequence()
        matchParallel(t)
    }

    private fun onRepeat(code: Int, t: Long) {
        // Kernel autorepeat is a cheap long-press clock, but tick() is authoritative so
        // the classification is identical whether or not autorepeat is enabled.
        if (code in heldKeys && code !in longPressFired) {
            val since = downAt[code] ?: return
            if (t - since >= longPressDelayMs) {
                longPressFired.add(code)
                pendingShort.remove(code)
                fireMatching(code, ClickType.LONG_PRESS)
            }
        }
    }

    private fun onUp(code: Int, t: Long) {
        heldKeys.remove(code)
        val since = downAt.remove(code)
        lastUpAt[code] = t

        if (code in longPressFired) {
            longPressFired.remove(code)
            return
        }
        if (since == null) return

        // Short press. Deferred if any trigger wants a double press on this key, since
        // firing now would pre-empt it.
        if (wantsDoublePress(code)) {
            pendingShort[code] = t
        } else {
            fireMatching(code, ClickType.SHORT_PRESS)
        }
    }

    private fun wantsDoublePress(code: Int): Boolean = triggers.any { trigger ->
        trigger.keys.any { it.code == code && it.clickType == ClickType.DOUBLE_PRESS } ||
            (trigger.mode as? TriggerMode.Parallel)?.clickType == ClickType.DOUBLE_PRESS
    }

    /** Single-key triggers whose key and click type match. */
    private fun fireMatching(code: Int, clickType: ClickType) {
        for (trigger in triggers) {
            if (trigger.mode !is TriggerMode.Undefined) continue
            val key = trigger.keys.singleOrNull() ?: continue
            if (key.code == code && key.clickType == clickType) onTrigger(trigger)
        }
    }

    /** Parallel triggers: every key held right now, nothing extra. */
    private fun matchParallel(t: Long) {
        for (trigger in triggers) {
            val mode = trigger.mode as? TriggerMode.Parallel ?: continue
            if (mode.clickType != ClickType.SHORT_PRESS) continue
            if (heldKeys == trigger.codes) onTrigger(trigger)
        }
    }

    /** Sequence triggers: the tail of the press history equals the trigger's key order. */
    private fun matchSequence() {
        for (trigger in triggers) {
            if (trigger.mode !is TriggerMode.Sequence) continue
            val want = trigger.keys.map { it.code }
            if (sequence.size < want.size) continue
            val tail = sequence.takeLast(want.size)
            if (tail.map { it.first } != want) continue
            val within = tail.zipWithNext().all { (a, b) -> b.second - a.second <= sequenceTimeoutMs }
            if (within) {
                onTrigger(trigger)
                sequence.clear()
            }
        }
    }
}
