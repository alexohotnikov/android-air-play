// JNI bridge: runs UxPlay's AirPlay core (RAOP/AirPlay server plus its own mDNS responder) and hands the streams
// to renderer.c. Java only supplies the Surface, the device name and identity, and shows the connection state.
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "dnssd.h"
#include "raop.h"
#include "stream.h"
#include "renderer.h"

#define TAG "AirPlayTV"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

enum {
    EV_CONNECTED = 1, EV_DISCONNECTED = 2, EV_VIDEO_ON = 3, EV_VIDEO_OFF = 4,
    EV_AUDIO_ONLY = 5,     // music-only AirPlay: sound, no picture
    EV_MIRROR_TO_URL = 6,  // mirroring stops because a video URL is about to play: keep the screen
    EV_URL_END = 7,        // the URL video is over
    EV_AUDIO_PAUSED = 8,   // music: a FLUSH (pause or seek)
    EV_AUDIO_PLAYING = 9,  // music: the first sound after a FLUSH
    EV_AUDIO_STOPPED = 10, // music: the audio stream torn down, which is how iOS pauses (TEARDOWN type 96)
};
enum { CMD_SCRUB = 1, CMD_RATE = 2, CMD_STOP = 3 };

static JavaVM *jvm;
static jclass bridge_class;
static jmethodID on_event;
static jmethodID on_video_size;
static jmethodID on_url_play, on_url_command, on_playback_info, on_playlist_remove;
static jmethodID on_metadata, on_coverart, on_progress;
static volatile bool audio_flushed;
static volatile bool audio_seen;  // the first audio packet of a connection is logged
static raop_t *raop;
static dnssd_t *dnssd;
static char host_name[64] = "AirPlay-TV";
static pthread_mutex_t server_lock = PTHREAD_MUTEX_INITIALIZER;

// lib/mdnsd builds its A record from gethostname(), which is "localhost" on Android: give it the TV's name.
int airplaytv_gethostname(char *name, size_t len) {
    strncpy(name, host_name, len);
    if (len) name[len - 1] = '\0';
    return 0;
}

static void post_event(int event) {
    JNIEnv *env;
    bool attached = false;
    if ((*jvm)->GetEnv(jvm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*jvm)->AttachCurrentThread(jvm, &env, NULL) != JNI_OK) return;
        attached = true;
    }
    (*env)->CallStaticVoidMethod(env, bridge_class, on_event, event);
    if (attached) (*jvm)->DetachCurrentThread(jvm);
}

static JNIEnv *jni_env(bool *attached) {
    JNIEnv *env;
    *attached = false;
    if ((*jvm)->GetEnv(jvm, (void **) &env, JNI_VERSION_1_6) == JNI_OK) return env;
    if ((*jvm)->AttachCurrentThread(jvm, &env, NULL) != JNI_OK) return NULL;
    *attached = true;
    return env;
}

static void post_url_command(int cmd, float value) {
    bool attached;
    JNIEnv *env = jni_env(&attached);
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, bridge_class, on_url_command, cmd, value);
    if (attached) (*jvm)->DetachCurrentThread(jvm);
}

static void post_video_size(int width, int height) {
    JNIEnv *env;
    bool attached = false;
    if ((*jvm)->GetEnv(jvm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*jvm)->AttachCurrentThread(jvm, &env, NULL) != JNI_OK) return;
        attached = true;
    }
    (*env)->CallStaticVoidMethod(env, bridge_class, on_video_size, width, height);
    if (attached) (*jvm)->DetachCurrentThread(jvm);
}

/* ---- RAOP callbacks ---- */

static void cb_conn_init(void *cls) {
    audio_seen = false;
    post_event(EV_CONNECTED);
}
static void cb_conn_destroy(void *cls) {
    video_stop();
    audio_stop();
    post_event(EV_DISCONNECTED);
}
static void cb_conn_reset(void *cls, int reason) {
    LOGI("connection reset, reason %d", reason);
    video_stop();
    audio_stop();
    post_event(EV_DISCONNECTED);
}
static void cb_conn_feedback(void *cls) {}

static void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *d) {
    video_render(d->data, d->data_len, d->is_h265);
}
static void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *d) {
    if (!audio_seen) {
        audio_seen = true;
        LOGI("first audio packet: ct=%d, %d bytes", d->ct, d->data_len);
    }
    if (audio_flushed) {
        audio_flushed = false;
        post_event(EV_AUDIO_PLAYING);
    }
    audio_render(d->ct, d->data, d->data_len);
}
static void cb_video_pause(void *cls) {}
static void cb_video_resume(void *cls) {}
static void cb_video_flush(void *cls) {}
static void cb_audio_flush(void *cls) {
    audio_flushed = true;
    post_event(EV_AUDIO_PAUSED);
}
static void cb_video_reset(void *cls, reset_type_t type) {
    LOGI("video reset, type %d", type);
    switch (type) {
        case RESET_TYPE_ON_VIDEO_PLAY:
            return;
        case RESET_TYPE_RTP_TO_HLS_TEARDOWN:
            video_stop();
            post_event(EV_MIRROR_TO_URL);
            return;
        case RESET_TYPE_RTP_SHUTDOWN:
            video_stop();
            post_event(EV_VIDEO_OFF);
            return;
        case RESET_TYPE_HLS_SHUTDOWN:
            // As uxplay.cpp does: the app, not the library, drops the finished video and its proxy connections.
            raop_destroy_airplay_video(raop, -1);
            raop_remove_hls_connections(raop);
            post_event(EV_URL_END);
            return;
        case RESET_TYPE_NOHOLD:
            raop_destroy_airplay_video(raop, -1);
            video_stop();
            post_event(EV_URL_END);
            return;
        case RESET_TYPE_HLS_EOS:
            post_event(EV_URL_END);
            return;
    }
}
static int cb_video_set_codec_and_size(void *cls, video_codec_t codec, unsigned short ws, unsigned short hs,
                                       unsigned short x, unsigned short y, unsigned short w, unsigned short h) {
    LOGI("video %ux%u (source %ux%u)", w, h, ws, hs);
    video_set_size(w, h);
    post_event(EV_VIDEO_ON);
    return 0;
}
static void cb_mirror_video_running(void *cls, bool running) { post_event(running ? EV_VIDEO_ON : EV_VIDEO_OFF); }

static double cb_audio_set_client_volume(void *cls) { return 0.0; }
static void cb_audio_set_volume(void *cls, float volume) { audio_set_volume_db(volume); }
static void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *screen, bool *media,
                                uint64_t *fmt) {
    LOGI("audio format ct=%d spf=%d screen=%d", *ct, *spf, *screen);
    if (!*screen) post_event(EV_AUDIO_ONLY);
}
/** Hands a buffer to Java unparsed: DMAP metadata and JPEG artwork are read there. */
static void post_bytes(jmethodID method, const void *b, int n) {
    if (!b || n <= 0) return;
    bool attached;
    JNIEnv *env = jni_env(&attached);
    if (!env) return;
    jbyteArray a = (*env)->NewByteArray(env, n);
    if (a) {
        (*env)->SetByteArrayRegion(env, a, 0, n, (const jbyte *) b);
        (*env)->CallStaticVoidMethod(env, bridge_class, method, a);
        (*env)->DeleteLocalRef(env, a);
    }
    if (attached) (*jvm)->DetachCurrentThread(jvm);
}

static void cb_audio_set_metadata(void *cls, const void *b, int n) { post_bytes(on_metadata, b, n); }
static void cb_audio_set_coverart(void *cls, const void *b, int n) { post_bytes(on_coverart, b, n); }
// UxPlay calls this when the sender tears down the audio stream, which iOS does on pause; resuming sets up a new
// stream, and its first packet reports EV_AUDIO_PLAYING.
static void cb_audio_stop_coverart(void *cls) {
    audio_flushed = true;
    post_event(EV_AUDIO_STOPPED);
}

/** RTP timestamps at 44100 Hz: track start, now, track end. Sent at a track change and after a seek. */
static void cb_audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end) {
    bool attached;
    JNIEnv *env = jni_env(&attached);
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, bridge_class, on_progress, (jlong) *start, (jlong) *curr, (jlong) *end);
    if (attached) (*jvm)->DetachCurrentThread(jvm);
}
static void cb_audio_remote_control_id(void *cls, const char *dacp, const char *active) {}
static void cb_export_dacp(void *cls, const char *active, const char *dacp) {}

// No access control: anyone on the home Wi-Fi may mirror, as with a stock AirPlay TV set to "Anyone on the
// same network".
static void cb_report_client_request(void *cls, char *id, char *model, char *name, bool *admit) {
    LOGI("client %s (%s) asks to connect", name, model);
    *admit = true;
}
static void cb_display_pin(void *cls, char *pin) {}
static void cb_register_client(void *cls, const char *id, const char *pk, const char *name) {}
static bool cb_check_register(void *cls, const char *pk) { return true; }
static const char *cb_passwd(void *cls, int *len) {
    *len = 0;
    return NULL;
}

// "Play this URL": an HLS stream through UxPlay's local proxy (YouTube) or a plain http(s) URL (Safari, many
// players). Java plays it with MediaPlayer; the sender polls the position about once a second.
static void cb_on_video_play(void *cls, const char *location, const float start) {
    LOGI("play URL from %.1f s", start);
    bool attached;
    JNIEnv *env = jni_env(&attached);
    if (!env) return;
    jstring url = (*env)->NewStringUTF(env, location);
    (*env)->CallStaticVoidMethod(env, bridge_class, on_url_play, url, start);
    (*env)->DeleteLocalRef(env, url);
    if (attached) (*jvm)->DetachCurrentThread(jvm);
}
static void cb_on_video_scrub(void *cls, const float pos) { post_url_command(CMD_SCRUB, pos); }
static void cb_on_video_rate(void *cls, const float rate) { post_url_command(CMD_RATE, rate); }
static void cb_on_video_stop(void *cls) { post_url_command(CMD_STOP, 0); }

/** From Java's cached state, without waiting on the main thread: {duration, position, rate, finished}. */
static void cb_on_video_info(void *cls, playback_info_t *info) {
    bool attached;
    JNIEnv *env = jni_env(&attached);
    if (!env) return;
    jdoubleArray a = (jdoubleArray) (*env)->CallStaticObjectMethod(env, bridge_class, on_playback_info);
    double v[4] = {0, 0, 0, 0};
    if (a) {
        (*env)->GetDoubleArrayRegion(env, a, 0, 4, v);
        (*env)->DeleteLocalRef(env, a);
    }
    if (attached) (*jvm)->DetachCurrentThread(jvm);
    info->duration = v[0];
    info->position = v[1];
    info->rate = (float) v[2];
    info->seek_start = 0;
    info->seek_duration = v[0];
    info->ready_to_play = true;
    info->playback_likely_to_keep_up = true;
    info->playback_buffer_empty = false;
    info->playback_buffer_full = true;
    if (v[3] != 0) {  // finished: tells the sender to move on or stop
        info->duration = -1.0;
        info->position = -1.0;
    }
}

static float cb_on_video_playlist_remove(void *cls) {
    LOGI("playlist remove: pausing the URL video");
    bool attached;
    JNIEnv *env = jni_env(&attached);
    if (!env) return 0.0f;
    float pos = (*env)->CallStaticFloatMethod(env, bridge_class, on_playlist_remove);
    if (attached) (*jvm)->DetachCurrentThread(jvm);
    return pos;
}
static void cb_get_custom_profile(void *cls, const char **profile) { *profile = NULL; }

static void log_callback(void *cls, int level, const char *msg) {
    if (level >= LOGGER_DEBUG) {
        // UxPlay's debug level for the requests that drive playback only: the TV keeps ~3000 log lines, and the
        // once-a-second /feedback and /playback-info would push everything else out within minutes.
        if (strstr(msg, "/feedback") || strstr(msg, "/playback-info")) return;
        if (strncmp(msg, "Handling request", 16) && !strstr(msg, "action type") && !strstr(msg, "sync")) return;
    }
    __android_log_print(level <= LOGGER_ERR ? ANDROID_LOG_ERROR : level <= LOGGER_INFO ? ANDROID_LOG_INFO
                                                                                     : ANDROID_LOG_DEBUG,
                        "UxPlay", "%s", msg);
}

static void set_features(dnssd_t *d) {
    // UxPlay's defaults for screen mirroring with audio, plus video by URL (bits 0 and 4: without them iOS treats
    // the TV as a speaker and a player's AirPlay button sends sound only). H.265 (bit 42) off.
    static const int on[] = {0, 1, 2, 4, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 25, 27, 28, 30};
    static const int off[] = {3, 8, 23, 24, 26, 29, 31, 42};
    for (size_t i = 0; i < sizeof(on) / sizeof(on[0]); i++) dnssd_set_airplay_features(d, on[i], 1);
    for (size_t i = 0; i < sizeof(off) / sizeof(off[0]); i++) dnssd_set_airplay_features(d, off[i], 0);
}

static void stop_locked(void) {
    if (raop) {
        raop_destroy(raop);
        raop = NULL;
    }
    if (dnssd) {
        dnssd_unregister_raop(dnssd);
        dnssd_unregister_airplay(dnssd);
        dnssd_destroy(dnssd);
        dnssd = NULL;
    }
    video_stop();
    audio_stop();
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    jvm = vm;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass c = (*env)->FindClass(env, "dev/okhotnikov/airplay/AirPlayBridge");
    bridge_class = (*env)->NewGlobalRef(env, c);
    on_event = (*env)->GetStaticMethodID(env, c, "onNativeEvent", "(I)V");
    on_video_size = (*env)->GetStaticMethodID(env, c, "onNativeVideoSize", "(II)V");
    on_url_play = (*env)->GetStaticMethodID(env, c, "onNativeUrlPlay", "(Ljava/lang/String;F)V");
    on_url_command = (*env)->GetStaticMethodID(env, c, "onNativeUrlCommand", "(IF)V");
    on_playback_info = (*env)->GetStaticMethodID(env, c, "onNativePlaybackInfo", "()[D");
    on_playlist_remove = (*env)->GetStaticMethodID(env, c, "onNativePlaylistRemove", "()F");
    on_metadata = (*env)->GetStaticMethodID(env, c, "onNativeMetadata", "([B)V");
    on_coverart = (*env)->GetStaticMethodID(env, c, "onNativeCoverArt", "([B)V");
    on_progress = (*env)->GetStaticMethodID(env, c, "onNativeProgress", "(JJJ)V");
    video_set_size_listener(post_video_size);
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_dev_okhotnikov_airplay_AirPlayBridge_nativeStart(JNIEnv *env, jclass cls, jstring jname, jstring jhost,
                                                      jbyteArray jhw, jstring jkeyfile, jint width, jint height) {
    pthread_mutex_lock(&server_lock);
    stop_locked();
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    const char *host = (*env)->GetStringUTFChars(env, jhost, NULL);
    const char *keyfile = (*env)->GetStringUTFChars(env, jkeyfile, NULL);
    jsize hw_len = (*env)->GetArrayLength(env, jhw);
    jbyte *hw = (*env)->GetByteArrayElements(env, jhw, NULL);
    strncpy(host_name, host, sizeof(host_name) - 1);

    char mac[18];
    snprintf(mac, sizeof(mac), "%02x:%02x:%02x:%02x:%02x:%02x", (unsigned char) hw[0], (unsigned char) hw[1],
             (unsigned char) hw[2], (unsigned char) hw[3], (unsigned char) hw[4], (unsigned char) hw[5]);

    int err = 0, rc = 0;
    dnssd = dnssd_init(name, (int) strlen(name), (const char *) hw, hw_len, 0, &err);
    if (err || !dnssd) {
        LOGE("dnssd_init failed: %d", err);
        rc = -1;
        goto done;
    }
    set_features(dnssd);

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.conn_init = cb_conn_init;
    cbs.conn_destroy = cb_conn_destroy;
    cbs.conn_reset = cb_conn_reset;
    cbs.conn_feedback = cb_conn_feedback;
    cbs.audio_process = cb_audio_process;
    cbs.video_process = cb_video_process;
    cbs.audio_flush = cb_audio_flush;
    cbs.video_flush = cb_video_flush;
    cbs.video_pause = cb_video_pause;
    cbs.video_resume = cb_video_resume;
    cbs.audio_set_client_volume = cb_audio_set_client_volume;
    cbs.audio_set_volume = cb_audio_set_volume;
    cbs.audio_get_format = cb_audio_get_format;
    cbs.audio_set_metadata = cb_audio_set_metadata;
    cbs.audio_set_coverart = cb_audio_set_coverart;
    cbs.audio_stop_coverart_rendering = cb_audio_stop_coverart;
    cbs.audio_set_progress = cb_audio_set_progress;
    cbs.audio_remote_control_id = cb_audio_remote_control_id;
    cbs.report_client_request = cb_report_client_request;
    cbs.display_pin = cb_display_pin;
    cbs.register_client = cb_register_client;
    cbs.check_register = cb_check_register;
    cbs.passwd = cb_passwd;
    cbs.export_dacp = cb_export_dacp;
    cbs.video_reset = cb_video_reset;
    cbs.video_set_codec_and_size = cb_video_set_codec_and_size;
    cbs.mirror_video_running = cb_mirror_video_running;
    cbs.on_video_play = cb_on_video_play;
    cbs.on_video_scrub = cb_on_video_scrub;
    cbs.on_video_rate = cb_on_video_rate;
    cbs.on_video_stop = cb_on_video_stop;
    cbs.on_video_playlist_remove = cb_on_video_playlist_remove;
    cbs.on_video_acquire_playback_info = cb_on_video_info;
    cbs.get_custom_profile = cb_get_custom_profile;

    raop = raop_init(&cbs);
    if (!raop) {
        rc = -2;
        goto done;
    }
    raop_set_log_callback(raop, log_callback, NULL);
    raop_set_log_level(raop, LOGGER_DEBUG);
    if (raop_init2(raop, 1, mac, keyfile)) {
        LOGE("raop_init2 failed");
        free(raop);
        raop = NULL;
        rc = -3;
        goto done;
    }
    raop_set_plist(raop, "width", width);
    raop_set_plist(raop, "height", height);
    raop_set_plist(raop, "refreshRate", 60);
    raop_set_plist(raop, "maxFPS", 60);
    raop_set_plist(raop, "hls", 1);
    // Kept as pointers by UxPlay: static strings. Audio and subtitle tracks preferred in HLS master playlists.
    raop_set_lang(raop, "ru:en", "ru:en", "ru");

    unsigned short tcp[2] = {0, 0}, udp[3] = {0, 0, 0};
    raop_set_tcp_ports(raop, tcp);
    raop_set_udp_ports(raop, udp);
    unsigned short port = raop_get_port(raop);
    raop_start_httpd(raop, &port);
    raop_set_port(raop, port);
    raop_set_dnssd(raop, dnssd);

    if (dnssd_register_raop(dnssd, port) || dnssd_register_airplay(dnssd, port)) {
        LOGE("mDNS registration failed");
        rc = -4;
        goto done;
    }
    LOGI("AirPlay \"%s\" on port %u", name, port);

done:
    if (rc) stop_locked();
    (*env)->ReleaseByteArrayElements(env, jhw, hw, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    (*env)->ReleaseStringUTFChars(env, jhost, host);
    (*env)->ReleaseStringUTFChars(env, jkeyfile, keyfile);
    pthread_mutex_unlock(&server_lock);
    return rc;
}

JNIEXPORT void JNICALL Java_dev_okhotnikov_airplay_AirPlayBridge_nativeStop(JNIEnv *env, jclass cls) {
    pthread_mutex_lock(&server_lock);
    stop_locked();
    pthread_mutex_unlock(&server_lock);
}

JNIEXPORT void JNICALL
Java_dev_okhotnikov_airplay_AirPlayBridge_nativeSetSurface(JNIEnv *env, jclass cls, jobject surface) {
    video_set_window(surface ? ANativeWindow_fromSurface(env, surface) : NULL);
}
