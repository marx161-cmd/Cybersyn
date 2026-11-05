package com.termux.cybersyn.stub2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * ISLAND REMOTE, generic since 2026-11-01 (shader-backdrop scope.md "REMOTES PER SHADER"): ONE ongoing notification
 * that shows the BUTTONs and SWITCHes of the pad belonging to the shader comrade is running right now. The 10-26
 * version was a fixed table of the starbox pad (so the pill "was always ringbox"); this one reads
 * <pre>
 *   ~/.cybersyn/active_shader          the shader dir base name, written by comrade's shader-remote-announce
 *   ~/.cybersyn/pads/remotes.json      shader -> pad name (pads/remotes.json in shader-backdrop, synced)
 *   ~/.cybersyn/pads/<pad>.json        the DroidPad export: BUTTON / SWITCH items
 * </pre>
 * and fills a generic layout (4 x 4 buttons, 4 switches) at runtime. Presses go out exactly like a pad finger
 * (PadPublisher -> ingest FIFO -> DroidPad/Events). Sticks and sliders stay on the floating pad. It carries a progress
 * value so the crDroid dynamic-bar island promotes it and draws this layout when the pill is opened.
 */
public final class IslandRemote extends BroadcastReceiver {

    private static final String TAG = "stub2-island";
    static final String CHANNEL = "island_remote";
    static final int ID = 4711;
    static final String A_PRESS = "com.termux.cybersyn.stub2.ISLAND_PRESS";
    static final String A_SWITCH = "com.termux.cybersyn.stub2.ISLAND_SWITCH";
    private static final String PREFS = "island_remote";
    private static final File HOME = new File("/data/data/com.termux/files/home");
    private static final File ACTIVE = new File(HOME, ".cybersyn/active_shader");
    private static final File PADS = new File(HOME, ".cybersyn/pads");
    static final int MAX_BUTTONS = 16;
    static final int MAX_SWITCHES = 4;

    /** What the pill shows: resolved from the three files above. */
    static final class Remote {
        String shader = "";          // active shader (dir base name), "" = unknown
        String pad = null;           // pad name, null = this shader has no remote
        final List<String[]> buttons = new ArrayList<>();   // {id, label}
        final List<String[]> switches = new ArrayList<>();  // {id, label}
        String problem = null;       // shown in the status line when something is off
    }

    @Override
    public void onReceive(Context ctx, Intent in) {
        String a = in.getAction();
        if (a == null) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();
        String last = null;
        switch (a) {
            case A_PRESS: {
                String id = in.getStringExtra("id");
                press(id);
                last = id;
                break;
            }
            case A_SWITCH: {
                String id = in.getStringExtra("id");
                boolean on = !sp.getBoolean("sw_" + id, false);
                ed.putBoolean("sw_" + id, on);
                JSONObject o = new JSONObject();
                try {
                    o.put("id", id).put("type", "SWITCH").put("state", on);
                } catch (JSONException ignored) {
                }
                publish(o.toString());
                last = id + (on ? " ON" : " OFF");
                break;
            }
            default:
                break;        // SHOW / BOOT: just (re)post
        }
        if (last != null) ed.putString("last", last);
        ed.apply();
        show(ctx);
    }

    /** One pad press: PRESS then RELEASE, the same two events a finger makes on a ButtonWidget. */
    private static void press(String id) {
        if (id == null) return;
        for (String st : new String[] {"PRESS", "RELEASE"}) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id).put("type", "BUTTON").put("state", st);
            } catch (JSONException ignored) {
            }
            publish(o.toString());
        }
    }

    private static void publish(String json) {
        PadPublisher p = new PadPublisher();
        p.publish(json);
        p.close();
    }

    private static String readText(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    /** shader -> pad name: remotes.json first, then a pad file named like the shader, else none. */
    static String padFor(String shader) {
        if (shader == null || shader.isEmpty()) return null;
        String map = readText(new File(PADS, "remotes.json"));
        if (map != null) {
            try {
                JSONObject o = new JSONObject(map);
                String p = o.optString(shader, null);
                if (p != null && !p.isEmpty()) return p;
            } catch (JSONException e) {
                Log.w(TAG, "remotes.json: " + e);
            }
        }
        return new File(PADS, shader + ".json").isFile() ? shader : null;
    }

    /** pad name -> shader (the inverse of remotes.json; a pad named like a shader maps to itself). */
    static String shaderFor(String pad) {
        if (pad == null) return null;
        if (pad.endsWith(".json")) pad = pad.substring(0, pad.length() - 5);
        String map = readText(new File(PADS, "remotes.json"));
        if (map != null) {
            try {
                JSONObject o = new JSONObject(map);
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    if (!k.startsWith("_") && pad.equals(o.optString(k))) return k;
                }
            } catch (JSONException ignored) {
            }
        }
        return pad;
    }

    static Remote resolve() {
        Remote r = new Remote();
        String s = readText(ACTIVE);
        if (s == null || s.isEmpty()) {
            r.problem = "no active shader yet (comrade's shader-remote-announce writes it)";
            return r;
        }
        r.shader = s;
        r.pad = padFor(s);
        if (r.pad == null) {
            r.problem = "no remote for this shader";
            return r;
        }
        String doc = readText(new File(PADS, r.pad + ".json"));
        if (doc == null) {
            r.problem = "pad file missing: " + r.pad + ".json";
            return r;
        }
        try {
            JSONArray items = new JSONObject(doc).getJSONArray("controlPadItems");
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                String type = it.optString("itemType", "");
                String id = it.optString("itemIdentifier", "");
                if (id.isEmpty()) continue;
                String label = id;
                String props = it.optString("properties", null);
                if (props != null) {
                    try {
                        String t = new JSONObject(props).optString("text", "");
                        if (!t.isEmpty()) label = t;
                    } catch (JSONException ignored) {
                    }
                }
                if ("BUTTON".equals(type) && r.buttons.size() < MAX_BUTTONS) r.buttons.add(new String[] {id, label});
                else if ("SWITCH".equals(type) && r.switches.size() < MAX_SWITCHES) r.switches.add(new String[] {id, label});
            }
        } catch (JSONException e) {
            r.problem = "pad file unreadable: " + e.getMessage();
        }
        return r;
    }

    /** Post (or refresh) the remote. Safe to call any time. */
    public static void show(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Island remote",
                NotificationManager.IMPORTANCE_LOW));
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Remote r = resolve();
        String title = "DESK · " + (r.shader.isEmpty() ? "?" : r.shader);
        String status = (r.pad != null ? r.pad + " · " : "") + (r.problem != null ? r.problem : sp.getString("last", "ready"));
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stub)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentTitle(title)
                .setSubText(status)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setCustomContentView(views(ctx, R.layout.island_remote_small, sp, r, status))
                .setCustomBigContentView(views(ctx, R.layout.island_remote, sp, r, status))
                .setProgress(100, 100, false)          // = promoted into the island (NotificationIslandManager)
                .setContentIntent(openRemote(ctx, r))  // v12 (a ROM that honours a body tap; crDroid only expands)
                .build();
        nm.notify(ID, n);
    }

    private static RemoteViews views(Context ctx, int layout, SharedPreferences sp, Remote r, String status) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), layout);
        v.setTextViewText(R.id.isl_status, "DESK · " + (r.shader.isEmpty() ? "?" : r.shader) + " · " + status
                + (r.pad != null ? "   open ▸" : ""));
        // v12 (2026-11-05, user: the island remote must open the full Cybersyn remote that mirrors the desktop one):
        // the status line is the tap target (crDroid only expands / collapses on a tap on the notification body)
        PendingIntent open = openRemote(ctx, r);
        if (open != null) v.setOnClickPendingIntent(R.id.isl_status, open);
        boolean small = layout != R.layout.island_remote;
        int nb = small ? Math.min(r.buttons.size(), 4) : r.buttons.size();
        int perRow = 4;
        for (int i = 0; i < MAX_BUTTONS; i++) {
            int id = vid(ctx, "isl_b" + i);
            if (id == 0) continue;
            if (i < nb) {
                v.setTextViewText(id, r.buttons.get(i)[1]);
                v.setViewVisibility(id, View.VISIBLE);
                v.setOnClickPendingIntent(id, pi(ctx, A_PRESS, 200 + i, r.buttons.get(i)[0]));
            } else {
                v.setViewVisibility(id, View.GONE);
            }
        }
        for (int row = 0; row < MAX_BUTTONS / perRow; row++) {
            int rid = vid(ctx, "isl_r" + row);
            if (rid != 0) v.setViewVisibility(rid, row * perRow < nb ? View.VISIBLE : View.GONE);
        }
        if (small) return v;
        int ns = r.switches.size();
        for (int i = 0; i < MAX_SWITCHES; i++) {
            int id = vid(ctx, "isl_s" + i);
            if (id == 0) continue;
            if (i < ns) {
                String[] s = r.switches.get(i);
                v.setTextViewText(id, s[1] + (sp.getBoolean("sw_" + s[0], false) ? " ●" : " ○"));
                v.setViewVisibility(id, View.VISIBLE);
                v.setOnClickPendingIntent(id, pi(ctx, A_SWITCH, 300 + i, s[0]));
            } else {
                v.setViewVisibility(id, View.GONE);
            }
        }
        int rs = vid(ctx, "isl_rs");
        if (rs != 0) v.setViewVisibility(rs, ns > 0 ? View.VISIBLE : View.GONE);
        return v;
    }

    /** The full remote (RemoteActivity) on this shader's pad; it decides floating vs normal itself. */
    private static PendingIntent openRemote(Context ctx, Remote r) {
        if (r.pad == null) return null;
        Intent i = new Intent(Intent.ACTION_MAIN).setClass(ctx, RemoteActivity.class)
                .putExtra("pad", r.pad)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(ctx, 400, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int vid(Context ctx, String name) {
        return ctx.getResources().getIdentifier(name, "id", ctx.getPackageName());
    }

    private static PendingIntent pi(Context ctx, String action, int code, String id) {
        Intent i = new Intent(action).setClass(ctx, IslandRemote.class);
        if (id != null) i.putExtra("id", id);
        return PendingIntent.getBroadcast(ctx, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Exported: boot + `am broadcast -a com.termux.cybersyn.stub2.SHOW_ISLAND` (only re-posts, presses nothing). */
    public static final class Show extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent in) {
            show(ctx);
        }
    }
}
