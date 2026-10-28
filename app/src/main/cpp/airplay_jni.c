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

enum { EV_CONNECTED = 1, EV_DISCONNECTED = 2, EV_VIDEO_ON = 3, EV_VIDEO_OFF = 4 };

static JavaVM *jvm;
static jclass bridge_class;
static jmethodID on_event;
static jmethodID on_video_size;
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

static void cb_conn_init(void *cls) { post_event(EV_CONNECTED); }
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
    audio_render(d->ct, d->data, d->data_len);
}
static void cb_video_pause(void *cls) {}
static void cb_video_resume(void *cls) {}
static void cb_video_flush(void *cls) {}
static void cb_audio_flush(void *cls) {}
static void cb_video_reset(void *cls, reset_type_t type) {
    video_stop();
    post_event(EV_VIDEO_OFF);
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
}
static void cb_audio_set_metadata(void *cls, const void *b, int n) {}
static void cb_audio_set_coverart(void *cls, const void *b, int n) {}
static void cb_audio_stop_coverart(void *cls) {}
static void cb_audio_set_progress(void *cls, uint32_t *s, uint32_t *c, uint32_t *e) {}
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

// HLS (YouTube-style "play this URL") is switched off in the features, so these stay empty.
static void cb_on_video_play(void *cls, const char *loc, const float start) {}
static void cb_on_video_scrub(void *cls, const float pos) {}
static void cb_on_video_rate(void *cls, const float rate) {}
static void cb_on_video_stop(void *cls) {}
static void cb_on_video_info(void *cls, playback_info_t *info) {}
static float cb_on_video_playlist_remove(void *cls) { return 0.0f; }
static void cb_get_custom_profile(void *cls, const char **profile) { *profile = NULL; }

static void log_callback(void *cls, int level, const char *msg) {
    __android_log_print(level <= LOGGER_ERR ? ANDROID_LOG_ERROR : level <= LOGGER_INFO ? ANDROID_LOG_INFO
                                                                                     : ANDROID_LOG_DEBUG,
                        "UxPlay", "%s", msg);
}

static void set_features(dnssd_t *d) {
    // UxPlay's defaults for screen mirroring with audio; HLS (bits 0, 4) and H.265 (bit 42) off.
    static const int on[] = {1, 2, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 25, 27, 28, 30};
    static const int off[] = {0, 3, 4, 8, 23, 24, 26, 29, 31, 42};
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
    raop_set_log_level(raop, LOGGER_INFO);
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
