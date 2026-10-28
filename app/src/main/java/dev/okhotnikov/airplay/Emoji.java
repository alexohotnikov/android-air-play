package dev.okhotnikov.airplay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.widget.TextView;

import androidx.emoji2.bundled.BundledEmojiCompatConfig;
import androidx.emoji2.text.EmojiCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Track text as the sender's screen shows it: colour emoji from the bundled font (loaded once in the
 * background; text set before then is redrawn after), and characters in a combining circle (U+20DD, as in
 * "FLASHBACK!⃝") drawn by {@link CircleSpan}, since the TV's fonts lack the mark and a fallback misplaces it.
 */
final class Emoji {

    private static final int ENCLOSING_CIRCLE = 0x20DD;

    private Emoji() {
    }

    static void init(Context context) {
        try {
            EmojiCompat.get();
        } catch (IllegalStateException notYet) {
            EmojiCompat.init(new BundledEmojiCompatConfig(context.getApplicationContext()));
        }
    }

    static void setText(TextView view, String text) {
        EmojiCompat emoji = EmojiCompat.get();
        view.setTag(text);
        view.setText(styled(emoji, text));
        if (emoji.getLoadState() == EmojiCompat.LOAD_STATE_LOADING) {
            emoji.registerInitCallback(new EmojiCompat.InitCallback() {
                @Override
                public void onInitialized() {
                    if (text.equals(view.getTag())) view.setText(styled(emoji, text));
                }
            });
        }
    }

    private static CharSequence styled(EmojiCompat emoji, String text) {
        // Take the circle marks out, remembering which character each one enclosed.
        StringBuilder plain = new StringBuilder(text.length());
        List<int[]> circled = new ArrayList<>();
        int lastStart = -1;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == ENCLOSING_CIRCLE) {
                if (lastStart >= 0) circled.add(new int[]{lastStart, plain.length()});
                continue;
            }
            lastStart = plain.length();
            plain.appendCodePoint(cp);
        }
        CharSequence base = emoji.getLoadState() == EmojiCompat.LOAD_STATE_SUCCEEDED
                ? emoji.process(plain) : plain;
        if (circled.isEmpty()) return base;
        SpannableStringBuilder out = new SpannableStringBuilder(base);
        for (int[] r : circled) out.setSpan(new CircleSpan(), r[0], r[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }

    /** The enclosed character, smaller, centred in a thin circle about the height of the capitals. */
    static final class CircleSpan extends ReplacementSpan {
        private static final float INNER_SCALE = 0.62f;

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            return Math.round(diameter(paint) + paint.getTextSize() * 0.16f);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
                         int bottom, Paint paint) {
            float d = diameter(paint);
            float cx = x + paint.getTextSize() * 0.08f + d / 2f;
            float cy = y - capHeight(paint) / 2f;
            Paint.Style style = paint.getStyle();
            float stroke = paint.getStrokeWidth();
            float size = paint.getTextSize();
            Paint.Align align = paint.getTextAlign();

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * 0.065f);
            canvas.drawCircle(cx, cy, d / 2f, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(size * INNER_SCALE);
            paint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics m = paint.getFontMetrics();
            canvas.drawText(text, start, end, cx, cy - (m.ascent + m.descent) / 2f, paint);

            paint.setTextAlign(align);
            paint.setTextSize(size);
            paint.setStrokeWidth(stroke);
            paint.setStyle(style);
        }

        private static float diameter(Paint paint) {
            return capHeight(paint) * 1.35f;
        }

        private static float capHeight(Paint paint) {
            return paint.getTextSize() * 0.72f;
        }
    }
}
