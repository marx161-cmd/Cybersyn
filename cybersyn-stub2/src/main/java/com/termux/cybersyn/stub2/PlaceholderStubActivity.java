package com.termux.cybersyn.stub2;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public final class PlaceholderStubActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView view = new TextView(this);
        view.setText("Cybersyn Stub 2");
        view.setTextColor(Color.WHITE);
        view.setTextSize(18f);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundColor(Color.rgb(13, 17, 23));
        setContentView(view);
    }
}
