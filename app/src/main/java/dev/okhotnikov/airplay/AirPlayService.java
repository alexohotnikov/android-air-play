package dev.okhotnikov.airplay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import java.io.File;

/**
 * Keeps the AirPlay receiver up: the native server with its mDNS responder, a few MB and two idle threads. When a
 * sender connects and no screen is showing, it opens MainActivity, which closes again when the sender leaves.
 */
public final class AirPlayService extends Service implements AirPlayBridge.Listener {

    private static final String TAG = "AirPlayTV";
    private static final String ACTION_RESTART = "dev.okhotnikov.airplay.RESTART";
    private static final String CHANNEL = "receiver";

    // Main thread only.
    private static AirPlayBridge.Listener screen;
    private static boolean running;
    static boolean videoOn;
    static int videoWidth, videoHeight;
    static int startError;
    private static String pendingUrl;
    static float pendingStart;

    private WifiManager.MulticastLock multicast;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean connected;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, AirPlayService.class));
    }

    /** Picks up a new name. */
    static void restart(Context c) {
        if (running) c.startForegroundService(new Intent(c, AirPlayService.class).setAction(ACTION_RESTART));
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, AirPlayService.class));
    }

    static void attach(AirPlayBridge.Listener l) {
        screen = l;
    }

    static void detach(AirPlayBridge.Listener l) {
        if (screen == l) screen = null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.app_name),
                NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_airplay)
                .setContentTitle(getString(R.string.notification, Prefs.name(this)))
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(1, n);
        }
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        // Without it the Wi-Fi driver drops the multicast queries iPhones send to find AirPlay receivers.
        multicast = wifi.createMulticastLock("airplay-mdns");
        multicast.setReferenceCounted(false);
        multicast.acquire();
        AirPlayBridge.setListener(this);
        startServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RESTART.equals(intent.getAction())) startServer();
        return START_STICKY;
    }

    private void startServer() {
        String name = Prefs.name(this);
        startError = AirPlayBridge.nativeStart(name, Prefs.hostName(name), Prefs.hwAddr(this),
                new File(getFilesDir(), "airplay.pem").getAbsolutePath(), 1920, 1080);
        if (startError != 0) Log.e(TAG, "server start failed: " + startError);
        videoOn = false;
    }

    @Override
    public void onDestroy() {
        AirPlayBridge.nativeStop();
        AirPlayBridge.setListener(null);
        main.removeCallbacks(reassert);
        if (multicast != null) multicast.release();
        running = false;
        videoOn = false;
        super.onDestroy();
    }

    @Override
    public void onAirPlayEvent(int event) {
        if (event == AirPlayBridge.VIDEO_ON) videoOn = true;
        if (event == AirPlayBridge.VIDEO_OFF || event == AirPlayBridge.DISCONNECTED) {
            videoOn = false;
            // The next stream reports its own size; a stale one would squeeze its picture.
            videoWidth = videoHeight = 0;
        }
        if (event == AirPlayBridge.CONNECTED) {
            connected = true;
            onConnected();
        }
        if (event == AirPlayBridge.DISCONNECTED) {
            connected = false;
            pendingUrl = null;
            main.removeCallbacks(reassert);
        }
        if (event == AirPlayBridge.URL_END) pendingUrl = null;
        if (screen != null) screen.onAirPlayEvent(event);
    }

    private void onConnected() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm.isInteractive()) {
            if (screen == null) openScreen();
            return;
        }
        // Philips standby only turns the panel off: Android, Wi-Fi and this server keep running, so the TV is
        // still found in Screen Mirroring and can be woken here.
        if (!Prefs.wakeOnConnect(this)) return;
        Log.i(TAG, "sender connected in standby: waking the TV");
        @SuppressWarnings("deprecation")
        PowerManager.WakeLock wake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "airplay:wake");
        wake.acquire(5000);
        openScreen();
        // The firmware opens Live TV at every power-on, possibly over this screen: bring it back once that is done.
        main.postDelayed(reassert, 3000);
        main.postDelayed(reassert, 7000);
    }

    private final Runnable reassert = () -> {
        if (connected && !MainActivity.resumed) openScreen();
    };

    @Override
    public void onVideoSize(int width, int height) {
        videoWidth = width;
        videoHeight = height;
        if (screen != null) screen.onVideoSize(width, height);
    }

    @Override
    public void onUrlPlay(String url, float startSeconds) {
        AirPlayBridge.Playback.reset();
        if (screen != null) {
            screen.onUrlPlay(url, startSeconds);
            return;
        }
        // No screen yet (a player's AirPlay button, without mirroring first): it plays this when it opens.
        pendingUrl = url;
        pendingStart = startSeconds;
        openScreen();
    }

    @Override
    public void onUrlCommand(int command, float value) {
        if (command == AirPlayBridge.CMD_STOP) pendingUrl = null;
        if (screen != null) screen.onUrlCommand(command, value);
    }

    /** The URL that arrived before the screen; null once taken. */
    static String takePendingUrl() {
        String url = pendingUrl;
        pendingUrl = null;
        return url;
    }

    // Android lets a background app open an activity only with "Display over other apps"; on the TV it is granted
    // once over ADB (appops), since TV Settings has no screen for it.
    private void openScreen() {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "connected, but cannot open the screen: no SYSTEM_ALERT_WINDOW");
            return;
        }
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_AUTO, true));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
