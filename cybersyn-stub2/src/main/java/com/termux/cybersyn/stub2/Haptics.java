package com.termux.cybersyn.stub2;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Press feedback lifted from Artemis's on-screen controls
 * (`virtual_controller/keyboard/KeyBoardController.java`, the VibrationEffect composition there),
 * which is the part worth scavenging: typed primitives (TICK/CLICK/THUD) instead of one flat buzz.
 *
 * Everything degrades: no vibrator, no primitive support, or an older API all fall back to a short
 * one-shot. Nothing here ever throws into a touch handler.
 */
public final class Haptics {

    private final Vibrator vibrator;

    public Haptics(Context context) {
        Vibrator v = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager manager =
                        (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (manager != null) v = manager.getDefaultVibrator();
            } else {
                v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }
        } catch (Exception ignored) {
        }
        this.vibrator = v;
    }

    private void vibrate(VibrationEffect effect) {
        if (vibrator == null || effect == null) return;
        try {
            if (vibrator.hasVibrator()) vibrator.vibrate(effect);
        } catch (Exception ignored) {
        }
    }

    /** A button press: a tick into a click, the shape the keyboard controller uses. */
    public void press() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                VibrationEffect.Composition c = VibrationEffect.startComposition();
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.75f);
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f);
                vibrate(c.compose());
                return;
            } catch (Exception ignored) {
            }
        }
        vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    /** A stick engaging: the app's own 10 ms tick. */
    public void engage() {
        vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    /** A heavier hit: thud, then a softer one 35 ms later. */
    public void thud() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                VibrationEffect.Composition c = VibrationEffect.startComposition();
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.8f);
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.55f, 35);
                vibrate(c.compose());
                return;
            } catch (Exception ignored) {
            }
        }
        vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE));
    }
}
