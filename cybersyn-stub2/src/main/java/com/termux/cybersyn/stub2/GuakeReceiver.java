package com.termux.cybersyn.stub2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** dev.marx.guake.{SHOW,HIDE,TOGGLE,RELEASE} -> GuakeService (cutout tap, Quick Tap, adb). */
public final class GuakeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Intent s = new Intent(c, GuakeService.class).setAction(i.getAction());
        if (i.getExtras() != null) s.putExtras(i.getExtras());
        c.startForegroundService(s);
    }
}
