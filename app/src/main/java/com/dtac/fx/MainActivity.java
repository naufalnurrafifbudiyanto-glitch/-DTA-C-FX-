package com.dtac.fx;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.TextView;

public class MainActivity extends Activity {

    static {
        System.loadLibrary("dtac_fx");
    }

    private native boolean nativeStartAudio();
    private native void nativeStopAudio();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView text = new TextView(this);

        text.setText("DTA-C FX\nNative Engine V1");
        text.setTextSize(26);
        text.setTextColor(Color.WHITE);
        text.setGravity(Gravity.CENTER);
        text.setBackgroundColor(Color.BLACK);

        setContentView(text);

        nativeStartAudio();
    }

    @Override
    protected void onDestroy() {
        nativeStopAudio();
        super.onDestroy();
    }
}
