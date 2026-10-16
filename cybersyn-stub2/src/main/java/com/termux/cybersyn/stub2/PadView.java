package com.termux.cybersyn.stub2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The pad surface: a profile's widgets laid out on the pad's own design canvas
 * (`controlPad.width` x `height`, which is the phone's own px), with hit-testing and gesture
 * capture here so a widget only ever sees a gesture that started on it. Since 2026-10-16 the
 * capture is per finger, so several controls can be driven at once.
 */
public final class PadView extends View {

    private final PadProfile profile;
    private final List<Widget> widgets = new ArrayList<>();

    /**
     * Widget per active finger, keyed by that finger's pointer id (2026-10-16). A pad is played
     * with several fingers at once — lean with the thumb while a slider moves under another — so
     * every pointer hit-tests on its own. One finger **per widget**: a second finger landing on a
     * widget already being driven is ignored rather than queued, because every widget is written
     * against a single finger (a stick reads one deflection, a slider one position).
     */
    private final Map<Integer, Widget> capturedByPointer = new HashMap<>();

    /** The pad's own wheel, if it has one — the seat for the tilt lane. */
    private SensorLane lane;

    /** Arrange (drag) mode: the finger moves widgets instead of driving them. */
    private boolean arranging;
    /**
     * The surface's own colour, and whether the surface is opaque or a scrim (the floating remote
     * sets the see-through one — see {@link #setScrim}).
     */
    private int scrim = Color.rgb(10, 12, 16);
    /**
     * Floating (a freeform pop-up) rather than the phone's own full-screen pad. A pop-up is not the
     * phone's shape, so the whole design canvas is fitted inside it (letterbox) instead of being
     * stretched: the pad becomes a true miniature at whatever size the window is.
     */
    private boolean floating;
    /** Told when the pad's own variant control moves, so the header can show the same thing. */
    private Runnable onVariantChanged;
    private Widget dragging;
    /** Which finger grabbed in arrange mode: the one that grabbed, not whichever is first. */
    private int dragPointer = -1;
    private float dragDX;
    private float dragDY;
    private Runnable onLayoutChanged;
    /** Called after every size change (see {@link #setOnResized}). */
    private Runnable onResized;
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Haptics haptics;

    /** Widget id -> {offsetX, offsetY} in the pad's design-canvas units, as last dropped. */
    private final Map<String, float[]> moved;

    public PadView(Context context, PadProfile profile, PadPublisher publisher, Haptics haptics) {
        super(context);
        this.profile = profile;
        this.haptics = haptics;
        // Start from whatever was arranged before, so a drop adds to the layout instead of
        // replacing it, and a reload keeps every previous move.
        this.moved = new HashMap<>(profile.overrides);
        for (PadProfile.Item item : profile.items) {
            Widget widget;
            switch (item.type) {
                case "JOYSTICK":
                    widget = new StickWidget(item, publisher, haptics);
                    break;
                case "BUTTON":
                    widget = new ButtonWidget(item, publisher, haptics);
                    break;
                case "DPAD":
                    widget = new DpadWidget(item, publisher, haptics);
                    break;
                case "SWITCH":
                    widget = new SwitchWidget(item, publisher, haptics);
                    break;
                case "SLIDER":
                case "STEP_SLIDER":
                    widget = new SliderWidget(item, publisher, haptics);
                    break;
                case "STEERING_WHEEL":
                    widget = new WheelWidget(item, publisher, haptics);
                    break;
                default:
                    widget = new InertWidget(item, publisher, haptics);
                    break;
            }
            widget.setInvalidateHook(this::postInvalidateOnAnimation);
            // Anything that changes state by itself (the header driving a switch) can be followed.
            widget.setStateListener(() -> {
                if (onVariantChanged != null) onVariantChanged.run();
            });
            widgets.add(widget);
        }
        setBackgroundColor(scrim);
    }

    /**
     * The surface's colour, called when the remote goes floating (a scrim, so the shader shows
     * through) and when it comes back (opaque). Nothing else about a pad changes with the window.
     */
    public void setScrim(int color) {
        scrim = color;
        setBackgroundColor(color);
    }

    /** Floating or full-screen: the shape the widgets are laid out for. */
    public void setFloating(boolean floating) {
        if (this.floating == floating) return;
        this.floating = floating;
        layoutWidgets(getWidth(), getHeight());
        invalidate();
    }

    public PadProfile profile() {
        return profile;
    }

    /**
     * Hand the sensor lanes the parts of this pad they can drive: the STEERING_WHEEL item (whatever
     * id this pad gave it) becomes the tilt lane's seat, and any reserved-id switch arms a lane
     * from the pad itself. A pad with neither simply is not sensor-capable, which is honest.
     */
    public void bindSensors(SensorLane lane) {
        this.lane = lane;
        // A fresh pad starts from no wheel at all: a wheel-less pad must clear the old seat.
        lane.setWheelTarget(null, null);
        for (Widget widget : widgets) {
            if (widget instanceof WheelWidget) {
                WheelWidget target = (WheelWidget) widget;
                lane.setWheelTarget(target.item.id, target);
            }
            if (widget instanceof SwitchWidget) {
                SwitchWidget sw = (SwitchWidget) widget;
                if (sw.role() != null) {
                    sw.setRoleListener((role, on) -> {
                        if (lane == null) return;
                        if ("gyro".equals(role)) lane.armCamera(on);
                        else if ("tilt".equals(role)) lane.armTilt(on);
                        refreshSensorState();
                    });
                }
            }
        }
        refreshSensorState();
    }

    /** Keep pad switches and the header showing the same truth. */
    public void refreshSensorState() {
        if (lane == null) return;
        for (Widget widget : widgets) {
            if (widget instanceof SwitchWidget) {
                SwitchWidget sw = (SwitchWidget) widget;
                if ("gyro".equals(sw.role())) sw.setExternalOn(lane.isCameraArmed());
                else if ("tilt".equals(sw.role())) sw.setExternalOn(lane.isTiltArmed());
            }
        }
    }

    /** Widget types this build drives; anything else is drawn but inert. */
    public static int drivenCount(PadProfile profile) {
        int n = 0;
        for (PadProfile.Item item : profile.items) {
            switch (item.type) {
                case "JOYSTICK":
                case "BUTTON":
                case "DPAD":
                case "SWITCH":
                case "SLIDER":
                case "STEP_SLIDER":
                case "STEERING_WHEEL":
                    n++;
                    break;
                default:
                    break;
            }
        }
        return n;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutWidgets(w, h);
        if (onResized != null) onResized.run();
    }

    private void layoutWidgets(int w, int h) {
        float density = getResources().getDisplayMetrics().density;
        boolean fitted = floating && profile.width > 0f && profile.height > 0f;
        // Full-screen, the pad's canvas *is* the phone (this factor is 1). Floating, the canvas is
        // fitted inside the window and centred, so every widget and every size scales as one piece.
        float fit = fitted ? Math.min(w / profile.width, h / profile.height)
                : (profile.width > 0f ? w / profile.width : 1f);
        float offX = fitted ? (w - profile.width * fit) / 2f : 0f;
        float offY = fitted ? (h - profile.height * fit) / 2f : 0f;
        for (Widget widget : widgets) {
            PadProfile.Item item = widget.item;
            float size = PadProfile.baseSizeDp(item.type) * item.scale * density * fit;
            float[] arranged = moved.get(item.id);
            float ox = arranged != null ? arranged[0] : item.offsetX;
            float oy = arranged != null ? arranged[1] : item.offsetY;
            float cx = fitted ? offX + ox * fit
                    : (profile.width > 0f ? ox / profile.width * w : w / 2f);
            float cy = fitted ? offY + oy * fit
                    : (profile.height > 0f ? oy / profile.height * h : h / 2f);
            widget.setTextScale(fit);
            widget.setBounds(new RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f));
        }
    }

    /** Arrange mode: the finger positions widgets. Every drop is committed and reported. */
    public boolean isArranging() {
        return arranging;
    }

    public void setArranging(boolean on) {
        if (arranging == on) return;
        arranging = on;
        dragging = null;
        invalidate();
    }

    /** Widget id -> {offsetX, offsetY} in canvas units, for {@link PadLayout#save}. */
    public Map<String, float[]> layout() {
        return moved;
    }

    /** Called after every drop, so the activity can write the sidecar immediately. */
    public void setOnLayoutChanged(Runnable listener) {
        this.onLayoutChanged = listener;
    }

    /**
     * Called after every size change: the surface's width is what decides whether the floating
     * remote has room for its header bar, and the first chrome pass runs before there is a width.
     */
    public void setOnResized(Runnable listener) {
        this.onResized = listener;
    }

    /**
     * The pad's own variant control, if it carries one: an `off` switch (the dwarf pad's) or a
     * two-stop `off` dial. Both publish the same switch event, so the header can drive either one
     * and the two can never disagree about which shader half is asked for.
     */
    private Widget variantControl() {
        for (Widget widget : widgets) {
            if (widget instanceof SwitchWidget) {
                String id = widget.item.id == null ? "" : widget.item.id.trim();
                if ("off".equalsIgnoreCase(id)) return widget;
            } else if (widget instanceof SliderWidget && ((SliderWidget) widget).isVariantControl()) {
                return widget;
            }
        }
        return null;
    }

    /** Which half this pad asks for, or null when it has no control of its own. */
    public Boolean variantOn() {
        Widget widget = variantControl();
        if (widget instanceof SwitchWidget) return ((SwitchWidget) widget).isOn();
        if (widget instanceof SliderWidget) return ((SliderWidget) widget).isVariantOn();
        return null;
    }

    /** Move the pad's own control, exactly as a finger would. */
    public void setVariant(boolean on) {
        Widget widget = variantControl();
        if (widget instanceof SwitchWidget) ((SwitchWidget) widget).set(on);
        else if (widget instanceof SliderWidget) ((SliderWidget) widget).setVariant(on);
    }

    public void setOnVariantChanged(Runnable listener) {
        this.onVariantChanged = listener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (Widget widget : widgets) {
            canvas.save();
            // DroidPad's export writes `rotation` in degrees (a slider laid on its side exports
            // as -89.9/-91.0), so it goes to Canvas.rotate() as-is. Converting it as if it were
            // radians put every rotated widget at a bogus angle (real bug found 2026-10-16).
            if (widget.item.rotation != 0f) {
                canvas.rotate(widget.item.rotation,
                        widget.bounds().centerX(), widget.bounds().centerY());
            }
            widget.draw(canvas);
            canvas.restore();
        }
        if (arranging) {
            // Every widget becomes a grab target and the one under the finger is highlighted, so
            // arrange mode says what it is doing without a dialog in the way.
            float density = getResources().getDisplayMetrics().density;
            for (Widget widget : widgets) {
                boolean held = widget == dragging;
                guide.setStyle(Paint.Style.STROKE);
                guide.setStrokeWidth((held ? 2.5f : 1f) * density);
                guide.setColor(held ? 0xFF00D492 : 0x5529FF9B);
                canvas.drawRoundRect(widget.bounds(), 8f * density, 8f * density, guide);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (arranging) return arrangeTouch(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                // A new finger hit-tests on its own, so a second finger can take a second control
                // without disturbing the first.
                int index = event.getActionIndex();
                capture(event.getPointerId(index), index, event);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    Widget widget = capturedByPointer.get(event.getPointerId(i));
                    if (widget != null) dispatch(widget, event, MotionEvent.ACTION_MOVE, i);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP: {
                // Only this finger's widget sees the lift; the others keep their grip.
                int index = event.getActionIndex();
                Widget widget = capturedByPointer.remove(event.getPointerId(index));
                if (widget != null) dispatch(widget, event, MotionEvent.ACTION_UP, index);
                if (capturedByPointer.isEmpty()) performClick();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    Widget widget = capturedByPointer.remove(event.getPointerId(i));
                    if (widget != null) dispatch(widget, event, MotionEvent.ACTION_CANCEL, i);
                }
                invalidate();
                return true;
            }
            default:
                return true;
        }
    }

    /** Topmost first, exactly like arrange mode: later items in the profile are drawn on top. */
    private void capture(int pointerId, int index, MotionEvent event) {
        float x = event.getX(index);
        float y = event.getY(index);
        for (int i = widgets.size() - 1; i >= 0; i--) {
            Widget widget = widgets.get(i);
            if (capturedByPointer.containsValue(widget)) continue;   // already has a finger
            if (widget.contains(x, y) && dispatch(widget, event, MotionEvent.ACTION_DOWN, index)) {
                capturedByPointer.put(pointerId, widget);
                return;
            }
        }
    }

    /**
     * Hand one widget a single-finger view of a multi-finger event. Every widget is written against
     * `getX()` (pointer 0) and a gesture that started on it, so routing is done by rebuilding the
     * event with just that finger rather than teaching each widget about pointer ids — which would
     * buy nothing and touch every control.
     */
    private boolean dispatch(Widget widget, MotionEvent event, int action, int index) {
        MotionEvent one = MotionEvent.obtain(event.getDownTime(), event.getEventTime(), action,
                event.getX(index), event.getY(index), event.getMetaState());
        try {
            return widget.onTouch(one);
        } finally {
            one.recycle();
        }
    }

    /**
     * Drag mode's gesture: a widget only starts moving when the touch began inside it (so a pad
     * full of overlapping widgets never jumps), the centre stays on the surface, and the drop is
     * committed in canvas units the moment the finger lifts — nothing is held back until "done".
     */
    private boolean arrangeTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                dragging = null;
                int index = event.getActionIndex();
                dragPointer = event.getPointerId(index);
                // Topmost first, exactly like the driving path.
                for (int i = widgets.size() - 1; i >= 0; i--) {
                    Widget widget = widgets.get(i);
                    if (widget.contains(event.getX(index), event.getY(index))) {
                        dragging = widget;
                        dragDX = event.getX(index) - widget.bounds().centerX();
                        dragDY = event.getY(index) - widget.bounds().centerY();
                        haptics.press();
                        break;
                    }
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragging != null) {
                    // Follow the finger that grabbed, so a second finger resting anywhere else
                    // cannot steer the widget being placed.
                    int index = event.findPointerIndex(dragPointer);
                    if (index >= 0) {
                        float half = dragging.bounds().width() / 2f;
                        float cx = clampCenter(event.getX(index) - dragDX, getWidth());
                        float cy = clampCenter(event.getY(index) - dragDY, getHeight());
                        dragging.setBounds(new RectF(cx - half, cy - half, cx + half, cy + half));
                        invalidate();
                    }
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                // A finger that was not the one dragging is none of this gesture's business.
                if (event.getPointerId(event.getActionIndex()) != dragPointer) return true;
                // The dragging finger lifted, so the drop is final; same path as ACTION_UP.
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging != null) {
                    commit(dragging);
                    dragging = null;
                    dragPointer = -1;
                    invalidate();
                }
                return true;
            default:
                // ACTION_POINTER_DOWN: arrange mode places one widget at a time, so extra fingers
                // are ignored rather than dropping something nobody asked for.
                return true;
        }
    }

    /** Canvas units, so the arrangement means the same thing on any screen size. */
    private void commit(Widget widget) {
        int wpx = Math.max(1, getWidth());
        int hpx = Math.max(1, getHeight());
        RectF b = widget.bounds();
        float cx = profile.width > 0f ? b.centerX() / wpx * profile.width : b.centerX();
        float cy = profile.height > 0f ? b.centerY() / hpx * profile.height : b.centerY();
        moved.put(widget.item.id, new float[]{cx, cy});
        haptics.thud();
        if (onLayoutChanged != null) onLayoutChanged.run();
    }

    /** Keep the widget's centre on the surface; overhang (as the app itself allows) is fine. */
    private static float clampCenter(float center, int extent) {
        if (extent <= 0) return center;
        return center < 0f ? 0f : (center > extent ? extent : center);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
