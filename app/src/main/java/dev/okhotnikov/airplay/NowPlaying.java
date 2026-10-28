package dev.okhotnikov.airplay;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;

import java.nio.charset.StandardCharsets;

/**
 * What music-only AirPlay is playing: DMAP metadata, artwork and progress, as the sender reports them. The
 * sender sends progress only at a track change and after a seek, so the position runs on our clock in between
 * and stops on a pause (a FLUSH). Main thread only.
 */
final class NowPlaying {

    private static final int RTP_RATE = 44100;
    private static final int MAX_COVER_PX = 800;

    static String title = "";
    static String artist = "";
    static String album = "";
    static Bitmap cover;
    private static double duration;
    private static double basePosition;
    private static long baseAt;
    private static boolean playing = true;
    private static boolean hasProgress;

    private NowPlaying() {
    }

    static void reset() {
        title = artist = album = "";
        cover = null;
        duration = basePosition = 0;
        hasProgress = false;
        playing = true;
    }

    static boolean hasContent() {
        return !title.isEmpty() || cover != null;
    }

    /** A DMAP listing item ("mlit"): minm title, asar artist, asal album. */
    static void setMetadata(byte[] dmap) {
        if (dmap.length < 8 || !tag(dmap, 0).equals("mlit")) return;
        String t = "", ar = "", al = "";
        int pos = 8;
        while (pos + 8 <= dmap.length) {
            String tag = tag(dmap, pos);
            int len = ((dmap[pos + 4] & 0xff) << 24) | ((dmap[pos + 5] & 0xff) << 16)
                    | ((dmap[pos + 6] & 0xff) << 8) | (dmap[pos + 7] & 0xff);
            pos += 8;
            if (len < 0 || pos + len > dmap.length) break;
            String value = new String(dmap, pos, len, StandardCharsets.UTF_8);
            if (tag.equals("minm")) t = value;
            else if (tag.equals("asar")) ar = value;
            else if (tag.equals("asal")) al = value;
            pos += len;
        }
        title = t.trim();
        artist = ar.trim();
        album = al.trim();
    }

    private static String tag(byte[] b, int at) {
        return new String(b, at, 4, StandardCharsets.US_ASCII);
    }

    /** Decoded on the caller's thread (the server's), so a large JPEG never holds up the UI. */
    static Bitmap decodeCover(byte[] jpeg) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_COVER_PX) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
    }

    /** RTP timestamps, unsigned 32-bit: they wrap, so differences are taken modulo 2^32. */
    static void setProgress(long start, long curr, long end) {
        duration = ((end - start) & 0xFFFFFFFFL) / (double) RTP_RATE;
        basePosition = ((curr - start) & 0xFFFFFFFFL) / (double) RTP_RATE;
        baseAt = SystemClock.elapsedRealtime();
        hasProgress = duration > 0;
    }

    static void pause() {
        basePosition = position();
        baseAt = SystemClock.elapsedRealtime();
        playing = false;
    }

    static void resume() {
        baseAt = SystemClock.elapsedRealtime();
        playing = true;
    }

    static boolean isPlaying() {
        return playing;
    }

    static boolean hasProgress() {
        return hasProgress;
    }

    static double duration() {
        return duration;
    }

    static double position() {
        double p = basePosition;
        if (playing) p += (SystemClock.elapsedRealtime() - baseAt) / 1000.0;
        return Math.max(0, Math.min(p, duration));
    }
}
