package dev.okhotnikov.airplay;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Music-only AirPlay, Apple Music style: the artwork large with title, artist and album beside it and a thin
 * progress bar, over the same artwork blurred and darkened. Reads {@link NowPlaying}; update() after a change.
 */
final class NowPlayingView extends FrameLayout {

    private static final long TICK_MS = 500;

    private final float dp;
    private final FrameLayout backdrops;
    private ImageView backdrop;
    private ImageView backdropNext;
    private final int artPx;
    private final ImageView artwork;
    private final TextView title;
    private final TextView artist;
    private final TextView album;
    private final ProgressLine progress;
    private final TextView elapsed;
    private final TextView remaining;
    private final View timeRow;
    private final ObjectAnimator breathe;
    private Bitmap shownCover;
    private String shownKey;
    private boolean shownPaused;
    private final View textBlock;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            showProgress();
            postDelayed(this, TICK_MS);
        }
    };

    NowPlayingView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        Emoji.init(context);
        setBackgroundColor(0xFF0B0B12);

        // Two plain layers cross-fade by alpha. TransitionDrawable (and elevation with an outline clip on a view
        // that scales and fades) crashed this TV's Mali driver in RenderThread when the artwork arrived.
        backdrops = new FrameLayout(context);
        backdrop = new ImageView(context);
        backdropNext = new ImageView(context);
        for (ImageView v : new ImageView[]{backdrop, backdropNext}) {
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            backdrops.addView(v, new LayoutParams(-1, -1));
        }
        backdropNext.setAlpha(0f);
        addView(backdrops, new LayoutParams(-1, -1));
        View shade = new View(context);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x8C000000, 0x66000000, 0xB3000000}));
        addView(shade, new LayoutParams(-1, -1));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        artwork = new ImageView(context);
        artwork.setScaleType(ImageView.ScaleType.FIT_XY);
        int art = (int) (300 * dp);
        artPx = art;
        row.addView(artwork, new LinearLayout.LayoutParams(art, art));

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        title = label(34, 0xFFFFFFFF, "sans-serif-medium", 2);
        artist = label(24, 0xD9FFFFFF, "sans-serif", 1);
        album = label(18, 0x80FFFFFF, "sans-serif", 1);
        text.addView(title);
        text.addView(artist, margins(10));
        text.addView(album, margins(4));

        progress = new ProgressLine(context);
        text.addView(progress, sized(-1, (int) (6 * dp), 32));
        LinearLayout times = new LinearLayout(context);
        elapsed = label(14, 0x99FFFFFF, "sans-serif", 1);
        remaining = label(14, 0x99FFFFFF, "sans-serif", 1);
        remaining.setGravity(Gravity.END);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1f));
        times.addView(remaining, new LinearLayout.LayoutParams(0, -2, 1f));
        text.addView(times, margins(8));
        timeRow = times;

        textBlock = text;
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams((int) (440 * dp), -2);
        tp.leftMargin = (int) (52 * dp);
        row.addView(text, tp);
        addView(row, new LayoutParams(-2, -2, Gravity.CENTER));

        // The backdrop drifts in and out a little, so a long song does not leave a still picture on the panel.
        breathe = ObjectAnimator.ofFloat(backdrops, View.SCALE_X, 1.05f, 1.15f);
        breathe.setDuration(18000);
        breathe.setRepeatMode(ValueAnimator.REVERSE);
        breathe.setRepeatCount(ValueAnimator.INFINITE);
        breathe.addUpdateListener(a -> backdrops.setScaleY((float) a.getAnimatedValue()));
    }

    void start() {
        update();
        progress.setRunning(true);
        removeCallbacks(tick);
        post(tick);
        if (!breathe.isStarted()) breathe.start();
    }

    void stop() {
        progress.setRunning(false);
        removeCallbacks(tick);
        breathe.cancel();
    }

    void update() {
        String key = NowPlaying.title + "\n" + NowPlaying.artist + "\n" + NowPlaying.album;
        boolean animate = isShown() && shownKey != null;
        if (!key.equals(shownKey)) {
            shownKey = key;
            if (animate) swapText();
            else applyText();
        }
        if (NowPlaying.cover != shownCover) {
            shownCover = NowPlaying.cover;
            if (animate) swapCover();
            else applyCover(false);
        }
        showPaused();
        showProgress();
    }

    /** Paused like Apple Music: the artwork settles back a little and the time dims; playing brings it forward. */
    private void showPaused() {
        boolean paused = !NowPlaying.isPlaying();
        if (paused == shownPaused) return;
        shownPaused = paused;
        float scale = artworkScale();
        artwork.animate().scaleX(scale).scaleY(scale).setDuration(450)
                .setInterpolator(new DecelerateInterpolator(2f)).withEndAction(null).start();
        float dim = paused ? 0.45f : 1f;
        progress.animate().alpha(dim).setDuration(450).start();
        timeRow.animate().alpha(dim).setDuration(450).start();
    }

    private float artworkScale() {
        return shownPaused ? 0.86f : 1f;
    }

    // Track changes the Apple Music way: the old title lifts and fades, the new one rises into place; the artwork
    // shrinks away and the new one settles in from slightly smaller; the backdrop cross-fades between them.
    private void swapText() {
        float lift = 14 * dp;
        textBlock.animate().cancel();
        textBlock.animate().alpha(0f).translationY(-lift).setDuration(180)
                .setInterpolator(new AccelerateInterpolator()).withEndAction(() -> {
                    applyText();
                    textBlock.setTranslationY(lift);
                    textBlock.animate().alpha(1f).translationY(0f).setDuration(420)
                            .setInterpolator(new DecelerateInterpolator(2f)).withEndAction(null).start();
                }).start();
    }

    private void swapCover() {
        artwork.animate().cancel();
        artwork.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(200)
                .setInterpolator(new AccelerateInterpolator()).withEndAction(() -> {
                    applyCover(true);
                    float scale = artworkScale();
                    artwork.animate().alpha(1f).scaleX(scale).scaleY(scale).setDuration(480)
                            .setInterpolator(new DecelerateInterpolator(2f)).withEndAction(null).start();
                }).start();
    }

    private void applyText() {
        Emoji.setText(title, NowPlaying.title.isEmpty() ? getContext().getString(R.string.audio_only) : NowPlaying.title);
        Emoji.setText(artist, NowPlaying.artist);
        artist.setVisibility(NowPlaying.artist.isEmpty() ? GONE : VISIBLE);
        Emoji.setText(album, NowPlaying.album);
        album.setVisibility(NowPlaying.album.isEmpty() ? GONE : VISIBLE);
    }

    private void applyCover(boolean crossFade) {
        Bitmap blurred = null;
        if (shownCover != null) {
            artwork.setScaleType(ImageView.ScaleType.FIT_XY);
            artwork.setImageBitmap(rounded(shownCover, artPx, 14 * dp));
            artwork.setBackground(null);
            blurred = blur(shownCover);
        } else {
            artwork.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            artwork.setImageResource(R.drawable.ic_music);
            GradientDrawable placeholder = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF3A3A4A, 0xFF1E1E28});
            placeholder.setCornerRadius(14 * dp);
            artwork.setBackground(placeholder);
        }
        if (!crossFade) {
            backdrop.setImageBitmap(blurred);
            return;
        }
        ImageView incoming = backdropNext;
        incoming.setImageBitmap(blurred);
        incoming.animate().cancel();
        incoming.setAlpha(0f);
        incoming.animate().alpha(1f).setDuration(800).withEndAction(() -> {
            backdrop.setAlpha(0f);
            backdropNext = backdrop;
            backdrop = incoming;
        }).start();
    }

    /** The artwork square at its shown size with its corners rounded in the pixels, not by an outline clip. */
    private static Bitmap rounded(Bitmap src, int size, float radius) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float scale = Math.max(size / (float) src.getWidth(), size / (float) src.getHeight());
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((size - src.getWidth() * scale) / 2f, (size - src.getHeight() * scale) / 2f);
        shader.setLocalMatrix(m);
        p.setShader(shader);
        c.drawRoundRect(new RectF(0, 0, size, size), radius, radius, p);
        return out;
    }

    private void showProgress() {
        boolean known = NowPlaying.hasProgress();
        progress.setVisibility(known ? VISIBLE : INVISIBLE);
        timeRow.setVisibility(known ? VISIBLE : INVISIBLE);
        if (!known) return;
        double d = NowPlaying.duration(), p = NowPlaying.position();
        progress.postInvalidateOnAnimation();
        elapsed.setText(clock(p));
        remaining.setText("−" + clock(d - p));
    }

    private static String clock(double seconds) {
        int s = (int) Math.round(seconds);
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format("%d:%02d", s / 60, s % 60);
    }

    /** A cheap strong blur: down to a few pixels and back up with filtering. RenderEffect needs Android 12. */
    private static Bitmap blur(Bitmap src) {
        Bitmap tiny = Bitmap.createScaledBitmap(src, 12, 12, true);
        Bitmap small = Bitmap.createScaledBitmap(tiny, 48, 48, true);
        return Bitmap.createScaledBitmap(small, 192, 192, true);
    }

    private TextView label(float sp, int color, String family, int lines) {
        TextView v = new TextView(getContext());
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(Typeface.create(family, Typeface.NORMAL));
        v.setMaxLines(lines);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setShadowLayer(10f, 0f, 2f, 0x55000000);
        return v;
    }

    private LinearLayout.LayoutParams margins(int topDp) {
        return sized(-1, -2, topDp);
    }

    private LinearLayout.LayoutParams sized(int w, int h, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.topMargin = (int) (topDp * dp);
        return lp;
    }

    /**
     * A thin rounded track with the played part in white. It reads the position itself on every frame while the
     * track plays, so it glides instead of stepping with the once-a-half-second text; a jump (seek, new track)
     * eases to the new place instead of snapping.
     */
    private static final class ProgressLine extends View {
        private static final long EASE_MS = 350;
        private static final float JUMP = 0.02f;

        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final DecelerateInterpolator ease = new DecelerateInterpolator(2f);
        private float shown;
        private float easeFrom;
        private long easeStart = -1;
        private boolean running;

        ProgressLine(Context c) {
            super(c);
            track.setColor(0x40FFFFFF);
            fill.setColor(0xE6FFFFFF);
        }

        void setRunning(boolean on) {
            running = on;
            postInvalidateOnAnimation();
        }

        private static float target() {
            double d = NowPlaying.duration();
            return d > 0 ? (float) Math.max(0, Math.min(1, NowPlaying.position() / d)) : 0f;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float target = target();
            long now = SystemClock.uptimeMillis();
            if (easeStart < 0 && Math.abs(target - shown) > JUMP) {
                easeStart = now;
                easeFrom = shown;
            }
            if (easeStart >= 0) {
                float t = Math.min(1f, (now - easeStart) / (float) EASE_MS);
                shown = easeFrom + (target - easeFrom) * ease.getInterpolation(t);
                if (t >= 1f) easeStart = -1;
            } else {
                shown = target;
            }
            float r = getHeight() / 2f;
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, r, r, track);
            rect.right = getWidth() * shown;
            if (rect.right > 0) canvas.drawRoundRect(rect, r, r, fill);
            if (isShown() && (easeStart >= 0 || (running && NowPlaying.isPlaying()))) postInvalidateOnAnimation();
        }
    }
}
