package com.termux.cybersyn.input

abstract class PointerAccelerationProfile {

    class MouseDelta(@JvmField var x: Float = 0f, @JvmField var y: Float = 0f)

    abstract fun touchMoved(deltaX: Float, deltaY: Float, eventTime: Long)

    abstract fun commitAcceleratedMouseDelta(reusedObject: MouseDelta): MouseDelta
}
