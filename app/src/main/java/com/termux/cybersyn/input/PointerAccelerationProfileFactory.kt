package com.termux.cybersyn.input

import kotlin.math.pow
import kotlin.math.sqrt

object PointerAccelerationProfileFactory {

    fun getProfileWithName(name: String): PointerAccelerationProfile = when (name) {
        "weaker" -> PolynomialProfile(0.25f)
        "weak" -> PolynomialProfile(0.5f)
        "medium" -> PolynomialProfile(1.0f)
        "strong" -> PolynomialProfile(1.5f)
        "stronger" -> PolynomialProfile(2.0f)
        "noacceleration" -> DefaultProfile()
        else -> DefaultProfile()
    }

    private class DefaultProfile : PointerAccelerationProfile() {
        var accumulatedX = 0.0f
        var accumulatedY = 0.0f

        override fun touchMoved(deltaX: Float, deltaY: Float, eventTime: Long) {
            accumulatedX += deltaX
            accumulatedY += deltaY
        }

        override fun commitAcceleratedMouseDelta(reusedObject: PointerAccelerationProfile.MouseDelta): PointerAccelerationProfile.MouseDelta {
            reusedObject.x = accumulatedX
            reusedObject.y = accumulatedY
            accumulatedY = 0f
            accumulatedX = 0f
            return reusedObject
        }
    }

    private abstract class SpeedBasedAccelerationProfile : PointerAccelerationProfile() {
        var accumulatedX = 0.0f
        var accumulatedY = 0.0f
        val freshThreshold: Long = 150

        private class TouchDeltaEvent(val x: Float, val y: Float, val time: Long)

        private val touchEventHistory = arrayOfNulls<TouchDeltaEvent>(32)

        fun addHistory(deltaX: Float, deltaY: Float, eventTime: Long) {
            System.arraycopy(touchEventHistory, 0, touchEventHistory, 1, touchEventHistory.size - 1)
            touchEventHistory[0] = TouchDeltaEvent(deltaX, deltaY, eventTime)
        }

        private fun speedFromTouchHistory(eventTime: Long): Pair<Float, Long> {
            var distanceMoved = 0.0f
            var deltaT: Long = 0
            for (entry in touchEventHistory) {
                if (entry == null) break
                if (eventTime - entry.time > freshThreshold) break
                distanceMoved += sqrt((entry.x * entry.x + entry.y * entry.y).toDouble()).toFloat()
                deltaT = eventTime - entry.time
            }
            return Pair(distanceMoved, deltaT)
        }

        private fun multiplierFromTouchHistory(eventTime: Long): Float {
            val (distanceMoved, deltaT) = speedFromTouchHistory(eventTime)
            val multiplier = if (deltaT == 0L) {
                0f
            } else {
                val speed = distanceMoved / (deltaT / 1000.0f)
                calculateMultiplier(speed)
            }
            return multiplier.coerceAtLeast(0.01f)
        }

        override fun touchMoved(deltaX: Float, deltaY: Float, eventTime: Long) {
            val multiplier = multiplierFromTouchHistory(eventTime)
            accumulatedX += deltaX * multiplier
            accumulatedY += deltaY * multiplier
            addHistory(deltaX, deltaY, eventTime)
        }

        abstract fun calculateMultiplier(speed: Float): Float

        override fun commitAcceleratedMouseDelta(reusedObject: PointerAccelerationProfile.MouseDelta): PointerAccelerationProfile.MouseDelta {
            reusedObject.x = accumulatedX.toInt().toFloat()
            reusedObject.y = accumulatedY.toInt().toFloat()
            accumulatedY %= 1.0f
            accumulatedX %= 1.0f
            return reusedObject
        }
    }

    private class PolynomialProfile(private val exponent: Float) : SpeedBasedAccelerationProfile() {
        override fun calculateMultiplier(speed: Float): Float {
            return ((speed / 600).toDouble().pow(exponent.toDouble())).toFloat()
        }
    }
}
