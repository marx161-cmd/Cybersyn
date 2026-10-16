package com.termux.cybersyn.stub2;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import org.json.JSONObject;

/**
 * A latched switch: `{"id","type":"SWITCH","state"}`.
 *
 * ON means engaged, and this is the one widget a shader must never see switched on without the user
 * doing it — so a freshly loaded pad publishes NOTHING until it is touched (the renderer's resting
 * state for an absent switch is 0, and only explicit intent may change that).
 */
public final class SwitchWidget extends Widget {

    /** A switch whose id is reserved drives a sensor lane instead of publishing to the shaders. */
    public interface RoleListener {
        void onRoleToggle(String role, boolean on);
    }

    /** Reserved pad ids: a switch the user places in DroidPad to arm a sensor lane. */
    public static String roleFor(String id) {
        if (id == null) return null;
        switch (id.trim().toLowerCase(java.util.Locale.US)) {
            case "gyro":
            case "gyro_arm":
                return "gyro";
            case "tilt":
            case "tilt_arm":
                return "tilt";
            default:
                return null;
        }
    }

    private final int colorOn;
    private final int colorOff;
    private final int colorThumb;
    private final String role;

    private boolean on;
    private RoleListener roleListener;

    public SwitchWidget(PadProfile.Item item, PadPublisher publisher, Haptics haptics) {
        super(item, publisher, haptics);
        this.colorOn = item.propColor("checkedColor", 0xFF00A478);
        this.colorOff = item.propColor("uncheckedColor", 0x33FFFFFF);
        this.colorThumb = item.propColor("thumbColor", 0xFFE6E6E6);
        this.role = roleFor(item.id);
    }

    public String role() {
        return role;
    }

    public void setRoleListener(RoleListener listener) {
        this.roleListener = listener;
    }

    /** Mirror a lane armed from somewhere else (the header), so the two never disagree. */
    public void setExternalOn(boolean value) {
        if (this.on == value) return;
        this.on = value;
        invalidate.run();
        stateListener.run();
    }

    public boolean isOn() {
        return on;
    }

    /**
     * Set from outside the surface — the header's variant control drives a pad's own `off` switch
     * through here, so the switch on screen and the header can never say different things. Same
     * state, same published event as a finger would produce.
     */
    public void set(boolean value) {
        if (this.on == value) return;
        this.on = value;
        haptics.press();
        if (role != null) {
            if (roleListener != null) roleListener.onRoleToggle(role, on);
        } else {
            publishState();
        }
        invalidate.run();
        stateListener.run();
    }

    private void publishState() {
        JSONObject o = new JSONObject();
        try {
            o.put("state", on);
        } catch (org.json.JSONException ignored) {
        }
        publish(o);
    }

    @Override
    public boolean onTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                set(!on);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return true;
        }
    }

    @Override
    public void draw(Canvas c) {
        float trackH = bounds.height() * 0.46f;
        float trackW = bounds.width();
        RectF track = new RectF(bounds.left, bounds.centerY() - trackH / 2f,
                bounds.left + trackW, bounds.centerY() + trackH / 2f);
        float radius = trackH / 2f;

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(on ? colorOn : colorOff);
        c.drawRoundRect(track, radius, radius, fill);
        line.setStrokeWidth(1.5f * c.getDensity());
        line.setColor(0x44FFFFFF);
        c.drawRoundRect(track, radius, radius, line);

        float thumbR = radius * 0.82f;
        float thumbX = on ? track.right - radius : track.left + radius;
        fill.setColor(colorThumb);
        c.drawCircle(thumbX, track.centerY(), thumbR, fill);

        text.setTextSize(10f * c.getDensity() * textScale);
        text.setColor(on ? 0xFFFFFFFF : 0x99FFFFFF);
        c.drawText(on ? "ON" : "OFF", bounds.centerX(), track.bottom + 12f * c.getDensity(), text);
        drawCaption(c, item.id + (role != null ? "  (sensor)" : ""));
    }
}
