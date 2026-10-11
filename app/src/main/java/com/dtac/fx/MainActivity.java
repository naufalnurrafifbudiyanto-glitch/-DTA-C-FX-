package com.dtac.fx;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView text = new TextView(this);

        text.setText("DTA-C FX\nV1");
        text.setTextSize(28);
        text.setTextColor(Color.WHITE);
        text.setGravity(Gravity.CENTER);

        text.setBackgroundColor(Color.BLACK);

        setContentView(text);
    }
}
