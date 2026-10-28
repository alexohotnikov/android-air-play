package dev.okhotnikov.airplay;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

/**
 * Plays a sender's "play this URL" (HLS, including fMP4 segments, and progressive http/https) with ExoPlayer,
 * on its own SurfaceView over the mirroring one, and keeps {@link AirPlayBridge.Playback} current for the
 * sender's polls.
 */
final class UrlPlayer {

    private static final String TAG = "AirPlayTV";
    private static final long POLL_MS = 500;
    // The sender's own player is AVPlayer; some CDNs (VK Video's okcdn) serve the playlist only to it.
    private static final String APPLE_UA = "AppleCoreMedia/1.0.0.22A3354 (iPhone; U; CPU OS 18_0 like Mac OS X; ru_ru)";

    interface Callback {
        void onUrlPlaying();

        /** failed: the URL could not be played at all. */
        void onUrlFinished(boolean failed);
    }

    private final SurfaceView view;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private boolean started;
    private long openedAt;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            publish();
            main.postDelayed(this, POLL_MS);
        }
    };

    UrlPlayer(Context context, FrameLayout parent, int index, Callback callback) {
        this.callback = callback;
        view = new SurfaceView(context);
        view.setVisibility(View.GONE);
        parent.addView(view, index, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
    }

    boolean active() {
        return player != null;
    }

    void play(String url, float startSeconds) {
        release(false);
        Log.i(TAG, "opening " + describe(url));
        view.setVisibility(View.VISIBLE);
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(APPLE_UA)
                .setAllowCrossProtocolRedirects(true);
        // Start after 1 s of buffered video instead of ExoPlayer's 2.5 s: the sender is waiting on the TV.
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 50000, 1000, 2000)
                .build();
        ExoPlayer p = new ExoPlayer.Builder(view.getContext())
                .setMediaSourceFactory(new DefaultMediaSourceFactory(view.getContext()).setDataSourceFactory(http))
                .setLoadControl(load)
                .build();
        openedAt = SystemClock.elapsedRealtime();
        player = p;
        started = false;
        p.setVideoSurfaceView(view);
        p.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && !started) {
                    started = true;
                    Log.i(TAG, "URL video ready in " + (SystemClock.elapsedRealtime() - openedAt) + " ms");
                    main.postDelayed(poll, POLL_MS);
                    callback.onUrlPlaying();
                } else if (state == Player.STATE_ENDED) {
                    finish(false);
                }
                publish();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                publish();
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
                if (size.width > 0 && size.height > 0) {
                    MainActivity.fit(view, Math.round(size.width * size.pixelWidthHeightRatio), size.height);
                }
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                Log.e(TAG, "URL playback error: " + e.getErrorCodeName(), e);
                finish(true);
            }
        });
        p.setMediaItem(MediaItem.fromUri(Uri.parse(url)), (long) (startSeconds * 1000));
        p.setPlayWhenReady(true);
        p.prepare();
    }

    /** The video ended or failed: the sender learns it at its next poll and moves on or stops. */
    private void finish(boolean failed) {
        publish();
        AirPlayBridge.Playback.finished = true;
        release(false);
        callback.onUrlFinished(failed);
    }

    void command(int command, float value) {
        if (player == null) return;
        if (command == AirPlayBridge.CMD_SCRUB) player.seekTo((long) (value * 1000));
        else if (command == AirPlayBridge.CMD_RATE) player.setPlayWhenReady(value != 0f);
        publish();
    }

    /** Play or pause from the remote. */
    void togglePause() {
        if (player != null) command(AirPlayBridge.CMD_RATE, player.getPlayWhenReady() ? 0f : 1f);
    }

    /** markFinished: the screen is going away, so tell the sender the video is over. */
    void release(boolean markFinished) {
        main.removeCallbacks(poll);
        if (player != null) {
            player.release();
            player = null;
        }
        view.setVisibility(View.GONE);
        if (markFinished) AirPlayBridge.Playback.finished = true;
    }

    private void publish() {
        if (player == null) return;
        long duration = player.getDuration();
        if (duration != C.TIME_UNSET) AirPlayBridge.Playback.duration = duration / 1000.0;
        AirPlayBridge.Playback.position = player.getCurrentPosition() / 1000.0;
        AirPlayBridge.Playback.rate = player.isPlaying() ? 1 : 0;
    }

    /** Scheme, host, port and file type only: the query often carries a token. */
    static String describe(String url) {
        Uri u = Uri.parse(url);
        String path = u.getPath() == null ? "" : u.getPath();
        int slash = path.lastIndexOf('/');
        return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + "/…"
                + (slash >= 0 ? path.substring(slash) : path) + (u.getQuery() != null ? " (+query)" : "");
    }
}
