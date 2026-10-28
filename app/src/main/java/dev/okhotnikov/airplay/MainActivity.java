package dev.okhotnikov.airplay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The screen of the receiver: the mirrored picture, a video played by URL, or the idle screen. The server itself
 * lives in AirPlayService; opened by the service on connect (EXTRA_AUTO), this screen closes again when the
 * sender leaves.
 */
public final class MainActivity extends Activity implements AirPlayBridge.Listener, SurfaceHolder.Callback,
        UrlPlayer.Callback {

    static final String EXTRA_AUTO = "auto";
    /** In front right now; the service checks it after waking the TV. Main thread only. */
    static boolean resumed;
    // Mirroring stopping can be the first half of a switch to a URL video: wait before closing the screen.
    private static final long CLOSE_DELAY_MS = 2500;
    // A player's next video follows its "stop" by about 3 s: keep the screen for it instead of flashing Home.
    private static final long NEXT_VIDEO_WAIT_MS = 6000;

    private SurfaceView surface;
    private UrlPlayer urlPlayer;
    private NowPlayingView nowPlaying;
    private View idle;
    private AuroraView aurora;
    private TextView title;
    private TextView status;
    private boolean auto;
    private final Runnable closeIfIdle = () -> {
        if (!urlPlayer.active() && !AirPlayService.videoOn) closeOrIdle();
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        auto = getIntent().getBooleanExtra(EXTRA_AUTO, false);
        if (auto && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // Opened for a sender: show even if the TV was in standby (the service decides whether to wake it).
            setTurnScreenOn(true);
            setShowWhenLocked(true);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        urlPlayer = new UrlPlayer(this, root, 1, this);
        nowPlaying = new NowPlayingView(this);
        nowPlaying.setVisibility(View.GONE);
        root.addView(nowPlaying, new FrameLayout.LayoutParams(-1, -1));
        idle = buildIdle();
        root.addView(idle, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        auto = auto && intent.getBooleanExtra(EXTRA_AUTO, false);
    }

    private View buildIdle() {
        float dp = getResources().getDisplayMetrics().density;
        FrameLayout box = new FrameLayout(this);
        aurora = new AuroraView(this);
        box.addView(aurora, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_airplay);
        icon.setAlpha(0.92f);
        center.addView(icon, new LinearLayout.LayoutParams((int) (84 * dp), (int) (84 * dp)));
        title = text("", 40, 0xFFFFFFFF, "sans-serif-light");
        title.setPadding(0, (int) (22 * dp), 0, 0);
        center.addView(title);
        status = text("", 19, 0xB3FFFFFF, "sans-serif");
        status.setPadding(0, (int) (8 * dp), 0, 0);
        center.addView(status);
        box.addView(center, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        TextView hint = text(getString(R.string.hint), 15, 0x80FFFFFF, "sans-serif");
        hint.setLetterSpacing(0.04f);
        hint.setLineSpacing(6f * dp, 1f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        lp.bottomMargin = (int) (44 * dp);
        box.addView(hint, lp);
        return box;
    }

    private TextView text(String s, float sp, int color, String family) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(Typeface.create(family, Typeface.NORMAL));
        v.setGravity(Gravity.CENTER);
        v.setShadowLayer(12f, 0f, 2f, 0x40000000);
        return v;
    }

    @Override
    protected void onStart() {
        super.onStart();
        AirPlayService.start(this);
        AirPlayService.attach(this);
        title.setText(Prefs.name(this));
        // The size can arrive while the service is still opening this screen: take it from the service.
        applyKnownSize();
        if (AirPlayService.videoOn) setIdleVisible(false);
        else showIdle();
        showNowPlayingIfMusic();
        String url = AirPlayService.takePendingUrl();
        if (url != null) onUrlPlay(url, AirPlayService.pendingStart);
    }

    private void applyKnownSize() {
        if (AirPlayService.videoWidth > 0) onVideoSize(AirPlayService.videoWidth, AirPlayService.videoHeight);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onStop() {
        idle.removeCallbacks(closeIfIdle);
        // Leaving the screen ends a URL video: the sender sees it finish and takes the video back.
        urlPlayer.release(urlPlayer.active());
        AirPlayService.detach(this);
        aurora.stop();
        nowPlaying.stop();
        if (!Prefs.background(this)) AirPlayService.stop(this);
        super.onStop();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
        if (urlPlayer.active() && (ok || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            urlPlayer.togglePause();
            return true;
        }
        if ((ok || keyCode == KeyEvent.KEYCODE_MENU) && idle.getVisibility() == View.VISIBLE) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onAirPlayEvent(int event) {
        switch (event) {
            case AirPlayBridge.VIDEO_ON:
                idle.removeCallbacks(closeIfIdle);
                hideNowPlaying();
                urlPlayer.release(false);
                applyKnownSize();
                setIdleVisible(false);
                break;
            case AirPlayBridge.CONNECTED:
                status.setText(R.string.connecting);
                break;
            case AirPlayBridge.AUDIO_ONLY:
                if (!urlPlayer.active() && !AirPlayService.videoOn) status.setText(R.string.audio_only);
                showNowPlayingIfMusic();
                break;
            case AirPlayBridge.NOW_PLAYING:
            case AirPlayBridge.AUDIO_PAUSED:
            case AirPlayBridge.AUDIO_PLAYING:
            case AirPlayBridge.AUDIO_STOPPED:
                if (nowPlaying.getVisibility() == View.VISIBLE) nowPlaying.update();
                else showNowPlayingIfMusic();
                break;
            case AirPlayBridge.MIRROR_TO_URL:
                idle.removeCallbacks(closeIfIdle);
                hideNowPlaying();
                status.setText(R.string.loading_video);
                setIdleVisible(true);
                break;
            case AirPlayBridge.VIDEO_OFF:
                if (urlPlayer.active()) break;
                showIdle();
                if (auto) idle.postDelayed(closeIfIdle, CLOSE_DELAY_MS);
                break;
            case AirPlayBridge.URL_END:
                urlPlayer.release(false);
                // Keep a failure on screen for a moment instead of vanishing as if the app had crashed.
                if (status.getText().toString().equals(getString(R.string.url_failed))) {
                    idle.postDelayed(closeIfIdle, CLOSE_DELAY_MS * 2);
                } else {
                    closeOrIdle();
                }
                break;
            case AirPlayBridge.DISCONNECTED:
                hideNowPlaying();
                urlPlayer.release(false);
                closeOrIdle();
                break;
            default:
                break;
        }
    }

    /** Music-only AirPlay with something to show: the Now Playing screen replaces the idle one. */
    private void showNowPlayingIfMusic() {
        if (!AirPlayService.audioOnly || !NowPlaying.hasContent() || urlPlayer.active() || AirPlayService.videoOn) {
            return;
        }
        idle.removeCallbacks(closeIfIdle);
        if (nowPlaying.getVisibility() != View.VISIBLE) {
            nowPlaying.setAlpha(0f);
            nowPlaying.setVisibility(View.VISIBLE);
            nowPlaying.animate().alpha(1f).setDuration(500).start();
        }
        nowPlaying.start();
        setIdleVisible(false);
    }

    private void hideNowPlaying() {
        if (nowPlaying.getVisibility() != View.VISIBLE) return;
        nowPlaying.stop();
        nowPlaying.setVisibility(View.GONE);
    }

    /** Opened for this session only: hand the TV back to whatever was on before. */
    private void closeOrIdle() {
        if (auto) finish();
        else showIdle();
    }

    @Override
    public void onUrlPlay(String url, float startSeconds) {
        idle.removeCallbacks(closeIfIdle);
        hideNowPlaying();
        status.setText(R.string.loading_video);
        setIdleVisible(true);
        urlPlayer.play(url, startSeconds);
    }

    @Override
    public void onUrlCommand(int command, float value) {
        if (command == AirPlayBridge.CMD_STOP) {
            urlPlayer.release(true);
            showIdle();
            if (auto) idle.postDelayed(closeIfIdle, NEXT_VIDEO_WAIT_MS);
        } else {
            urlPlayer.command(command, value);
        }
    }

    @Override
    public void onUrlPlaying() {
        setIdleVisible(false);
    }

    @Override
    public void onUrlFinished(boolean failed) {
        // The sender stops or sends the next video after its next poll; the idle screen covers the gap.
        showIdle();
        if (failed) status.setText(R.string.url_failed);
    }

    @Override
    public void onVideoSize(int width, int height) {
        fit(surface, width, height);
    }

    /** Letterbox: the picture keeps the sender's proportions (a portrait iPhone gets black bars at the sides). */
    static void fit(View view, int width, int height) {
        View root = (View) view.getParent();
        int rw = root.getWidth(), rh = root.getHeight();
        if (rw == 0 || rh == 0) {
            root.post(() -> fit(view, width, height));
            return;
        }
        int w = rw, h = rh;
        if ((long) width * rh > (long) height * rw) h = (int) ((long) rw * height / width);
        else w = (int) ((long) rh * width / height);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (lp.width == w && lp.height == h) return;
        lp.width = w;
        lp.height = h;
        view.setLayoutParams(lp);
    }

    private void showIdle() {
        int err = AirPlayService.startError;
        status.setText(err != 0 ? getString(R.string.start_failed, err) : getString(R.string.ready));
        setIdleVisible(true);
    }

    /** The idle screen fades over the video; its animation stops while the video plays. */
    private void setIdleVisible(boolean visible) {
        if (visible) {
            aurora.start();
            if (idle.getVisibility() != View.VISIBLE) {
                idle.setAlpha(0f);
                idle.setVisibility(View.VISIBLE);
            }
            idle.animate().alpha(1f).setDuration(500).withEndAction(null).start();
        } else {
            idle.animate().alpha(0f).setDuration(350).withEndAction(() -> {
                idle.setVisibility(View.GONE);
                aurora.stop();
            }).start();
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        AirPlayBridge.nativeSetSurface(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        AirPlayBridge.nativeSetSurface(null);
    }
}
