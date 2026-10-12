package com.dtac.fx;

import android.content.Context;
import android.util.AttributeSet;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

public class SpectrumView extends View {

    private final Paint paint =
            new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float[] values =
            new float[32];

    public SpectrumView(Context context) {
        super(context);
        init();
    }

    public SpectrumView(
            Context context,
            AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SpectrumView(
            Context context,
            AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {

        paint.setStrokeWidth(7f);

        paint.setStrokeCap(
                Paint.Cap.ROUND
        );

        paint.setTypeface(
                Typeface.DEFAULT_BOLD
        );
    }

    public void setSpectrum(float[] data) {

        if (data == null)
            return;

        int count =
                Math.min(
                        data.length,
                        values.length
                );

        System.arraycopy(
                data,
                0,
                values,
                0,
                count
        );

        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {

        super.onDraw(canvas);

        float width = getWidth();
        float height = getHeight();

        canvas.drawColor(
                android.graphics.Color.rgb(
                        8, 10, 13
                )
        );

        // Grid
        paint.setStrokeWidth(1f);

        paint.setColor(
                android.graphics.Color.rgb(
                        35, 40, 45
                )
        );

        for (int i = 1; i < 4; i++) {

            float y =
                    height -
                    (height * i / 4f);

            canvas.drawLine(
                    0,
                    y,
                    width,
                    y,
                    paint
            );
        }

        // Spectrum
        float spacing =
                width / values.length;

        paint.setStrokeWidth(
                Math.max(
                        3f,
                        spacing * 0.55f
                )
        );

        paint.setColor(
                android.graphics.Color.rgb(
                        0, 220, 255
                )
        );

        for (int i = 0;
             i < values.length;
             i++) {

            float level =
                    values[i] * 5.0f;

            level =
                    Math.max(
                            0.02f,
                            Math.min(
                                    1.0f,
                                    level
                            )
                    );

            float x =
                    spacing * i +
                    spacing * 0.5f;

            float barHeight =
                    level * height;

            canvas.drawLine(
                    x,
                    height - 4,
                    x,
                    height - barHeight,
                    paint
            );
        }
    }
}