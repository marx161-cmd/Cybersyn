package com.termux.cybersyn.stub2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * ISLAND REMOTE (Cybersyn scope.md 2026-10-26): the starbox pad's buttons as ONE ongoing notification. It carries a
 * progress value, so the crDroid dynamic-bar island promotes it and draws this custom layout when the pill is opened.
 * Presses go out exactly like a pad finger (PadPublisher -> the ingest FIFO -> DroidPad/Events): no comrade change.
 * MODE opens the option list inside the panel, a mode becomes the PICK, CONFIRM presses the picked pad button.
 * The sticks stay on the floating pad (a notification cannot be dragged on).
 */
public final class IslandRemote extends BroadcastReceiver {

    static final String CHANNEL = "island_remote";
    static final int ID = 4711;
    static final String A_PRESS = "com.termux.cybersyn.stub2.ISLAND_PRESS";
    static final String A_MODE = "com.termux.cybersyn.stub2.ISLAND_MODE";
    static final String A_PICK = "com.termux.cybersyn.stub2.ISLAND_PICK";
    static final String A_CONFIRM = "com.termux.cybersyn.stub2.ISLAND_CONFIRM";
    static final String A_SWITCH = "com.termux.cybersyn.stub2.ISLAND_SWITCH";
    private static final String PREFS = "island_remote";

    @Override
    public void onReceive(Context ctx, Intent in) {
        String a = in.getAction();
        if (a == null) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();
        String last;
        switch (a) {
            case A_PRESS: {
                String id = in.getStringExtra("id");
                press(id);
                last = id;
                break;
            }
            case A_MODE:
                ed.putBoolean("open", !sp.getBoolean("open", false));
                last = sp.getBoolean("open", false) ? "list closed" : "pick a mode";
                break;
            case A_PICK: {
                int k = in.getIntExtra("k", -1);
                if (k >= 0 && k < IslandTable.MODES.length) ed.putInt("pick", k);
                ed.putBoolean("open", false);
                last = "picked " + (k >= 0 && k < IslandTable.MODES.length ? IslandTable.MODES[k][1] : "?")
                        + " -- CONFIRM sends it";
                break;
            }
            case A_CONFIRM: {
                int k = sp.getInt("pick", -1);
                if (k < 0) {
                    last = "nothing picked (MODE first)";
                } else {
                    press(IslandTable.MODES[k][0]);
                    last = "sent " + IslandTable.MODES[k][1];
                    ed.remove("pick");
                }
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
                last = null;        // SHOW / BOOT: just (re)post
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

    /** Post (or refresh) the remote. Safe to call any time. */
    public static void show(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Island remote",
                NotificationManager.IMPORTANCE_LOW));
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int k = sp.getInt("pick", -1);
        String pick = k >= 0 && k < IslandTable.MODES.length ? IslandTable.MODES[k][1] : null;
        String status = "DESK" + (pick != null ? " · pick " + pick : "") + " · " + sp.getString("last", "ready");
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stub)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentTitle(pick != null ? "DESK · " + pick : "DESK remote")
                .setSubText(status)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setCustomContentView(views(ctx, R.layout.island_remote_small, sp, status, pick))
                .setCustomBigContentView(views(ctx, R.layout.island_remote, sp, status, pick))
                .setProgress(100, 100, false)          // = promoted into the island (NotificationIslandManager)
                .build();
        nm.notify(ID, n);
    }

    private static RemoteViews views(Context ctx, int layout, SharedPreferences sp, String status, String pick) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), layout);
        v.setTextViewText(R.id.isl_status, status);
        boolean open = sp.getBoolean("open", false);
        v.setTextViewText(R.id.isl_mode, (open ? "MODE ▴ " : "MODE ▾ ") + (pick != null ? pick : ""));
        v.setOnClickPendingIntent(R.id.isl_mode, pi(ctx, A_MODE, 1, null, -1));
        v.setOnClickPendingIntent(R.id.isl_confirm, pi(ctx, A_CONFIRM, 2, null, -1));
        if (layout != R.layout.island_remote) return v;
        v.setViewVisibility(R.id.isl_pick0, open ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.isl_pick1, open ? View.VISIBLE : View.GONE);
        for (int i = 0; i < IslandTable.MODES.length; i++) {
            v.setOnClickPendingIntent(vid(ctx, "isl_m" + i), pi(ctx, A_PICK, 100 + i, null, i));
        }
        for (int i = 0; i < IslandTable.BUTTONS.length; i++) {
            v.setOnClickPendingIntent(vid(ctx, IslandTable.BUTTONS[i][1]),
                    pi(ctx, A_PRESS, 200 + i, IslandTable.BUTTONS[i][0], -1));
        }
        for (int i = 0; i < IslandTable.SWITCHES.length; i++) {
            String[] s = IslandTable.SWITCHES[i];
            int id = vid(ctx, s[2]);
            v.setTextViewText(id, s[1] + (sp.getBoolean("sw_" + s[0], false) ? " ●" : " ○"));
            v.setOnClickPendingIntent(id, pi(ctx, A_SWITCH, 300 + i, s[0], -1));
        }
        return v;
    }

    private static int vid(Context ctx, String name) {
        return ctx.getResources().getIdentifier(name, "id", ctx.getPackageName());
    }

    private static PendingIntent pi(Context ctx, String action, int code, String id, int k) {
        Intent i = new Intent(action).setClass(ctx, IslandRemote.class);
        if (id != null) i.putExtra("id", id);
        if (k >= 0) i.putExtra("k", k);
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
