package com.termux.cybersyn.stub2;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One on-screen control. Bounds are in view px, laid out from the pad profile's own canvas.
 *
 * Widgets only know how to draw themselves and turn touches into DroidPad-shaped payloads
 * ({@link #publish}); the view owns hit-testing and the gesture capture.
 */
public abstract class Widget {

    public final PadProfile.Item item;
    protected final RectF bounds = new RectF();
    protected final PadPublisher publisher;
    protected final Haptics haptics;

    protected final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Lets an animated widget ask its view for the next frame; set by PadView. */
    protected Runnable invalidate = () -> { };

    /**
     * How big this widget's own fixed-size labels are drawn: 1 full-screen, smaller in a floating
     * pop-up, set by the view on every layout. Sizes that follow the widget's own bounds (buttons,
     * sliders) need no part of this — they shrink by themselves.
     */
    protected float textScale = 1f;

    /**
     * Told when this widget's own state changes, so something outside the surface can mirror it —
     * the header's variant control follows the pad's own `off` switch/dial, and vice versa.
     */
    protected Runnable stateListener = () -> { };

    protected Widget(PadProfile.Item item, PadPublisher publisher, Haptics haptics) {
        this.item = item;
        this.publisher = publisher;
        this.haptics = haptics;
        line.setStyle(Paint.Style.STROKE);
        text.setColor(0xFFE6E6E6);
        text.setTextAlign(Paint.Align.CENTER);
    }

    public void setBounds(RectF b) {
        bounds.set(b);
    }

    public void setInvalidateHook(Runnable hook) {
        this.invalidate = hook;
    }

    /** Called by the view on every layout: 1 full-screen, the letterbox factor while floating. */
    public void setTextScale(float scale) {
        this.textScale = scale;
    }

    public void setStateListener(Runnable listener) {
        this.stateListener = listener == null ? () -> { } : listener;
    }

    public RectF bounds() {
        return bounds;
    }

    public boolean contains(float x, float y) {
        return bounds.contains(x, y);
    }

    public abstract void draw(Canvas c);

    /** @return true when this widget takes the gesture. */
    public boolean onTouch(MotionEvent e) {
        return false;
    }

    /** Publish one event in the app's own shape: {"id":…,"type":…,+payload}. */
    protected void publish(JSONObject fields) {
        publish(fields, item.type);
    }

    /**
     * Publish with an explicit wire type. Exactly one class needs it: the app sends a `STEP_SLIDER`
     * as `"type":"SLIDER"` like any other slider (only the widget id survives the wire), so the
     * item's own type must never go out for one.
     */
    protected void publish(JSONObject fields, String wireType) {
        try {
            fields.put("id", item.id);
            fields.put("type", wireType);
        } catch (JSONException ignored) {
        }
        publisher.publish(fields.toString());
    }

    /** A small caption above the widget, so a mis-typed itemIdentifier is visible at a glance. */
    protected void drawCaption(Canvas c, String caption) {
        text.setTextSize(11f * c.getDensity() * textScale);
        text.setColor(0xCCE6E6E6);
        c.drawText(caption, bounds.centerX(), bounds.top - 4f * c.getDensity(), text);
    }
}
