package com.termux.cybersyn.stub2;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.MotionEvent;

import org.json.JSONObject;

import java.util.Locale;

/**
 * A steering wheel: `{"id","type":"STEERING_WHEEL","angle"}` — the one camera axis (roll) no stick
 * can reach, and the app's own spring-return control: released by a finger it animates back to 0,
 * and the renderer applies angles as deltas, so the animation is what unwinds the roll.
 *
 * It is also the seat for the tilt lane: while that lane is armed the phone's roll IS this angle
 * (see {@link SensorLane}), so the phone's posture represents an orientation instead of a gesture.
 * A finger still wins — touch it and the sensor stands back until you let go, exactly like taking
 * the wheel from a driver.
 */
public final class WheelWidget extends Widget implements SensorLane.WheelReceiver {

    /** The app's animated return, same idea: release unwinds over a few frames, not instantly. */
    private static final long RETURN_MS = 260;

    private final int colorTrack;
    private final int colorKnob;
    private final int colorActive;

    private float angle;
    private boolean touched;
    private boolean returning;
    private long returnStart;
    private float returnFrom;
    private boolean sensorDriven;

    public WheelWidget(PadProfile.Item item, PadPublisher publisher, Haptics haptics) {
        super(item, publisher, haptics);
        this.colorTrack = item.propColor("backgroundColor", 0x66202020);
        this.colorKnob = item.propColor("handleColor", 0xFFD0D0D0);
        this.colorActive = item.propColor("checkedColor", 0xFF00A478);
    }

    /** 0° is straight up; clockwise (the phone's right edge tipping down) is positive. */
    private float angleAt(float x, float y) {
        double dx = x - bounds.centerX();
        double dy = bounds.centerY() - y;
        return (float) Math.toDegrees(Math.atan2(dx, dy));
    }

    @Override
    public boolean onTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                touched = true;
                returning = false;
                angle = angleAt(e.getX(), e.getY());
                publishAngle(angle);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!touched) return false;
                angle = angleAt(e.getX(), e.getY());
                publishAngle(angle);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!touched) return false;
                touched = false;
                // Handing back to the tilt lane: it re-stamps the phone's own angle next sample,
                // so there is nothing to unwind here.
                if (!sensorDriven) startReturn();
                return true;
            }
            default:
                return touched;
        }
    }

    private void startReturn() {
        returning = true;
        returnFrom = angle;
        returnStart = SystemClock.uptimeMillis();
        invalidate.run();
    }

    private void publishAngle(float deg) {
        JSONObject o = new JSONObject();
        try {
            o.put("angle", Math.round(deg * 100f) / 100f);
        } catch (org.json.JSONException ignored) {
        }
        publish(o);
    }

    // ---- the tilt lane -----------------------------------------------------------------

    @Override
    public void onSensorAngle(float degrees) {
        sensorDriven = true;
        returning = false;
        angle = degrees;
        invalidate.run();
    }

    @Override
    public void onSensorReleased() {
        sensorDriven = false;
        if (!touched) startReturn();
    }

    @Override
    public boolean isHeld() {
        return touched;
    }

    @Override
    public void draw(Canvas c) {
        // The self-centring animation ticks from here: one frame per redraw, publishing the
        // intermediate angles so the renderer unwinds the roll as smoothly as we draw it.
        if (returning) {
            float t = (SystemClock.uptimeMillis() - returnStart) / (float) RETURN_MS;
            if (t >= 1f) {
                returning = false;
                angle = 0f;
                publishAngle(0f);
            } else {
                angle = returnFrom * (1f - t * t);       // ease out
                publishAngle(angle);
                invalidate.run();
            }
        }

        float radius = Math.min(bounds.width(), bounds.height()) / 2f;
        float cx = bounds.centerX();
        float cy = bounds.centerY();
        boolean engaged = Math.abs(angle) > 0.5f;

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(colorTrack);
        c.drawCircle(cx, cy, radius, fill);

        line.setStrokeWidth(1.5f * c.getDensity());
        line.setColor(0x44FFFFFF);
        c.drawCircle(cx, cy, radius, line);

        // Tick marks, so a small angle is visible before the knob has travelled far.
        line.setStrokeWidth(2f * c.getDensity());
        for (int deg = -150; deg <= 180; deg += 30) {
            if (deg == 0) continue;
            double rad = Math.toRadians(deg);
            float x0 = cx + (float) Math.sin(rad) * radius * 0.86f;
            float y0 = cy - (float) Math.cos(rad) * radius * 0.86f;
            float x1 = cx + (float) Math.sin(rad) * radius * 0.94f;
            float y1 = cy - (float) Math.cos(rad) * radius * 0.94f;
            line.setColor(0x33FFFFFF);
            c.drawLine(x0, y0, x1, y1, line);
        }

        double rad = Math.toRadians(angle);
        float hx = cx + (float) Math.sin(rad) * radius * 0.78f;
        float hy = cy - (float) Math.cos(rad) * radius * 0.78f;

        line.setStrokeWidth(Math.max(4f, radius * 0.09f));
        line.setColor(engaged ? colorActive : colorKnob);
        c.drawLine(cx, cy, hx, hy, line);

        fill.setColor(engaged ? colorActive : colorKnob);
        c.drawCircle(hx, hy, radius * 0.15f, fill);
        c.drawCircle(cx, cy, radius * 0.07f, fill);

        if (engaged || touched || sensorDriven) {
            text.setTextSize(11f * c.getDensity() * textScale);
            text.setColor(sensorDriven && !touched ? 0xFF00A478 : 0xCCE6E6E6);
            c.drawText(String.format(Locale.US, "%+.1f°", angle),
                    cx, cy + radius + 13f * c.getDensity(), text);
        }
        drawCaption(c, item.id + (sensorDriven && !touched ? "  (tilt)" : ""));
    }
}
