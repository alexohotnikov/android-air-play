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

    interface Listener {
        void onAirPlayEvent(int event);

        void onVideoSize(int width, int height);
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

    /** 0 on success; negative when the mDNS responder or the server could not start. */
    static native int nativeStart(String name, String host, byte[] hwAddr, String keyFile, int width, int height);

    static native void nativeStop();

    static native void nativeSetSurface(Surface surface);
}
