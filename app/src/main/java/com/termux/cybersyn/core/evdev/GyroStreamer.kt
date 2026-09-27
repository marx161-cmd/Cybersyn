package com.termux.cybersyn.core.evdev

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.termux.cybersyn.core.logging.AppLogger
import com.termux.cybersyn.core.mqtt.MqttBridge

/**
 * Streams the game rotation vector to `android/sensor` while a gyro hold is active, in the
 * same JSON the trackpad stub sends ({"type":"android.sensor.game_rotation_vector",
 * "values":[x,y,z,w]}), which cybersyn-hid-relay turns into pointing. Runs on its own
 * thread; MqttBridge.publish reuses one persistent helper process, so ~50 Hz is cheap.
 */
object GyroStreamer : SensorEventListener {
    private const val TAG = "GyroStreamer"
    private const val TOPIC = "android/sensor"

    private var thread: HandlerThread? = null
    private var sensorManager: SensorManager? = null
    private var ctx: Context? = null

    @Synchronized
    fun start(context: Context) {
        if (thread != null) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (sensor == null) {
            AppLogger.error(TAG, "no game rotation vector sensor")
            return
        }
        val t = HandlerThread("gyro-stream").also { it.start() }
        thread = t
        sensorManager = sm
        ctx = context.applicationContext
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, Handler(t.looper))
    }

    @Synchronized
    fun stop() {
        sensorManager?.unregisterListener(this)
        thread?.quitSafely()
        thread = null
        sensorManager = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val c = ctx ?: return
        val v = event.values
        val x = v[0]; val y = v[1]; val z = v[2]
        val w = if (v.size >= 4) v[3] else Math.sqrt(maxOf(0.0, 1.0 - (x * x + y * y + z * z).toDouble())).toFloat()
        MqttBridge.publish(c, TOPIC,
            "{\"type\":\"android.sensor.game_rotation_vector\",\"values\":[$x,$y,$z,$w]}")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
