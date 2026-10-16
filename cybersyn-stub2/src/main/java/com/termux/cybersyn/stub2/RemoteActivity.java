package com.termux.cybersyn.stub2;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The remote: the one place that holds a pad surface.
 *
 * Pads are read from Termux's own home (`~/.cybersyn/pads/*.json`) — the same UID-1000 filesystem
 * comrade already edits over ssh and the satellites can write, so a pad change is a file write with
 * no APK rebuild. Everything a control does goes out as one DroidPad-shaped line into Cybersyn's
 * ingest FIFO (see {@link PadPublisher}); nothing here opens a socket.
 *
 * The activity itself is a deliberate nothing-burger: it shows the surface, names it, and lets you
 * pick another pad. Choosing a pad is local and immediate, exactly as it is in the app today.
 */
public final class RemoteActivity extends Activity {

    private static final String TAG = "cybersyn-remote";

    /** The live pad copy, in Termux's home: `/data/data/com.termux/files/home`. */
    private static final File PADS_DIR =
            new File("/data/data/com.termux/files/home/.cybersyn/pads");

    private static final String PREFS = "remote";
    private static final String KEY_LAST = "last_pad";
    /** This remote's memory of the variant it last asked for, on a pad with no `off` control. */
    private static final String KEY_VARIANT = "variant_on";

    /**
     * The floating remote (user 2026-10-16: "the trackpad … pops open in a very transparent free
     * floating window can we copy that?"). The window is not ours to make: it is the ROM's own
     * freeform manager, `com.libremobileos.freeform`, and the way to ask for it is the broadcast
     * SpectreBoard's popup key sends (KeyboardActionListenerImpl: AMD_CONTROL). Anything else —
     * `am start --windowingMode freeform`, `ActivityOptions.setLaunchBounds` — is ignored or makes
     * an ordinary AOSP task instead.
     *
     * A freeform window here is a **virtual display**, so the activity inside it is "full-screen"
     * and `isInMultiWindowMode()` is false: floating is therefore detected by the display id.
     */
    private static final String FREEFORM_ACTION = "com.libremobileos.freeform.START_FREEFORM";
    private static final String FREEFORM_PACKAGE = "com.libremobileos.freeform";
    private static final String FREEFORM_RECEIVER =
            "com.libremobileos.freeform.receiver.StartFreeformReceiver";
    /** How a fresh launch of this remote opens: the floating window, or the ordinary pad. */
    private static final String KEY_FLOAT = "open_float";
    /**
     * The two floats' scrims: dark enough that captions and outlines stay readable over video,
     * light enough to see the picture through. Tunable in one line — the trackpad stub uses
     * `0x1A003D1F` (10%), which is fine for a bare surface with no text on it and not for a pad.
     */
    private static final int OPAQUE_CANVAS = 0xFF0A0C10;
    private static final int OPAQUE_BAR = 0xFF12161C;
    private static final int FLOAT_CANVAS = 0x800A0C10;
    private static final int FLOAT_BAR = 0x9912161C;
    /**
     * Under this much of the pad's own canvas width the header bar is hidden: a small pop-up is
     * the pad and nothing else, and its labels would not fit. A resized or maximized window gets
     * the bar back.
     */
    private static final float BAR_MIN_WIDTH = 0.75f;

    private PadPublisher publisher;
    private Haptics haptics;
    private SensorLane lane;
    private FrameLayout surface;
    private LinearLayout bar;
    private TextView header;
    private TextView sensorsLabel;
    private TextView variantButton;
    private TextView arrangeButton;
    private TextView floatButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Opened as an ordinary app while this remote is set to float (Quick Tap's "open remote"
        // rule, the launcher icon, `am start`): hand straight over to the freeform window before
        // building a surface, so the handover is one launch with no flash of a full-screen pad and
        // never two publishers. It cannot loop: the freeform window launches this activity fresh on
        // its own display, where the display-id test below is already true.
        if (floatWanted() && !isFloating()) {
            requestFreeform();
            finish();
            return;
        }
        publisher = new PadPublisher();
        haptics = new Haptics(this);
        lane = new SensorLane(this, publisher);
        // One place observes the lanes: the header says what is armed and the pad's own reserved
        // switches mirror it, so the two can never disagree about whether the phone is steering.
        lane.setListener(() -> {
            updateSensorLabel();
            if (surface.getChildCount() > 0 && surface.getChildAt(0) instanceof PadView) {
                ((PadView) surface.getChildAt(0)).refreshSensorState();
            }
        });
        View root = buildUi();
        // Edge-to-edge is enforced at this targetSdk, so claim the bars explicitly: they are
        // status-bar padding on the header and navigation-bar padding under the pad.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            int side = dp(10);
            bar.setPadding(side, side + top, side, side);
            surface.setPadding(0, 0, 0, bottom);
            return insets;
        });
        setContentView(root);
        // A control surface is useless if the screen sleeps under a finger.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // The window itself must not paint a background: full-screen, the pad's own views cover
        // every pixel of it anyway, and as a pop-up the picture behind has to come through.
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        loadInitialPad();
        applyFloatChrome();
    }

    /**
     * Floating or full-screen? The system tells us, so the pad never has to guess: a freeform
     * window is multi-window mode, and everything about the two modes — the scrims, the focus
     * flag, the button's own label — hangs off this one answer.
     */
    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode,
                                         android.content.res.Configuration newConfig) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig);
        applyFloatChrome();
    }

    /**
     * The whole difference between the two remotes. Floating: see-through, never focusable (so the
     * keyboard behind stays up — the trackpad stub's own trick, a pad tap must not drop
     * SpectreBoard out of Termux), and so small that the header bar is dropped. Full-screen: the
     * ordinary opaque pad with its bar.
     */
    private void applyFloatChrome() {
        if (bar == null) return;
        boolean floating = isFloating();
        boolean showBar = !floating || barFits();
        bar.setVisibility(showBar ? View.VISIBLE : View.GONE);
        bar.setBackgroundColor(floating ? FLOAT_BAR : OPAQUE_BAR);
        PadView view = padView();
        if (view != null) {
            view.setFloating(floating);
            view.setScrim(floating ? FLOAT_CANVAS : OPAQUE_CANVAS);
        }
        if (floating) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        if (floatButton != null) {
            floatButton.setText(floating ? "full" : "float");
            floatButton.setTextColor(floating ? 0xFF00D492 : 0xFF99A0AA);
        }
    }

    /** Is this window the freeform one? LMO freeform windows are virtual displays. */
    private boolean isFloating() {
        return getWindowManager().getDefaultDisplay().getDisplayId() != Display.DEFAULT_DISPLAY
                || isInMultiWindowMode();
    }

    /** How a fresh launch opens, until the header button says otherwise. */
    private boolean floatWanted() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_FLOAT, true);
    }

    /** Is there room for the header bar at the surface's current width? */
    private boolean barFits() {
        PadView view = padView();
        if (view == null) return true;
        float canvas = view.profile().width;
        return canvas <= 0f || surface.getWidth() >= canvas * BAR_MIN_WIDTH;
    }

    /** Ask the ROM's freeform manager for a window on this component (SpectreBoard's own call). */
    private void requestFreeform() {
        sendBroadcast(new Intent(FREEFORM_ACTION)
                .setComponent(new ComponentName(FREEFORM_PACKAGE, FREEFORM_RECEIVER))
                .putExtra("packageName", getPackageName())
                .putExtra("activityName", RemoteActivity.class.getName())
                .putExtra("userId", 0)
                .putExtra("taskId", -1));
    }

    /**
     * The header's own way between the two remotes. Floating: this sets the remote back to the
     * ordinary pad and closes the window (the ROM owns it; the pad leaving is what takes it away).
     * Full-screen: this hands the remote to the freeform window. Both are remembered for the next
     * launch, because that is what decides how Quick Tap opens it.
     */
    private void toggleFloat() {
        boolean toFloat = !floatWanted();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_FLOAT, toFloat).apply();
        if (isFloating()) {
            finish();
            Toast.makeText(this, toFloat
                            ? "floating remote closed"
                            : "next launch opens full-screen",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (toFloat) {
            requestFreeform();
            finish();
            return;
        }
        // Already the ordinary pad and asked for the ordinary pad: nothing to move.
        applyFloatChrome();
        Toast.makeText(this, "this is the full-screen remote", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // A warm task gets the new extra here, not in onCreate: without this, `am start … --es pad`
        // (the documented test-loop entry point) silently kept whatever pad was already loaded.
        if (intent != null && intent.getStringExtra("pad") != null) loadInitialPad();
        applyRequestedArm();
    }

    @Override
    protected void onPause() {
        // Nothing steers from off-screen: leaving the surface (or the screen going off) drops every
        // arm and tells the far end, so the phone can never keep driving something nobody sees.
        // (Null when this instance handed itself to the freeform window in onCreate and never built
        // a surface.)
        if (lane != null) lane.disarmAll();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (lane != null) lane.shutdown();
        if (publisher != null) publisher.close();
        super.onDestroy();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(OPAQUE_BAR);
        int pad = dp(10);
        bar.setPadding(pad, pad, pad, pad);
        this.bar = bar;

        header = new TextView(this);
        header.setTextColor(Color.WHITE);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), dp(6), dp(4), dp(6));
        header.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.setOnClickListener(v -> showPadChooser());
        bar.addView(header);

        sensorsLabel = new TextView(this);
        sensorsLabel.setTextColor(0xFF99A0AA);
        sensorsLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        sensorsLabel.setPadding(dp(10), dp(6), dp(10), dp(6));
        // The sensors are one tap away on every pad, whether or not the pad carries arm switches.
        sensorsLabel.setOnClickListener(v -> showSensorPanel());
        bar.addView(sensorsLabel);

        variantButton = new TextView(this);
        variantButton.setPadding(dp(12), dp(6), dp(10), dp(6));
        variantButton.setOnClickListener(v -> flipVariant());
        bar.addView(variantButton);

        arrangeButton = new TextView(this);
        arrangeButton.setText("arrange");
        arrangeButton.setTextColor(0xFF99A0AA);
        arrangeButton.setPadding(dp(12), dp(6), dp(10), dp(6));
        arrangeButton.setOnClickListener(v -> toggleArrange());
        bar.addView(arrangeButton);

        // The pad is the thing you want over the picture, so floating is a plain tap here rather
        // than a Tasker task: `float` pops this remote into a pop-up window, `full` sends it back.
        floatButton = new TextView(this);
        floatButton.setText("float");
        floatButton.setTextColor(0xFF99A0AA);
        floatButton.setPadding(dp(12), dp(6), dp(10), dp(6));
        floatButton.setOnClickListener(v -> toggleFloat());
        bar.addView(floatButton);

        TextView reload = new TextView(this);
        reload.setText("reload");
        reload.setTextColor(Color.rgb(0, 164, 120));
        reload.setPadding(dp(12), dp(6), dp(4), dp(6));
        reload.setOnClickListener(v -> reload());
        bar.addView(reload);

        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        surface = new FrameLayout(this);
        root.addView(surface, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void loadInitialPad() {
        List<File> pads = PadProfile.listPadFiles(PADS_DIR);
        if (pads.isEmpty()) {
            header.setText("no pads in " + PADS_DIR.getAbsolutePath());
            surface.addView(message("No pad profiles found.\n\n" + PADS_DIR
                    + "\n\nCopy a DroidPad export there (see shader-backdrop/pads/fetch.py)."));
            return;
        }
        // `am start … --es pad <file>` opens straight into one pad, for the test loop.
        String requested = getIntent() == null ? null : getIntent().getStringExtra("pad");
        if (requested != null) {
            for (File f : pads) {
                if (f.getName().equals(requested) || f.getName().equals(requested + ".json")) {
                    show(f);
                    return;
                }
            }
            Log.w(TAG, "no such pad: " + requested);
        }
        String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST, null);
        if (last != null) {
            for (File f : pads) {
                if (f.getName().equals(last)) {
                    show(f);
                    return;
                }
            }
        }
        show(pads.get(0));
    }

    private void showPadChooser() {
        List<File> pads = PadProfile.listPadFiles(PADS_DIR);
        if (pads.isEmpty()) {
            Toast.makeText(this, "no pads in " + PADS_DIR.getAbsolutePath(), Toast.LENGTH_LONG).show();
            return;
        }
        final String[] names = new String[pads.size()];
        for (int i = 0; i < pads.size(); i++) names[i] = pads.get(i).getName();
        new AlertDialog.Builder(this)
                .setTitle("Pads in ~/.cybersyn/pads")
                .setItems(names, (dialog, which) -> show(pads.get(which)))
                .show();
    }

    /** Load (or reload) one profile from disk. */
    private void show(File file) {
        PadProfile profile;
        try {
            profile = PadProfile.load(file);
        } catch (Exception e) {
            Log.w(TAG, "cannot read " + file + ": " + e);
            Toast.makeText(this, "cannot read " + file.getName(), Toast.LENGTH_LONG).show();
            return;
        }
        surface.removeAllViews();
        PadView view = new PadView(this, profile, publisher, haptics);
        surface.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        // Point the sensor lanes at whatever this pad offers (its wheel, its reserved switches).
        view.bindSensors(lane);
        // Arrange mode is per-surface: a freshly loaded pad starts in driving mode, and every drop
        // in this surface writes this pad's own layout sidecar.
        arrangeButton.setText("arrange");
        arrangeButton.setTextColor(0xFF99A0AA);
        view.setOnLayoutChanged(() -> saveLayout(view));
        view.setOnResized(this::applyFloatChrome);
        // A pad carrying its own `off` control keeps the header honest about which half is asked for.
        view.setOnVariantChanged(this::updateVariantLabel);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST, file.getName()).apply();
        header.setText(profile.name + "  ·  " + PadView.drivenCount(profile) + "/"
                + profile.items.size() + " widgets  ·  tap to switch"
                + (profile.overrides.isEmpty() ? "" : "  ·  arranged here"));
        updateSensorLabel();
        updateVariantLabel();
        applyFloatChrome();
        applyRequestedArm();
    }

    /**
     * `am start … --es arm gyro,tilt` arms lanes on launch, so the sysapp test loop does not need a
     * finger on the screen. Consumed once: the lanes are never silently re-armed by a relaunch.
     */
    private void applyRequestedArm() {
        if (getIntent() == null) return;
        String spec = getIntent().getStringExtra("arm");
        if (spec == null) return;
        getIntent().removeExtra("arm");
        for (String part : spec.split(",")) {
            switch (part.trim().toLowerCase(java.util.Locale.US)) {
                case "gyro":
                case "camera":
                    lane.armCamera(true);
                    break;
                case "tilt":
                case "wheel":
                    lane.armTilt(true);
                    break;
                default:
                    Log.w(TAG, "unknown arm lane: " + part);
                    break;
            }
        }
    }

    private void updateSensorLabel() {
        String text = lane.describe();
        sensorsLabel.setText("sensors: " + text);
        sensorsLabel.setTextColor("off".equals(text) ? 0xFF99A0AA : 0xFF00D492);
    }

    /**
     * The sensor panel. Labels carry the state, so what the list says is always what the phone is
     * doing — a refused arm (no wheel on this pad) shows as "no wheel" rather than a checked box
     * that lies.
     */
    private void showSensorPanel() {
        final java.util.List<String> labels = new java.util.ArrayList<>();
        final java.util.List<Runnable> actions = new java.util.ArrayList<>();

        labels.add("orientation → camera:  " + (lane.isCameraArmed() ? "ARMED" : "off"));
        actions.add(() -> lane.armCamera(!lane.isCameraArmed()));

        if (lane.hasHeadTracker()) {
            labels.add("source:  " + (lane.isUsingHeadTracker()
                    ? "head tracker (dynamic)" : "game rotation vector"));
            actions.add(() -> lane.setUseHeadTracker(!lane.isUsingHeadTracker()));
        } else {
            labels.add("source:  game rotation vector  (no head tracker present)");
            actions.add(() -> { });
        }

        labels.add("tilt → wheel:  " + (lane.isTiltArmed() ? "ARMED" : "off")
                + (lane.hasWheel() ? "" : "   (this pad has no wheel)"));
        actions.add(() -> {
            if (lane.hasWheel()) {
                lane.armTilt(!lane.isTiltArmed());
            } else {
                Toast.makeText(this, "no STEERING_WHEEL widget on this pad",
                        Toast.LENGTH_SHORT).show();
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("Sensors")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    actions.get(which).run();
                    updateSensorLabel();
                    showSensorPanel();
                })
                .setNegativeButton("close", null)
                .show();
    }

    /**
     * The variant control: which half of the active shader's name pair (`<x>.mp` / `ctrl_<x>.mp`) is
     * asked for. It used to be the dwarf pad's own `off` switch, which left every other pad with no
     * way to reach its remote half at all (user, 2026-10-16: "each remote get a toggle"). It sends
     * the very same `off` switch event, so comrade's existing trigger does the reload — nothing on
     * the comrade side had to change.
     */
    private void flipVariant() {
        boolean on = !variantOn();
        PadView view = padView();
        if (view != null && view.variantOn() != null) {
            // This pad carries its own control: move that one, so the surface and the header agree.
            view.setVariant(on);
        } else {
            publishVariant(on);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_VARIANT, on).apply();
        }
        updateVariantLabel();
    }

    /** The dwarf pad's `off` switch, sent from the header, for a pad that has no control of its own. */
    private void publishVariant(boolean on) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", "off");
            o.put("type", "SWITCH");
            o.put("state", on);
        } catch (JSONException ignored) {
        }
        publisher.publish(o.toString());
    }

    /**
     * The pad's own control when it has one, else this remote's memory of what it last asked for.
     * There is no downlink, so the header cannot read comrade's actual choice: it starts at the
     * app's own resting state (the auto half) and follows every tap from there.
     */
    private boolean variantOn() {
        PadView view = padView();
        if (view != null) {
            Boolean own = view.variantOn();
            if (own != null) return own;
        }
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_VARIANT, false);
    }

    private void updateVariantLabel() {
        boolean on = variantOn();
        variantButton.setText(on ? "remote" : "auto");
        variantButton.setTextColor(on ? 0xFF00D492 : 0xFF99A0AA);
    }

    /**
     * Arrange mode: the finger positions widgets instead of driving them. Drops are written as they
     * happen, so leaving the surface (or the screen going off) mid-arrangement loses nothing.
     */
    private void toggleArrange() {
        PadView view = padView();
        if (view == null) return;
        boolean on = !view.isArranging();
        view.setArranging(on);
        arrangeButton.setText(on ? "done" : "arrange");
        arrangeButton.setTextColor(on ? 0xFF00D492 : 0xFF99A0AA);
        Toast.makeText(this, on
                        ? "arrange: drag any widget, tap done to finish"
                        : "layout saved to " + PadLayout.sidecarFor(view.profile().file).getName(),
                Toast.LENGTH_SHORT).show();
    }

    /** The pad surface currently on screen, if there is one. */
    private PadView padView() {
        return surface.getChildCount() > 0 && surface.getChildAt(0) instanceof PadView
                ? (PadView) surface.getChildAt(0) : null;
    }

    /**
     * Write the arrangement beside the export (never into it), so the pad file stays exactly what
     * DroidPad produced while the remote's own layout survives a re-export.
     */
    private void saveLayout(PadView view) {
        try {
            PadLayout.save(view.profile().file, view.layout());
        } catch (Exception e) {
            Log.w(TAG, "cannot write pad layout: " + e);
            Toast.makeText(this, "layout not saved: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void reload() {
        List<File> pads = PadProfile.listPadFiles(PADS_DIR);
        String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST, null);
        for (File f : pads) {
            if (last != null && f.getName().equals(last)) {
                show(f);
                return;
            }
        }
        if (!pads.isEmpty()) show(pads.get(0));
    }

    private TextView message(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFB0B0B0);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(24), dp(24), dp(24), dp(24));
        return tv;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
