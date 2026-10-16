package com.termux.cybersyn.stub2;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * A widget type this build does not drive: drawn where it belongs on the pad so the gap is visible
 * instead of silently missing. What is left is the output class — `GAUGE`/`LED` are things comrade
 * would drive *back down* (the app's `DroidPad/feed`), and this phone has no downlink at all.
 */
public final class InertWidget extends Widget {

    public InertWidget(PadProfile.Item item, PadPublisher publisher, Haptics haptics) {
        super(item, publisher, haptics);
    }

    @Override
    public void draw(Canvas c) {
        RectF r = new RectF(bounds);
        r.inset(bounds.width() * 0.12f, bounds.height() * 0.12f);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x22FFFFFF);
        float corner = 10f * c.getDensity();
        c.drawRoundRect(r, corner, corner, fill);
        line.setStrokeWidth(1.5f * c.getDensity());
        line.setColor(0x33FFFFFF);
        c.drawRoundRect(r, corner, corner, line);

        text.setTextSize(10f * c.getDensity());
        text.setColor(0x66E6E6E6);
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(item.type.toLowerCase(java.util.Locale.US),
                r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, text);
        drawCaption(c, item.id);
    }
}
