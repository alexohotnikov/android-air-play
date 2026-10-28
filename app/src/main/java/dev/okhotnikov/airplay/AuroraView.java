package dev.okhotnikov.airplay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Soft colour fields drifting over a dark base, like an Apple TV idle screen. Each blob follows a Lissajous
 * path while its hue walks around the colour wheel; a full cycle takes under a minute.
 * Runs at about 30 fps and only while visible: stop() frees the GPU for the video decoder.
 */
final class AuroraView extends View {

    private static final long FRAME_MS = 33;
    private static final int BASE = 0xFF07070D;

    // Per blob: starting hue, hue speed (degrees per second), path speeds and phases, radius as a share of width.
    private static final float[][] BLOBS = {
            {250f, 7.0f, 0.075f, 0.055f, 0.0f, 1.3f, 0.60f},
            {320f, 5.2f, 0.048f, 0.070f, 2.1f, 0.4f, 0.52f},
            {195f, 8.5f, 0.066f, 0.043f, 4.0f, 2.6f, 0.48f},
            {35f, 6.1f, 0.052f, 0.081f, 5.3f, 3.7f, 0.42f},
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final float[] hsv = {0f, 0.85f, 0.95f};
    private final long start = SystemClock.uptimeMillis();
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };

    AuroraView(Context context) {
        super(context);
    }

    void start() {
        if (running) return;
        running = true;
        post(tick);
    }

    void stop() {
        running = false;
        removeCallbacks(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawColor(BASE);
        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        for (float[] b : BLOBS) {
            hsv[0] = (b[0] + b[1] * t) % 360f;
            int color = Color.HSVToColor(hsv);
            float cx = w * (0.5f + 0.38f * (float) Math.sin(b[2] * t * 6.283f + b[4]));
            float cy = h * (0.5f + 0.34f * (float) Math.sin(b[3] * t * 6.283f + b[5]));
            float r = w * b[6] * (1f + 0.15f * (float) Math.sin(t * 0.6f + b[4]));
            // A unit gradient moved into place by the matrix: only the colour needs a new shader each frame.
            RadialGradient g = new RadialGradient(0f, 0f, 1f,
                    new int[]{color & 0xCCFFFFFF, color & 0x55FFFFFF, color & 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            matrix.setScale(r, r);
            matrix.postTranslate(cx, cy);
            g.setLocalMatrix(matrix);
            paint.setShader(g);
            canvas.drawCircle(cx, cy, r, paint);
        }
    }
}
