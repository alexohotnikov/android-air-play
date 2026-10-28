package dev.okhotnikov.airplay;

import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

/** The native AirPlay server (UxPlay core, lib airplaytv). Events arrive on the main thread. */
final class AirPlayBridge {

    static final int CONNECTED = 1;
    static final int DISCONNECTED = 2;
    static final int VIDEO_ON = 3;
    static final int VIDEO_OFF = 4;
    /** Music-only AirPlay: sound, no picture. */
    static final int AUDIO_ONLY = 5;
    /** Mirroring stops because a video URL is about to play: the screen stays. */
    static final int MIRROR_TO_URL = 6;
    /** The URL video is over. */
    static final int URL_END = 7;
    /** Music: paused, or seeking (a FLUSH). */
    static final int AUDIO_PAUSED = 8;
    /** Music: sound again after a FLUSH. */
    static final int AUDIO_PLAYING = 9;
    /** Music: the audio stream was torn down, which is how iOS pauses; the track and artwork stay. */
    static final int AUDIO_STOPPED = 10;
    /** Music: {@link NowPlaying} changed (title, artwork or progress). Raised on the Java side. */
    static final int NOW_PLAYING = 11;

    static final int CMD_SCRUB = 1;
    static final int CMD_RATE = 2;
    static final int CMD_STOP = 3;

    interface Listener {
        void onAirPlayEvent(int event);

        void onVideoSize(int width, int height);

        /** A sender's "play this URL": an HLS stream through UxPlay's local proxy, or a plain http(s) URL. */
        void onUrlPlay(String url, float startSeconds);

        /** CMD_SCRUB (seconds), CMD_RATE (0 pause, 1 play), CMD_STOP. */
        void onUrlCommand(int command, float value);
    }

    /**
     * The URL player's state as the sender sees it, written by the player on the main thread and read by the
     * server's threads when the sender polls (about once a second), so the poll never waits on the UI.
     */
    static final class Playback {
        static volatile double duration;
        static volatile double position;
        static volatile double rate;
        static volatile boolean finished;

        static void reset() {
            duration = position = rate = 0;
            finished = false;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Listener listener;

    static {
        System.loadLibrary("airplaytv");
    }

    private AirPlayBridge() {
    }

    static void setListener(Listener l) {
        listener = l;
    }

    /** Called from the server's threads. */
    @SuppressWarnings("unused")
    private static void onNativeEvent(int event) {
        MAIN.post(() -> {
            Listener l = listener;
            if (l != null) l.onAirPlayEvent(event);
        });
    }

    @SuppressWarnings("unused")
    private static void onNativeVideoSize(int width, int height) {
        MAIN.post(() -> {
            Listener l = listener;
            if (l != null) l.onVideoSize(width, height);
        });
    }

    @SuppressWarnings("unused")
    private static void onNativeUrlPlay(String url, float start) {
        MAIN.post(() -> {
            Listener l = listener;
            if (l != null) l.onUrlPlay(url, start);
        });
    }

    @SuppressWarnings("unused")
    private static void onNativeUrlCommand(int command, float value) {
        MAIN.post(() -> {
            Listener l = listener;
            if (l != null) l.onUrlCommand(command, value);
        });
    }

    @SuppressWarnings("unused")
    private static void onNativeMetadata(byte[] dmap) {
        MAIN.post(() -> {
            NowPlaying.setMetadata(dmap);
            dispatch(NOW_PLAYING);
        });
    }

    @SuppressWarnings("unused")
    private static void onNativeCoverArt(byte[] jpeg) {
        android.graphics.Bitmap cover = NowPlaying.decodeCover(jpeg);
        MAIN.post(() -> {
            NowPlaying.cover = cover;
            dispatch(NOW_PLAYING);
        });
    }

    @SuppressWarnings("unused")
    private static void onNativeProgress(long start, long curr, long end) {
        MAIN.post(() -> {
            NowPlaying.setProgress(start, curr, end);
            dispatch(NOW_PLAYING);
        });
    }

    private static void dispatch(int event) {
        Listener l = listener;
        if (l != null) l.onAirPlayEvent(event);
    }

    @SuppressWarnings("unused")
    private static double[] onNativePlaybackInfo() {
        return new double[]{Playback.duration, Playback.position, Playback.rate, Playback.finished ? 1 : 0};
    }

    @SuppressWarnings("unused")
    private static float onNativePlaylistRemove() {
        MAIN.post(() -> {
            Listener l = listener;
            if (l != null) l.onUrlCommand(CMD_RATE, 0f);
        });
        return (float) Playback.position;
    }

    /** 0 on success; negative when the mDNS responder or the server could not start. */
    static native int nativeStart(String name, String host, byte[] hwAddr, String keyFile, int width, int height);

    static native void nativeStop();

    static native void nativeSetSurface(Surface surface);
}
