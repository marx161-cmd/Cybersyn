package com.comrade.quicktaprelay;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public final class QuickTapRelayActivity extends Activity {
    private static final String CYBERSYN_PACKAGE = "com.termux.cybersyn";
    private static final String ACTION_EXTERNAL_TRIGGER = "com.termux.cybersyn.action.EXTERNAL_TRIGGER";
    private static final String EXTRA_TRIGGER_NAME = "com.termux.cybersyn.extra.TRIGGER_NAME";
    private static final String EXTRA_SOURCE = "com.termux.cybersyn.extra.SOURCE";
    private static final String EXTRA_TRIGGER_TIME_MS = "com.termux.cybersyn.extra.TRIGGER_TIME_MS";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent relay = new Intent(ACTION_EXTERNAL_TRIGGER)
                .setPackage(CYBERSYN_PACKAGE)
                .putExtra(EXTRA_TRIGGER_NAME, "quick_tap")
                .putExtra(EXTRA_SOURCE, "pixel_quick_tap_stub")
                .putExtra(EXTRA_TRIGGER_TIME_MS, System.currentTimeMillis());
        sendBroadcast(relay);
        finish();
        overridePendingTransition(0, 0);
    }
}
