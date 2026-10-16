package com.termux.cybersyn.stub2;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import org.json.JSONObject;

/**
 * A button: `{"id","type":"BUTTON","state"}` where state is PRESS/RELEASE, or CLICK when the item
 * carries `useClickAction` (the app's own two shapes — the bridge counts both).
 *
 * Presses are explicit intent and must never be gated, so a press always publishes even with every
 * switch off.
 */
public final class ButtonWidget extends Widget {

    private static final float CORNER_DP = 16f;

    private final boolean clickMode;
    private final boolean round;
    private final String caption;
    private final int color;
    private final int colorText;

    private boolean pressed;

    public ButtonWidget(PadProfile.Item item, PadPublisher publisher, Haptics haptics) {
        super(item, publisher, haptics);
        this.clickMode = item.propBool("useClickAction", false);
        this.round = "ROUND".equalsIgnoreCase(item.propText("buttonShape", "SQUARE"));
        this.caption = item.propText("text", item.id);
        this.color = item.propColor("buttonColor", 0xFF2A2A2A);
        this.colorText = item.propColor("textColor", 0xFFE6E6E6);
    }

    @Override
    public boolean onTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                pressed = true;
                haptics.press();
                publishState(clickMode ? "CLICK" : "PRESS");
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!pressed) return false;
                pressed = false;
                if (!clickMode) publishState("RELEASE");
                return true;
            }
            default:
                return pressed;
        }
    }

    private void publishState(String state) {
        JSONObject o = new JSONObject();
        try {
            o.put("state", state);
        } catch (org.json.JSONException ignored) {
        }
        publish(o);
    }

    @Override
    public void draw(Canvas c) {
        float inset = pressed ? 4f * c.getDensity() : 0f;
        RectF r = new RectF(bounds);
        r.inset(inset, inset);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        if (round) {
            c.drawCircle(r.centerX(), r.centerY(), Math.min(r.width(), r.height()) / 2f, fill);
        } else {
            c.drawRoundRect(r, CORNER_DP * c.getDensity(), CORNER_DP * c.getDensity(), fill);
        }
        line.setStrokeWidth(2f * c.getDensity());
        line.setColor(pressed ? 0xFFFFFFFF : 0x44FFFFFF);
        if (round) {
            c.drawCircle(r.centerX(), r.centerY(), Math.min(r.width(), r.height()) / 2f, line);
        } else {
            c.drawRoundRect(r, CORNER_DP * c.getDensity(), CORNER_DP * c.getDensity(), line);
        }

        text.setTextSize(Math.max(14f, r.height() * 0.26f));
        text.setColor(colorText);
        Paint.FontMetrics fm = text.getFontMetrics();
        float baseline = r.centerY() - (fm.ascent + fm.descent) / 2f;
        c.drawText(caption, r.centerX(), baseline, text);
        drawCaption(c, item.id);
    }
}
