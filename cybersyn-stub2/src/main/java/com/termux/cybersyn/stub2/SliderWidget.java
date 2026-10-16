package com.termux.cybersyn.stub2;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * A slider, wired end to end: `{"id","type":"SLIDER","value"}` — the value in whatever min..max the
 * app was configured with. The renderer already consumes these (pads/README.md): `Speed` on the
 * dwarf pad drives the animation rate, `Hue1`/`Hue2` and `Pocket` on mycontrolpad-2 drive hue
 * rotate and the pocket override, and `slider`/`step_slider` publish raw for anything else.
 *
 * Wire rules that must not drift:
 *  - a `STEP_SLIDER` arrives as `"type":"SLIDER"` like any other — only the widget **id** tells the
 *    two apart — so the wire type is forced to SLIDER whatever the item calls itself;
 *  - **nothing is published on load.** The app starts every slider at its minimum and only publishes
 *    on touch, and the renderer reads an ABSENT dial as "the value the shader was authored at" (its
 *    `Speed` rule). Publishing the initial minimum would therefore freeze a shader the first time a
 *    pad carrying a dial was opened. A value leaves the phone only when a finger moves it;
 *  - a slider is **not** spring-return: the value stays where it was left, so one publish per change
 *    is enough (the stick's 8 s TTL does not apply).
 *
 * `steps` is the count of stops BETWEEN the ends, which is what the renderer's arithmetic assumes:
 * the dwarf's 5-step `Speed` has 6 intervals, so its dead centre is exactly 5.00 of 0..10 — and
 * 5.00/5 = 1.0 = "the rate the shader was authored at" on the comrade side. The general pad's
 * 15-step dial is 16 intervals over 0..10, which is the "0..10 in 16 steps" comment there.
 *
 * Colours are the app's own (`thumbColor`/`trackColor`, stored as Compose `Color.toULong()`);
 * nothing is invented. A rotated item is drawn rotated by PadView, so touches are rotated back
 * into the widget's own frame here — a side-laid slider (the app exports ±90°) tracks the finger
 * along the axis it actually runs on.
 */
public final class SliderWidget extends Widget {

    /** ~66 Hz is plenty: only the latest value matters on the wire. Same rate as the stick. */
    private static final long MIN_PUBLISH_MS = 15;

    private final float min;
    private final float max;
    /** Stops between the ends (0 = continuous). See the class comment for why. */
    private final int stops;
    private final boolean showValue;
    private final int colorTrack;
    private final int colorThumb;

    /** Where along the track the thumb sits, 0..1. Starts at the minimum, like the app. */
    private float t;

    private boolean down;
    private float published = Float.NaN;
    private long lastPublishAt;
    /** For a variant dial: the side last sent, so only a change of side publishes. */
    private boolean publishedVariant;

    public SliderWidget(PadProfile.Item item, PadPublisher publisher, Haptics haptics) {
        super(item, publisher, haptics);
        // No range travels on the wire, so read the item's own — or the app's 0..10 default.
        this.min = item.props.has("minValue") ? item.propFloat("minValue", 0f) : 0f;
        this.max = item.props.has("maxValue") ? item.propFloat("maxValue", 10f) : 10f;
        this.stops = Math.max(0, item.propInt("steps", 0));
        this.showValue = item.propBool("showValue", false);
        this.colorTrack = item.propColor("trackColor", 0x55202020);
        this.colorThumb = item.propColor("thumbColor", 0xFFD8D8D8);
        this.t = 0f;
    }

    public float value() {
        return Math.round((min + t * (max - min)) * 1000f) / 1000f;
    }

    @Override
    public boolean onTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                down = true;
                haptics.press();
                move(e.getX(), e.getY(), false);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!down) return false;
                move(e.getX(), e.getY(), false);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!down) return false;
                down = false;
                move(e.getX(), e.getY(), true);   // the value it came to rest on is always sent
                return true;
            }
            default:
                return down;
        }
    }

    private void move(float x, float y, boolean finalDrop) {
        setT(tForX(localX(x, y)), finalDrop);
    }

    /** The one place `t` changes, so a finger and the header leave identical state behind. */
    private void setT(float next, boolean finalDrop) {
        float along = clamp01(next);
        if (stops > 0) {
            float intervals = stops + 1f;
            along = Math.round(along * intervals) / intervals;
            if (along != t) haptics.press();      // one tick per detent, like a real step dial
        }
        t = along;
        publishValue(finalDrop);
    }

    /**
     * A dial whose id is reserved is the pad's **variant control**, not a shader input: put one on
     * any pad (a two-stop `STEP_SLIDER` named `off` is the flicky version of the dwarf pad's `off`
     * switch) and it asks comrade to reload the other half of the active shader's name pair.
     */
    public boolean isVariantControl() {
        return item.id != null && "off".equalsIgnoreCase(item.id.trim());
    }

    /** Which half this dial asks for; its own two-position reading of the configured range. */
    public boolean isVariantOn() {
        return value() > (min + max) / 2f;
    }

    /** Driven from the header: put the thumb on an end, exactly as a finger would leave it. */
    public void setVariant(boolean on) {
        setT(on ? 1f : 0f, true);
    }

    /** A rotated item is drawn rotated: rotate the finger back before it means anything. */
    private float localX(float x, float y) {
        if (item.rotation == 0f) return x;
        double rad = Math.toRadians(-item.rotation);
        float dx = x - bounds.centerX();
        float dy = y - bounds.centerY();
        return (float) (bounds.centerX() + dx * Math.cos(rad) - dy * Math.sin(rad));
    }

    /** The thumb's travel, shared with {@link #draw} so a drop at either end lands exactly on it. */
    private float thumbRadius() {
        return Math.min(bounds.width(), bounds.height()) * 0.20f;
    }

    private float trackLeft() {
        return bounds.left + thumbRadius();
    }

    private float trackRight() {
        return bounds.right - thumbRadius();
    }

    private float tForX(float x) {
        float left = trackLeft();
        float right = trackRight();
        return right > left ? (x - left) / (right - left) : 0f;
    }

    private void publishValue(boolean finalDrop) {
        if (isVariantControl()) {
            // The variant dial says ON/OFF, in the switch shape comrade's trigger reads, and only
            // when the side actually changes — dragging within one side says nothing new.
            boolean on = isVariantOn();
            if (on == publishedVariant) return;
            long now = System.currentTimeMillis();
            if (!finalDrop && now - lastPublishAt < MIN_PUBLISH_MS) return;
            lastPublishAt = now;
            publishedVariant = on;
            JSONObject o = new JSONObject();
            try {
                o.put("state", on);
            } catch (JSONException ignored) {
            }
            publish(o, "SWITCH");
            stateListener.run();
            return;
        }
        float v = value();
        if (v == published) return;                       // nothing new to say
        long now = System.currentTimeMillis();
        if (!finalDrop && now - lastPublishAt < MIN_PUBLISH_MS) return;
        lastPublishAt = now;
        published = v;
        JSONObject o = new JSONObject();
        try {
            o.put("value", v);
        } catch (JSONException ignored) {
        }
        publish(o, "SLIDER");                             // a STEP_SLIDER goes out as SLIDER too
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    @Override
    public void draw(Canvas c) {
        float h = bounds.height();
        float density = c.getDensity();
        float thumbR = thumbRadius();
        float trackH = Math.max(4f * density, h * 0.10f);
        float left = trackLeft();
        float right = trackRight();
        float cy = bounds.centerY();
        float corner = trackH / 2f;

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(colorTrack);
        c.drawRoundRect(new RectF(left, cy - trackH / 2f, right, cy + trackH / 2f), corner, corner, fill);

        float tx = left + (right - left) * t;
        if (tx > left) {
            // The travelled part is the thumb colour, so the value is readable without the number.
            fill.setColor(colorThumb);
            fill.setAlpha(110);
            c.drawRoundRect(new RectF(left, cy - trackH / 2f, tx, cy + trackH / 2f), corner, corner, fill);
            fill.setAlpha(255);
        }

        fill.setColor(colorThumb);
        c.drawCircle(tx, cy, thumbR, fill);
        line.setStrokeWidth(1.5f * density);
        line.setColor(0x55000000);
        c.drawCircle(tx, cy, thumbR, line);

        if (showValue) {
            text.setTextSize(Math.max(12f, h * 0.18f));
            text.setColor(0xFFE6E6E6);
            // A variant dial has two sides rather than a number, so it says which one it wants.
            String readout = isVariantControl()
                    ? (isVariantOn() ? "REMOTE" : "AUTO")
                    : String.format(Locale.US, "%.2f", value());
            c.drawText(readout, bounds.centerX(), cy + thumbR + text.getTextSize() * 1.15f, text);
        }
        drawCaption(c, item.id);
    }
}
