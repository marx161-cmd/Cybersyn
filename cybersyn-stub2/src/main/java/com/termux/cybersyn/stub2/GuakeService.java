package com.termux.cybersyn.stub2;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.lang.reflect.Method;
import java.util.List;

/**
 * GUAKE PROTOTYPE (crDroid/GUAKE_TERMUX_SCOPE.md 2026-11-09): the same mechanism as LMOFreeform
 * (Apache-2.0), run from this system-UID app instead of system_server:
 * a trusted virtual display rendered into a TextureView inside a full-width overlay pinned under
 * the status bar; the real Termux task is moved onto that display; touches on the overlay are
 * injected into it; the IME falls back to the main display (as in LMOFreeform).
 *
 *   am broadcast -n com.termux.cybersyn.stub2/.GuakeReceiver -a dev.marx.guake.SHOW [--ei h 1000]
 *   am broadcast -n com.termux.cybersyn.stub2/.GuakeReceiver -a dev.marx.guake.HIDE
 *   am broadcast -n com.termux.cybersyn.stub2/.GuakeReceiver -a dev.marx.guake.TOGGLE
 *   am broadcast -n com.termux.cybersyn.stub2/.GuakeReceiver -a dev.marx.guake.RELEASE  (Termux back to normal)
 *   ... -a dev.marx.guake.SHOW --ei ms 700    (glitch burst length, saved; default GLITCH_MS_DEFAULT)
 *   ... -a dev.marx.guake.SHOW --ei dim 150   (black layer behind Termux, 0..255, saved; default DIM_DEFAULT)
 * Bottom strip (invisible, STRIP_DP) drags the height live; let go above FULL_AT of the screen ->
 * the display is released and Termux falls back to normal full screen.
 */
public final class GuakeService extends Service implements TextureView.SurfaceTextureListener {
    static final String TAG = "Guake";
    static final String ACTION_SHOW = "dev.marx.guake.SHOW";
    static final String ACTION_HIDE = "dev.marx.guake.HIDE";
    static final String ACTION_TOGGLE = "dev.marx.guake.TOGGLE";
    static final String ACTION_RELEASE = "dev.marx.guake.RELEASE";
    private static final ComponentName TERMUX = new ComponentName("com.termux", "com.termux.app.TermuxActivity");
    // DisplayManager.VIRTUAL_DISPLAY_FLAG_*: SECURE | OWN_CONTENT_ONLY | SUPPORTS_TOUCH (hidden) | TRUSTED (hidden)
    private static final int VD_FLAGS = 4 | 8 | 64 | 1024;
    private static final int DIM_DEFAULT = 150;   // ~60 % black behind the translucent Termux
    private static final int GLITCH_MS_DEFAULT = 550;   // live: --ei ms 700 (saved)
    private static final float STRIP_DP = 28f, FULL_AT = 0.70f, MIN_FRAC = 0.15f;

    private WindowManager wm;
    private FrameLayout root;
    private TextureView tex;
    private View strip;
    private WindowManager.LayoutParams lp;
    private VirtualDisplay vd;
    private Surface surface;
    private boolean shown, paused;
    private ValueAnimator anim;
    private int screenW, screenH, top, dpi, height;
    private SharedPreferences prefs;
    private Method injectMethod, setDisplayIdMethod;
    private InputManager im;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = getSystemService(WindowManager.class);
        im = getSystemService(InputManager.class);
        prefs = getSharedPreferences("guake", MODE_PRIVATE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("guake", "Guake panel", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "guake").setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Guake panel").setOngoing(true)
            // Own group: without it Android auto-groups this with the island remote (also stub2), and the
            // grouped remote drops out of the island bar (2026-11-09).
            .setGroup("guake").build();
        startForeground(41, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        try {
            injectMethod = InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
            setDisplayIdMethod = MotionEvent.class.getMethod("setDisplayId", int.class);
        } catch (Throwable t) { Log.e(TAG, "hidden input API missing", t); }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (intent != null && intent.hasExtra("h")) prefs.edit().putInt("h", intent.getIntExtra("h", 0)).apply();
        if (intent != null && intent.hasExtra("ms"))
            prefs.edit().putInt("ms", Math.max(50, Math.min(3000, intent.getIntExtra("ms", GLITCH_MS_DEFAULT)))).apply();
        if (intent != null && intent.hasExtra("dim")) {
            prefs.edit().putInt("dim", Math.max(0, Math.min(255, intent.getIntExtra("dim", DIM_DEFAULT)))).apply();
            applyDim();
        }
        Log.i(TAG, "command " + a + " shown=" + shown);
        if (ACTION_SHOW.equals(a)) show();
        else if (ACTION_HIDE.equals(a)) hide();
        else if (ACTION_TOGGLE.equals(a)) { if (shown) hide(); else show(); }
        else if (ACTION_RELEASE.equals(a)) release();
        return START_STICKY;
    }

    private void measure() {
        android.view.WindowMetrics m = wm.getMaximumWindowMetrics();
        screenW = m.getBounds().width();
        screenH = m.getBounds().height();
        Insets sb = wm.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.statusBars());
        top = sb.top;
        dpi = getResources().getDisplayMetrics().densityDpi;
        int def = Math.round(screenH * 0.40f);
        height = clampH(prefs.getInt("h", def));
    }

    private int clampH(int h) {
        int min = Math.round(screenH * MIN_FRAC);
        return Math.max(min, Math.min(h, screenH - top));
    }

    private void show() {
        if (anim != null) anim.cancel();
        if (root == null) {
            measure();
            buildOverlay();                 // starts above the screen, slides in once Termux is moved
        } else {
            resume();
            // Termux may have left the display (restart, opened full screen): pull it back in.
            if (vd != null && !termuxOnDisplay(vd.getDisplay().getDisplayId())) moveOrStartTermux(vd.getDisplay().getDisplayId());
        }
        lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        wm.updateViewLayout(root, lp);
        root.setTranslationY(0f);
        root.setVisibility(View.VISIBLE);
        glitch(1f, 0f, null);
        shown = true;
    }

    private void hide() {
        if (root == null || !shown) return;
        if (anim != null) anim.cancel();
        shown = false;
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        wm.updateViewLayout(root, lp);
        glitch(0f, 1f, () -> {
            root.setVisibility(View.INVISIBLE);
            pause();
        });
    }

    /**
     * Glitch the panel in/out in place (user 2026-11-09: "couldnt it glitch into place?").
     * iAmount 1 = shattered/invisible, 0 = clean. Shader = /data/local/tmp/shaderfx/guake.agsl if
     * present (live, like the other shaderfx surfaces), else GLITCH_AGSL (= shaders/guake.agsl, the approved hologram); a compile error = no effect.
     */
    private void glitch(float from, float to, Runnable end) {
        RuntimeShader sh = loadShader();
        anim = ValueAnimator.ofFloat(from, to);
        anim.setDuration(prefs.getInt("ms", GLITCH_MS_DEFAULT));
        long t0 = android.os.SystemClock.uptimeMillis();
        anim.addUpdateListener(va -> {
            float amt = (float) va.getAnimatedValue();
            if (sh == null) { root.setAlpha(1f - amt); return; }
            sh.setFloatUniform("iResolution", root.getWidth(), root.getHeight());
            sh.setFloatUniform("iAmount", amt);
            sh.setFloatUniform("iTime", (android.os.SystemClock.uptimeMillis() - t0) / 1000f);
            root.setRenderEffect(RenderEffect.createRuntimeShaderEffect(sh, "content"));
        });
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) {
                if (to == 0f) { root.setRenderEffect(null); root.setAlpha(1f); }
                if (!cancelled && end != null) end.run();
            }
        });
        anim.start();
    }

    private RuntimeShader loadShader() {
        String src = GLITCH_AGSL;
        java.io.File f = new java.io.File("/data/local/tmp/shaderfx/guake.agsl");
        try {
            if (f.canRead()) src = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) { Log.w(TAG, "guake.agsl unreadable, using built-in", t); }
        try {
            return new RuntimeShader(src);
        } catch (Throwable t) {
            Log.e(TAG, "glitch shader failed to compile, no effect", t);
            return null;
        }
    }

    private static final String GLITCH_AGSL =
        "// Guake panel: HOLOGRAM materialize (stub2 GuakeService). Live: re-read on every open/close.\n" +
        "// iAmount 1 = not there, 0 = solid. Open: 1->0 (beam sweeps down), close: 0->1 (beam sweeps up).\n" +
        "// 2026-11-09: user wanted \"less blocky / old TV static, more hologram appearing\"; then a less clean, ragged beam.\n" +
        "// Previous aggressive TV-static version: guake_tvstatic.agsl (scratch copy on comrade).\n" +
        "uniform shader content;\n" +
        "uniform float2 iResolution;\n" +
        "uniform float iAmount;\n" +
        "uniform float iTime;\n" +
        "\n" +
        "const half3 HOLO = half3(0.40, 1.00, 0.91);        // comrade teal #66FFE8\n" +
        "\n" +
        "float h(float2 p) { return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453); }\n" +
        "float vnoise(float x) { float i = floor(x); float f = fract(x); f = f * f * (3.0 - 2.0 * f); return mix(h(float2(i, 0.0)), h(float2(i + 1.0, 0.0)), f); }\n" +
        "\n" +
        "half4 main(float2 c) {\n" +
        "    float a = clamp(iAmount, 0.0, 1.0);\n" +
        "    if (a <= 0.001) return content.eval(c);\n" +
        "    float H = iResolution.y;\n" +
        "    float t = iTime;\n" +
        "\n" +
        "    // scan beam position: top -> bottom as the panel materializes (a 1 -> 0), with a jittery y\n" +
        "    float jit = (h(float2(floor(t * 30.0), 2.0)) - 0.5) * 14.0 * a;\n" +
        "    float reveal = (1.0 - a) * (H + 160.0) - 80.0 + jit;\n" +
        "    // ragged, wandering edge: big slow bends + small fast teeth along x\n" +
        "    float edge = (vnoise(c.x * 0.008 + t * 3.0) - 0.5) * 46.0\n" +
        "               + (vnoise(c.x * 0.05 - t * 13.0) - 0.5) * 16.0\n" +
        "               + (vnoise(c.x * 0.21 + t * 31.0) - 0.5) * 5.0;\n" +
        "    float d = c.y - reveal - edge;                // > 0 = not yet drawn\n" +
        "    // thickness varies along the line, segments drop out, brightness stutters\n" +
        "    float w = 4.0 + 12.0 * vnoise(c.x * 0.02 + t * 6.0);\n" +
        "    float seg = 0.25 + 0.75 * smoothstep(0.3, 0.55, vnoise(c.x * 0.012 + t * 8.0));\n" +
        "    float stut = 0.65 + 0.7 * h(float2(floor(t * 45.0), 4.0));\n" +
        "    float beam = exp(-abs(d) / w) * seg * stut;\n" +
        "    float halo = exp(-abs(d) / 60.0) * (0.6 + 0.4 * seg);\n" +
        "    // two faint ghost beams trailing behind the main one\n" +
        "    float g1 = exp(-abs(d + 22.0 + 6.0 * vnoise(c.x * 0.03 + t * 4.0)) / 3.0) * 0.35 * h(float2(floor(t * 25.0), 8.0));\n" +
        "    float g2 = exp(-abs(d + 48.0 + 10.0 * vnoise(c.x * 0.02 - t * 5.0)) / 2.5) * 0.2 * h(float2(floor(t * 20.0), 12.0));\n" +
        "    beam += (g1 + g2) * seg;\n" +
        "\n" +
        "    // smooth row wobble that settles, plus a slow sway\n" +
        "    float wob = (sin(c.y * 0.045 + t * 23.0) * 0.6 + (vnoise(c.y * 0.02 + t * 9.0) - 0.5)) * 38.0 * a;\n" +
        "    // a few thin glitch lines, rare\n" +
        "    float line = step(0.985 - a * 0.03, h(float2(floor(c.y / 3.0), floor(t * 30.0))));\n" +
        "    float2 p = float2(c.x + wob + line * (h(float2(c.y, t)) - 0.5) * 120.0 * a, c.y);\n" +
        "\n" +
        "    // soft chromatic aberration\n" +
        "    float sp = 10.0 * a + 6.0 * halo;\n" +
        "    half4 base = content.eval(p);\n" +
        "    half4 col = half4(content.eval(p + float2(sp, 0.0)).r, base.g, content.eval(p - float2(sp, 0.0)).b, base.a);\n" +
        "\n" +
        "    // holographic tint fading to true colour\n" +
        "    half lum = dot(col.rgb, half3(0.299, 0.587, 0.114));\n" +
        "    col.rgb = mix(col.rgb, HOLO * (lum * 1.4 + 0.08 * col.a), half(a * 0.85));\n" +
        "\n" +
        "    // fine moving scanlines + soft flicker\n" +
        "    float scan = 1.0 - 0.45 * a * (0.5 + 0.5 * sin((c.y + t * 240.0) * 2.1));\n" +
        "    float flick = 1.0 - a * 0.35 * vnoise(t * 40.0);\n" +
        "    col *= half(scan * flick);\n" +
        "\n" +
        "    // only the part above the beam exists (soft edge), beam glows on top\n" +
        "    float vis = 1.0 - smoothstep(-6.0, 6.0, d);\n" +
        "    col *= half(vis);\n" +
        "    half3 glow = HOLO * half(beam * 1.2 + halo * 0.35) * half(a > 0.02 ? 1.0 : 0.0);\n" +
        "    return half4(col.rgb + glow, max(col.a, half(beam * 0.9 + halo * 0.25)));\n" +
        "}\n";

    /** Hidden: no surface -> Android turns the virtual display off, Termux stops drawing (shells keep running). */
    private void pause() {
        if (vd != null && !paused) { vd.setSurface(null); paused = true; Log.i(TAG, "paused display"); }
    }

    private void resume() {
        if (vd != null && paused && surface != null) { vd.setSurface(surface); paused = false; Log.i(TAG, "resumed display"); }
    }

    private void applyDim() {
        if (root != null) root.setBackgroundColor(prefs.getInt("dim", DIM_DEFAULT) << 24);
    }


    private void buildOverlay() {
        root = new FrameLayout(this);
        applyDim();
        tex = new TextureView(this);
        tex.setOpaque(false);
        tex.setSurfaceTextureListener(this);
        tex.setOnTouchListener((v, e) -> { inject(e); return true; });
        root.addView(tex, new FrameLayout.LayoutParams(screenW, height));
        strip = new View(this);
        int stripPx = Math.round(STRIP_DP * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(screenW, stripPx, Gravity.BOTTOM);
        root.addView(strip, slp);
        strip.setOnTouchListener(new StripDrag());

        lp = new WindowManager.LayoutParams(screenW, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = 0;
        lp.y = top;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle("GuakePanel");
        wm.addView(root, lp);
        Log.i(TAG, "overlay " + screenW + "x" + height + " at y=" + top + " dpi=" + dpi);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        st.setDefaultBufferSize(screenW, height);
        if (vd != null) return;
        surface = new Surface(st);
        DisplayManager dm = getSystemService(DisplayManager.class);
        vd = dm.createVirtualDisplay("guake-termux", screenW, height, dpi, surface, VD_FLAGS);
        int id = vd.getDisplay().getDisplayId();
        Log.i(TAG, "virtual display " + id + " " + screenW + "x" + height);
        moveOrStartTermux(id);
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { st.setDefaultBufferSize(screenW, height); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }

    private boolean termuxOnDisplay(int displayId) {
        try {
            for (ActivityManager.RunningTaskInfo t : getSystemService(ActivityManager.class).getRunningTasks(100)) {
                if (t.baseActivity != null && TERMUX.getPackageName().equals(t.baseActivity.getPackageName()))
                    return t.getClass().getField("displayId").getInt(t) == displayId;   // TaskInfo.displayId is @hide
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private void moveOrStartTermux(int displayId) {
        int taskId = -1;
        try {
            List<ActivityManager.RunningTaskInfo> tasks = getSystemService(ActivityManager.class).getRunningTasks(100);
            for (ActivityManager.RunningTaskInfo t : tasks) {
                ComponentName base = t.baseActivity;
                if (base != null && TERMUX.getPackageName().equals(base.getPackageName())) { taskId = t.taskId; break; }
            }
        } catch (Throwable t) { Log.w(TAG, "task lookup failed", t); }
        if (taskId != -1) {
            try {
                Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
                atm.getClass().getMethod("moveRootTaskToDisplay", int.class, int.class).invoke(atm, taskId, displayId);
                Log.i(TAG, "moved Termux task " + taskId + " to display " + displayId);
                return;
            } catch (Throwable t) { Log.w(TAG, "moveRootTaskToDisplay failed, starting instead", t); }
        }
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(TERMUX)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions o = ActivityOptions.makeBasic().setLaunchDisplayId(displayId);
        startActivity(i, o.toBundle());
        Log.i(TAG, "started Termux on display " + displayId);
    }

    private void inject(MotionEvent e) {
        if (vd == null || injectMethod == null) return;
        MotionEvent c = MotionEvent.obtain(e);
        try {
            c.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            setDisplayIdMethod.invoke(c, vd.getDisplay().getDisplayId());
            injectMethod.invoke(im, c, 0);
        } catch (Throwable t) {
            Log.w(TAG, "inject failed", t);
        } finally {
            c.recycle();
        }
    }

    private void resizeTo(int h) {
        height = clampH(h);
        lp.height = height;
        tex.getLayoutParams().height = height;
        tex.requestLayout();
        wm.updateViewLayout(root, lp);
        if (vd != null) {
            vd.resize(screenW, height, dpi);
            if (tex.getSurfaceTexture() != null) tex.getSurfaceTexture().setDefaultBufferSize(screenW, height);
        }
    }

    /** Invisible bottom strip: drag = live height; release past FULL_AT = normal full-screen Termux. */
    private final class StripDrag implements View.OnTouchListener {
        private float startY;
        private int startH;
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startY = e.getRawY(); startH = height; return true;
                case MotionEvent.ACTION_MOVE:
                    resizeTo(startH + Math.round(e.getRawY() - startY)); return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (top + height >= screenH * FULL_AT) { release(); return true; }
                    prefs.edit().putInt("h", height).apply();
                    return true;
            }
            return false;
        }
    }

    /** Drop the display: Android moves Termux back to the main screen as a normal full-screen app. */
    private void release() {
        if (anim != null) anim.cancel();
        if (vd != null) { vd.release(); vd = null; }
        paused = false;
        if (surface != null) { surface.release(); surface = null; }
        if (root != null) { try { wm.removeViewImmediate(root); } catch (Throwable ignored) { } root = null; }
        shown = false;
        Log.i(TAG, "released -> Termux full screen");
    }

    @Override
    public void onDestroy() {
        release();
        super.onDestroy();
    }
}
