package com.termux.cybersyn.stub2;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import org.json.JSONObject;

/**
 * A d-pad: `{"id","type":"DPAD","button","state"}` with button UP/DOWN/LEFT/RIGHT.
 *
 * The app can emit either shape for the same nudge — PRESS/RELEASE, or CLICK when the item sets
 * `useClickAction` ("i can do different modes of dpad", user) — and the bridge counts both, so we
 * simply mirror whichever the item is configured for.
 */
public final class DpadWidget extends Widget {

    private static final String[] DIRECTIONS = {"UP", "DOWN", "LEFT", "RIGHT"};

    private final boolean clickMode;
    private final int colorIdle;
    private final int colorActive;

    /** The direction currently held, or null. */
    private String held;

    public DpadWidget(PadProfile.Item item, PadPublisher publisher, Haptics haptics) {
        super(item, publisher, haptics);
        this.clickMode = item.propBool("useClickAction", false);
        this.colorIdle = item.propColor("buttonColor", 0xAA2A2A2A);
        this.colorActive = item.propColor("checkedColor", 0xFF00A478);
    }

    /** The arm a touch is on, or null for the dead centre. */
    private String sector(float x, float y) {
        float dx = x - bounds.centerX();
        float dy = y - bounds.centerY();
        float size = Math.min(bounds.width(), bounds.height());
        if ((float) Math.hypot(dx, dy) < size * 0.12f) return null;
        if (Math.abs(dx) > Math.abs(dy)) return dx < 0 ? "LEFT" : "RIGHT";
        return dy < 0 ? "UP" : "DOWN";
    }

    @Override
    public boolean onTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                String s = sector(e.getX(), e.getY());
                if (s == null) return false;
                held = s;
                haptics.press();
                publishState(s, clickMode ? "CLICK" : "PRESS");
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (held == null) return false;
                String s = sector(e.getX(), e.getY());
                if (s != null && !s.equals(held)) {
                    if (!clickMode) publishState(held, "RELEASE");
                    held = s;
                    haptics.press();
                    publishState(s, clickMode ? "CLICK" : "PRESS");
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (held == null) return false;
                if (!clickMode) publishState(held, "RELEASE");
                held = null;
                return true;
            }
            default:
                return held != null;
        }
    }

    private void publishState(String button, String state) {
        JSONObject o = new JSONObject();
        try {
            o.put("button", button);
            o.put("state", state);
        } catch (org.json.JSONException ignored) {
        }
        publish(o);
    }

    @Override
    public void draw(Canvas c) {
        float w = bounds.width();
        float h = bounds.height();
        float armW = Math.min(w, h) * 0.36f;
        float corner = armW * 0.28f;

        fill.setStyle(Paint.Style.FILL);
        for (String dir : DIRECTIONS) {
            fill.setColor(dir.equals(held) ? colorActive : colorIdle);
            c.drawRoundRect(arm(bounds, dir, armW), corner, corner, fill);
        }
        // Centre hub, drawn last so the arms read as one control.
        fill.setColor(0xCC141414);
        c.drawCircle(bounds.centerX(), bounds.centerY(), armW * 0.55f, fill);
        line.setStrokeWidth(1.5f * c.getDensity());
        line.setColor(0x44FFFFFF);
        c.drawCircle(bounds.centerX(), bounds.centerY(), armW * 0.55f, line);
        drawCaption(c, item.id);
    }

    private static RectF arm(RectF b, String dir, float armW) {
        float cx = b.centerX();
        float cy = b.centerY();
        float half = armW / 2f;
        switch (dir) {
            case "UP":    return new RectF(cx - half, b.top, cx + half, cy);
            case "DOWN":  return new RectF(cx - half, cy, cx + half, b.bottom);
            case "LEFT":  return new RectF(b.left, cy - half, cx, cy + half);
            default:      return new RectF(cx, cy - half, b.right, cy + half);
        }
    }
}
