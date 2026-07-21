package com.termux.cybersyn.input

import android.view.MotionEvent
import android.view.ViewConfiguration

class MousePadGestureDetector(
    private val gestureListener: OnGestureListener
) {
    private val tapTimeout = ViewConfiguration.getTapTimeout() + 100
    private var firstDownTime = 0L
    private var isGestureHandled = false

    interface OnGestureListener {
        fun onTripleFingerTap(ev: MotionEvent): Boolean
        fun onDoubleFingerTap(ev: MotionEvent): Boolean
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.action
        when (action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                isGestureHandled = false
                firstDownTime = event.eventTime
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val count = event.pointerCount
                if (event.eventTime - firstDownTime <= tapTimeout) {
                    if (count == 3) {
                        if (!isGestureHandled) {
                            isGestureHandled = gestureListener.onTripleFingerTap(event)
                        }
                    } else if (count == 2) {
                        if (!isGestureHandled) {
                            isGestureHandled = gestureListener.onDoubleFingerTap(event)
                        }
                    }
                }
                firstDownTime = 0
            }
        }
        return isGestureHandled
    }
}
