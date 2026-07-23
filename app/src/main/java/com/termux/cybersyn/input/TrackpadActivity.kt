package com.termux.cybersyn.input

import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.termux.cybersyn.core.mqtt.MqttBridge
import kotlin.math.abs

class TrackpadActivity : ComponentActivity(),
    GestureDetector.OnGestureListener,
    GestureDetector.OnDoubleTapListener,
    MousePadGestureDetector.OnGestureListener,
    SensorEventListener {

    private var gyroEnabled = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                sensorManager.registerListener(
                    this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
                    SensorManager.SENSOR_DELAY_GAME
                )
            } else {
                sensorManager.unregisterListener(this)
            }
        }

    private val standardDpi = 240f
    private var dpiMultiplier = 1f
    private var sensitivity = 1f
    private var scrollDirection = 1
    private var scrollCoefficient = 1.0
    private var gyroSensitivity = 100
    private var dragging = false
    private var isScrolling = false
    private var accumulatedScroll = 0.0
    private var prevX = 0f
    private var prevY = 0f

    private lateinit var gestureDetector: GestureDetector
    private lateinit var multiTapDetector: MousePadGestureDetector
    private lateinit var sensorManager: SensorManager
    private lateinit var accelerationProfile: PointerAccelerationProfile
    private val mouseDelta = PointerAccelerationProfile.MouseDelta()

    companion object {
        private const val SURFACE_TINT = 0x1A003D1F
        private const val MIN_SCROLL_DISTANCE = 2.5f
        private const val TOPIC_MOUSE = "cybersyn/hid/mouse"
        private const val TOPIC_CLICK = "cybersyn/hid/click"
        private const val TOPIC_SCROLL = "cybersyn/hid/scroll"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        sensorManager = ContextCompat.getSystemService(this, SensorManager::class.java)!!
        dpiMultiplier = standardDpi / resources.displayMetrics.xdpi
        accelerationProfile = PointerAccelerationProfileFactory.getProfileWithName("medium")

        gestureDetector = GestureDetector(this, this).apply {
            setOnDoubleTapListener(this@TrackpadActivity)
        }
        multiTapDetector = MousePadGestureDetector(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(SURFACE_TINT))) {
                    AndroidView(
                        factory = { ctx ->
                            View(ctx).apply {
                                setBackgroundColor(SURFACE_TINT.toInt())
                                setOnTouchListener { _, event -> handleTouch(event) }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (gyroEnabled) {
            sensorManager.registerListener(
                this,
                sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
                SensorManager.SENSOR_DELAY_GAME
            )
        }
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    // --- Touch pipeline ---

    private fun handleTouch(event: MotionEvent): Boolean {
        if (multiTapDetector.onTouchEvent(event)) return true
        if (gestureDetector.onTouchEvent(event)) return true

        if (isScrolling) {
            if (event.action == MotionEvent.ACTION_UP) isScrolling = false
            else return false
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                prevX = event.x
                prevY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.x - prevX) * dpiMultiplier * sensitivity
                val dy = (event.y - prevY) * dpiMultiplier * sensitivity
                accelerationProfile.touchMoved(dx, dy, event.eventTime)
                val delta = accelerationProfile.commitAcceleratedMouseDelta(mouseDelta)
                if (delta.x != 0f || delta.y != 0f) {
                    MqttBridge.publish(this, TOPIC_MOUSE, "${delta.x},${delta.y}")
                }
                prevX = event.x
                prevY = event.y
            }
        }
        return true
    }

    // --- GestureDetector callbacks ---

    override fun onDown(e: MotionEvent) = false
    override fun onShowPress(e: MotionEvent) {}
    override fun onSingleTapUp(e: MotionEvent) = false

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        publishClick("left")
        return true
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        publishClick("double")
        return true
    }

    override fun onDoubleTapEvent(e: MotionEvent) = true

    override fun onLongPress(e: MotionEvent) {
        publishClick("hold")
        dragging = true
    }

    override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float) = false

    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
        if (e2.pointerCount <= 1) return false
        isScrolling = true
        accumulatedScroll += dy * scrollCoefficient
        if (abs(accumulatedScroll) > MIN_SCROLL_DISTANCE) {
            MqttBridge.publish(this, TOPIC_SCROLL, "${scrollDirection * accumulatedScroll}")
            accumulatedScroll = 0.0
        }
        return true
    }

    // --- Multi-finger tap callbacks ---

    override fun onTripleFingerTap(ev: MotionEvent): Boolean {
        publishClick("middle")
        return true
    }

    override fun onDoubleFingerTap(ev: MotionEvent): Boolean {
        publishClick("right")
        return true
    }

    // --- Gyro ---

    override fun onSensorChanged(event: SensorEvent) {
        var x = -event.values[2] * 70 * (gyroSensitivity / 100f)
        var y = -event.values[0] * 70 * (gyroSensitivity / 100f)

        if (x in -0.25f..0.25f) x = 0f else x *= (gyroSensitivity / 100f)
        if (y in -0.25f..0.25f) y = 0f else y *= (gyroSensitivity / 100f)

        if (x != 0f || y != 0f) {
            MqttBridge.publish(this, TOPIC_MOUSE, "$x,$y")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --- MQTT helpers ---

    private fun publishClick(type: String) {
        if (dragging) {
            MqttBridge.publish(this, TOPIC_CLICK, "release")
            dragging = false
        } else {
            MqttBridge.publish(this, TOPIC_CLICK, type)
        }
    }
}
