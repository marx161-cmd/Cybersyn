package com.termux.cybersyn.stub2;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import org.json.JSONObject;

/**
 * An analog stick: DroidPad's wire contract, Artemis's handling.
 *
 * The contract (see pads/README.md): `{"id","type":"JOYSTICK","x","y"}` with both axes -1..1 and
 * +y UP, and — because the app only sends a 0 on drag END — a resting stick that goes quiet reads as
 * centre on the renderer side (PAD_STICK_TTL_S). So a release must always publish 0,0.
 *
 * The feel is the part lifted from Artemis's `AnalogStick`:
 *  - the touch position itself is the deflection (no hunting for the handle);
 *  - the deadzone is released after {@link #TIMEOUT_DEADZONE_MS} ms of holding, so precise small
 *    movements are possible, and once active the stick stays active back through the deadzone.
 */
public final class StickWidget extends Widget {

    private static final long TIMEOUT_DEADZONE_MS = 150;   // Artemis AnalogStick.timeoutDeadzone
    private static final float KNOB_FACTOR = 0.42f;        // knob radius / outer radius
    private static final float DEADZONE = 0.10f;           // of the outer radius
    private static final long MIN_PUBLISH_MS = 15;         // ~66 Hz; pad.json only needs the latest
    private static final float EPSILON = 0.004f;

    private final int colorTrack;
    private final int colorKnob;
    private final boolean showValues;

    private boolean pressed;
    private boolean active;
    private long downAt;
    private long lastPublishAt;
    private float lastX;
    private float lastY;
    private float knobDx;
    private float knobDy;

    public StickWidget(PadProfile.Item item, PadPublisher publisher, Haptics haptics) {
        super(item, publisher, haptics);
        this.colorTrack = item.propColor("backgroundColor", 0x77202020);
        this.colorKnob = item.propColor("handleColor", 0xFFB0B0B0);
        this.showValues = item.propBool("showValues", false);
    }

    @Override
    public boolean onTouch(MotionEvent e) {
        float radiusOuter = Math.min(bounds.width(), bounds.height()) / 2f;
        float radiusKnob = radiusOuter * KNOB_FACTOR;
        float complete = Math.max(1f, radiusOuter - radiusKnob);
        float cx = bounds.centerX();
        float cy = bounds.centerY();
        float dx = e.getX() - cx;
        float dy = e.getY() - cy;
        float r = (float) Math.hypot(dx, dy);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (r > radiusOuter) return false;       // outside the stick: not our gesture
                pressed = true;
                active = false;
                downAt = e.getEventTime();
                haptics.engage();
                return move(dx, dy, r, complete, radiusKnob, e.getEventTime());
            }
            case MotionEvent.ACTION_MOVE: {
                if (!pressed) return false;
                return move(dx, dy, r, complete, radiusKnob, e.getEventTime());
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!pressed) return false;
                pressed = false;
                active = false;
                knobDx = 0f;
                knobDy = 0f;
                publish(0f, 0f);
                lastX = 0f;
                lastY = 0f;
                return true;
            }
            default:
                return pressed;
        }
    }

    private boolean move(float dx, float dy, float r, float complete, float radiusKnob, long eventTime) {
        if (r > complete) {                              // keep the knob inside the track
            float k = complete / r;
            dx *= k;
            dy *= k;
            r = complete;
        }
        knobDx = dx;
        knobDy = dy;

        // Artemis: dead inside the zone until the finger has been down a moment or the stick is
        // pushed out of it; then stay active so a return through the centre does not drop out.
        boolean nowActive = active || (eventTime - downAt > TIMEOUT_DEADZONE_MS) || (r > DEADZONE * complete);
        if (!nowActive) return true;
        active = true;

        float x = dx / complete;
        float y = -dy / complete;                        // +y is UP on the wire
        if (eventTime - lastPublishAt < MIN_PUBLISH_MS
                && Math.abs(x - lastX) < EPSILON && Math.abs(y - lastY) < EPSILON) {
            return true;
        }
        lastX = x;
        lastY = y;
        publish(x, y);
        return true;
    }

    private void publish(float x, float y) {
        lastPublishAt = System.currentTimeMillis();
        JSONObject o = new JSONObject();
        try {
            o.put("x", round(x));
            o.put("y", round(y));
        } catch (org.json.JSONException ignored) {
        }
        publish(o);
    }

    private static float round(float v) {
        return Math.round(v * 1000f) / 1000f;
    }

    @Override
    public void draw(Canvas c) {
        float radiusOuter = Math.min(bounds.width(), bounds.height()) / 2f;
        float radiusKnob = radiusOuter * KNOB_FACTOR;
        float cx = bounds.centerX();
        float cy = bounds.centerY();

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(colorTrack);
        c.drawCircle(cx, cy, radiusOuter, fill);

        line.setStrokeWidth(1.5f * c.getDensity());
        line.setColor(0x33FFFFFF);
        c.drawCircle(cx, cy, radiusOuter, line);
        line.setColor(0x22FFFFFF);                       // the deadzone, visible while testing
        c.drawCircle(cx, cy, radiusOuter * DEADZONE, line);

        fill.setColor(colorKnob);
        c.drawCircle(cx + knobDx, cy + knobDy, radiusKnob, fill);
        line.setColor(0x55000000);
        c.drawCircle(cx + knobDx, cy + knobDy, radiusKnob, line);

        if (showValues) {
            text.setTextSize(10f * c.getDensity() * textScale);
            text.setColor(0xAAFFFFFF);
            c.drawText(String.format(java.util.Locale.US, "%+.2f %+.2f", lastX, lastY),
                    cx, cy + radiusOuter + 12f * c.getDensity(), text);
        }
        drawCaption(c, item.id);
    }
}
