package com.termux.cybersyn.stub2;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The phone's sensors, folded into the remote — as publishers for lanes that already exist,
 * never as new wire. Two lanes, neither of which needs a line of comrade-side change:
 *
 * <b>Orientation → camera (the gyromouse, reused).</b> `cybersyn-hid-relay` on comrade already
 * consumes `android/sensor` (a game-rotation-vector quaternion, exactly the shape SensorSpot
 * published) and `android/clutch` (ON = "this is centre", recalibrated on the spot), writing
 * `gyro_state.txt`, which renderer.py's gyro block turns into mouse_cam yaw/pitch plus twist →
 * zoom/forward and `u_steer`. So every mouse-steering shader follows the phone with no shader
 * edit — the satellite just becomes the phone-side publisher that lane has been waiting for
 * (SensorSpot and p30control are both stopped; the lane was dark).
 *
 * <b>Attitude → the steering wheel.</b> The phone's roll is published as the pad's own
 * STEERING_WHEEL event for whatever id the current pad uses, and renderer.py already applies wheel
 * angles to `mouse_cam["roll"]` — the one camera axis no stick can reach, and the one widget this
 * remote could not drive. A held tilt is an absolute angle, so the phone's posture *is* the
 * horizon; releasing the lane publishes 0 and unwinds it, the same self-centring the app does.
 *
 * Rules kept from the pad work: outbound only through the ingest FIFO; no new sockets; sensors are
 * registered ONLY while a lane is armed; arming is always explicit and is dropped on pause, so the
 * phone can never keep steering something nobody is looking at.
 */
public final class SensorLane implements SensorEventListener {

    private static final String TAG = "cybersyn-sensors";

    /** The relay's own ceiling is 60 Hz; a sample every ~18 ms keeps the FIFO quiet. */
    private static final long SAMPLE_MIN_MS = 18;
    /** The wheel is a stable offset, not a gesture: it is re-stamped, so it needs less. */
    private static final long WHEEL_MIN_MS = 33;
    /** Republished at least this often even when unchanged — the renderer's TTL reads silence as rest. */
    private static final long WHEEL_HEARTBEAT_MS = 1000;

    /** Topic the relay listens on for the quaternion; SensorSpot's own shape. */
    private static final String SENSOR_TOPIC = "android/sensor";
    /** Topic the relay listens on for the gyro gate: ON/OFF, "ON" re-zeros the reference. */
    private static final String CLUTCH_TOPIC = "android/clutch";
    private static final String TYPE_GAME_ROTATION = "android.sensor.game_rotation_vector";
    private static final String TYPE_HEAD_TRACKER = "android.sensor.head_tracker";

    /**
     * Tilt the phone's right edge down → positive wheel angle. The wheel's angle is applied by the
     * renderer as camera roll, and the app's own convention is that clockwise (right edge down) is
     * positive, so this sign should not need flipping — it is one constant if it ever does.
     */
    private static final float TILT_ROLL_SIGN = 1.0f;

    /** A widget that can be driven by the tilt lane. */
    public interface WheelReceiver {
        void onSensorAngle(float degrees);

        /** The lane let go: the widget is its own again. */
        void onSensorReleased();

        /** True while a finger owns the wheel, so the sensor stands back. */
        boolean isHeld();
    }

    /** The UI watches this so the pad switches and the header never disagree. */
    public interface Listener {
        void onLaneStateChanged();
    }

    private final PadPublisher publisher;
    private final SensorManager sensors;
    private final Sensor gameRotation;
    private final Sensor headTracker;
    private final Sensor proximity;

    private boolean cameraArmed;
    private boolean tiltArmed;
    private boolean useHeadTracker;
    private boolean proximitySawFar;
    private boolean awaitingTiltReference;
    private float tiltReference;
    private long lastSampleAt;
    private long lastWheelAt;
    private float lastWheelDeg;
    private String wheelId;
    private WheelReceiver wheel;
    private Listener listener;

    public SensorLane(Context context, PadPublisher publisher) {
        this.publisher = publisher;
        SensorManager manager =
                (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.sensors = manager;
        this.gameRotation = manager == null ? null
                : manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        this.proximity = manager == null ? null
                : manager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        Sensor head = null;
        if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                head = manager.getDefaultSensor(Sensor.TYPE_HEAD_TRACKER);
            } catch (Throwable ignored) {
            }
        }
        this.headTracker = head;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    // ---- what the current pad offers -------------------------------------------------

    /**
     * Point the tilt lane at a pad's wheel. Without a wheel widget the lane is not available at
     * all — there would be nothing to represent the phone's attitude in, and quietly publishing
     * some other id would be a lie about what the pad contains.
     */
    public void setWheelTarget(String id, WheelReceiver receiver) {
        // Unwind against the *old* id first, so a pad switch (or a wheel-less pad) never leaves the
        // renderer holding a tilt whose id no longer exists.
        if (tiltArmed) armTilt(false);
        this.wheelId = id;
        this.wheel = receiver;
    }

    public boolean hasWheel() {
        return wheelId != null && wheel != null;
    }

    public boolean isCameraArmed() {
        return cameraArmed;
    }

    public boolean isTiltArmed() {
        return tiltArmed;
    }

    public boolean hasHeadTracker() {
        return headTracker != null;
    }

    public boolean isUsingHeadTracker() {
        return useHeadTracker && hasHeadTracker();
    }

    /** Prefer the dynamic head tracker when there is one: absolute, and already parsed by the relay. */
    public void setUseHeadTracker(boolean use) {
        if (use == useHeadTracker) return;
        useHeadTracker = use && hasHeadTracker();
        if (cameraArmed) syncRegistration();
        notifyListener();
    }

    // ---- arming ----------------------------------------------------------------------

    public void armCamera(boolean on) {
        if (on == cameraArmed) return;
        if (on && sensors == null) {
            Log.w(TAG, "no sensor service: camera lane unavailable");
            return;
        }
        cameraArmed = on;
        // ON is also the calibration: the phone's current attitude becomes "centre", so arming
        // never yanks the view — it only starts listening from where the phone already is.
        publisher.publish(CLUTCH_TOPIC, on ? "ON" : "OFF");
        syncRegistration();
        notifyListener();
    }

    public void armTilt(boolean on) {
        if (on && !hasWheel()) {
            Log.w(TAG, "no STEERING_WHEEL widget on this pad: tilt lane unavailable");
            return;
        }
        if (on == tiltArmed) return;
        tiltArmed = on;
        if (on) {
            lastWheelDeg = 0f;
            // Arming IS the reference, exactly like the gyro lane's ON: whatever posture the phone is
            // in when the switch is flipped becomes the wheel's zero, so arming can never snap the
            // horizon to it.
            awaitingTiltReference = true;
            syncRegistration();
        } else {
            // Unwind: the renderer applies wheel angles as deltas, so the lane handing the wheel
            // back must say "straight ahead" or the last tilt would stay baked into the roll.
            publishWheel(0f);
            lastWheelDeg = 0f;
            if (wheel != null) wheel.onSensorReleased();
            syncRegistration();
        }
        notifyListener();
    }

    /** Called when the surface goes away: nothing steers from off-screen. */
    public void disarmAll() {
        if (cameraArmed) armCamera(false);
        if (tiltArmed) armTilt(false);
    }

    public void shutdown() {
        disarmAll();
        sensorsUnregisterAll();
        listener = null;
    }

    private void syncRegistration() {
        sensorsUnregisterAll();
        if (sensors == null) return;
        // EITHER lane needs the quaternion: the camera lane sends it, the tilt lane derives its roll
        // from it. Registering it only for the camera lane left an armed tilt lane with no samples.
        if (cameraArmed || tiltArmed) {
            Sensor source = isUsingHeadTracker() ? headTracker : gameRotation;
            if (source != null) {
                sensors.registerListener(this, source, 20000, 0);
            } else {
                Log.w(TAG, "no usable orientation sensor");
            }
        }
        if ((cameraArmed || tiltArmed) && proximity != null) {
            sensors.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL, 0);
        }
    }

    private void sensorsUnregisterAll() {
        if (sensors != null) sensors.unregisterListener(this);
    }

    private void notifyListener() {
        if (listener != null) listener.onLaneStateChanged();
    }

    // ---- samples ---------------------------------------------------------------------

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();

        if (type == Sensor.TYPE_PROXIMITY) {
            // Safety interlock, deliberately paranoid: only trust it once it has reported "far",
            // so a sensor that only ever says "near" can never mute the lanes for no reason.
            float value = event.values.length > 0 ? event.values[0] : Float.NaN;
            float max = event.sensor.getMaximumRange();
            boolean near = value < Math.min(max - 0.01f, 1.0f);
            if (!near) {
                proximitySawFar = true;
            } else if (proximitySawFar && (cameraArmed || tiltArmed)) {
                Log.i(TAG, "proximity covered: disarming sensor lanes");
                disarmAll();
            }
            return;
        }

        if (!cameraArmed && !tiltArmed) return;

        float[] yawPitchRoll = null;
        float[] quaternion = null;
        if (type == Sensor.TYPE_HEAD_TRACKER && event.values.length >= 3) {
            yawPitchRoll = new float[]{event.values[0], event.values[1], event.values[2]};
        } else if (type == Sensor.TYPE_GAME_ROTATION_VECTOR && event.values.length >= 3) {
            quaternion = gameRotationQuaternion(event.values);
        } else {
            return;
        }

        long now = android.os.SystemClock.uptimeMillis();

        if (cameraArmed && now - lastSampleAt >= SAMPLE_MIN_MS) {
            lastSampleAt = now;
            if (yawPitchRoll != null) {
                publishSensor(TYPE_HEAD_TRACKER, yawPitchRoll);
            } else {
                publishSensor(TYPE_GAME_ROTATION, quaternion);
            }
        }

        if (tiltArmed && hasWheel()) {
            if (wheel != null && wheel.isHeld()) return;   // a finger has the wheel right now
            if (yawPitchRoll != null) {
                onRoll((float) Math.toDegrees(yawPitchRoll[2]));
            } else {
                onRoll((float) Math.toDegrees(rollOf(quaternion)));
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void onRoll(float degrees) {
        float deg = TILT_ROLL_SIGN * degrees;
        if (awaitingTiltReference) {
            awaitingTiltReference = false;
            tiltReference = deg;
            deg = 0f;
        } else {
            deg = wrap180(deg - tiltReference);
        }
        long now = android.os.SystemClock.uptimeMillis();
        boolean moved = Math.abs(deg - lastWheelDeg) > 0.05f;
        if (!moved && now - lastWheelAt < WHEEL_HEARTBEAT_MS) return;
        if (moved && now - lastWheelAt < WHEEL_MIN_MS) return;
        lastWheelAt = now;
        lastWheelDeg = deg;
        publishWheel(deg);
        if (wheel != null) wheel.onSensorAngle(deg);
    }

    /**
     * Android's game rotation vector gives (x, y, z); w is reconstructed as the positive root of
     * the unit quaternion. Publishing four values matters: both the relay and p30control ignore
     * anything shorter.
     */
    private static float[] gameRotationQuaternion(float[] values) {
        float x = values[0];
        float y = values[1];
        float z = values[2];
        float w = (float) Math.sqrt(Math.max(0.0, 1.0 - (x * x + y * y + z * z)));
        return new float[]{x, y, z, w};
    }

    /** The relay's own get_angles() roll, so the two ends agree about which way is which. */
    private static float rollOf(float[] q) {
        float x = q[0];
        float y = q[1];
        float z = q[2];
        float w = q[3];
        double t0 = 2.0 * (w * x + y * z);
        double t1 = 1.0 - 2.0 * (x * x + y * y);
        return (float) Math.atan2(t0, t1);
    }

    private void publishSensor(String type, float[] values) {
        JSONObject payload = new JSONObject();
        try {
            JSONArray array = new JSONArray();
            for (float v : values) array.put(round(v));
            payload.put("type", type);
            payload.put("values", array);
            payload.put("timestamp", System.currentTimeMillis());
        } catch (org.json.JSONException ignored) {
        }
        publisher.publish(SENSOR_TOPIC, payload.toString());
    }

    private void publishWheel(float degrees) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("id", wheelId);
            payload.put("type", "STEERING_WHEEL");
            payload.put("angle", round(degrees));
        } catch (org.json.JSONException ignored) {
        }
        publisher.publish(payload.toString());
    }

    private static double round(float v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** Keep the wheel inside ±180° so a half turn either way is the same place. */
    private static float wrap180(float deg) {
        float d = deg % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    /** Kept for the header's own reporting. */
    public String describe() {
        if (!cameraArmed && !tiltArmed) return "off";
        StringBuilder sb = new StringBuilder();
        if (cameraArmed) {
            sb.append(isUsingHeadTracker() ? "head→camera" : "gyro→camera");
        }
        if (tiltArmed) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append("tilt→wheel");
        }
        return sb.toString();
    }

    String wheelTargetId() {
        return wheelId;
    }
}
