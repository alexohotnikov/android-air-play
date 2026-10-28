// Mirroring video and audio straight into the platform decoders: no JNI copies and no buffering beyond what the
// decoders hold, so latency stays at what the sender's own pacing gives. A/V sync is not attempted.
#include "renderer.h"
#include "alac_bridge.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <math.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define TAG "AirPlayTV"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static int64_t now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000000 + ts.tv_nsec / 1000;
}

/* ---- video ---- */

static pthread_mutex_t video_lock = PTHREAD_MUTEX_INITIALIZER;
static ANativeWindow *video_window;
static AMediaCodec *video_codec;
static bool video_codec_h265;
static int video_width = 1920, video_height = 1080;
static int reported_width, reported_height;
static void (*size_listener)(int width, int height);

void video_set_size_listener(void (*listener)(int width, int height)) {
    size_listener = listener;
}

// Every keyframe's SPS is reported, even unchanged: a screen opened mid-session needs it, and keyframes are rare.
static void report_size(int w, int h) {
    if (w <= 0 || h <= 0) return;
    if (w != reported_width || h != reported_height) LOGI("picture %dx%d", w, h);
    reported_width = w;
    reported_height = h;
    if (size_listener) size_listener(w, h);
}

/* ---- H.264 SPS: the picture size travels in the stream, so a resize the decoder keeps quiet about still shows. */

typedef struct {
    uint8_t buf[256];
    int len, pos;  // pos in bits
} bits_t;

static int bit(bits_t *b) {
    if (b->pos >= b->len * 8) return 0;
    int v = (b->buf[b->pos >> 3] >> (7 - (b->pos & 7))) & 1;
    b->pos++;
    return v;
}

static unsigned bits(bits_t *b, int n) {
    unsigned v = 0;
    while (n--) v = (v << 1) | bit(b);
    return v;
}

static unsigned ue(bits_t *b) {
    int zeros = 0;
    while (!bit(b) && zeros < 32) zeros++;
    return ((1u << zeros) - 1) + bits(b, zeros);
}

static int se(bits_t *b) {
    unsigned v = ue(b);
    return v & 1 ? (int) ((v + 1) / 2) : -(int) (v / 2);
}

static void skip_scaling_list(bits_t *b, int size) {
    int last = 8, next = 8;
    for (int i = 0; i < size; i++) {
        if (next) next = (last + se(b) + 256) % 256;
        last = next ? next : last;
    }
}

/** nal points just past the NAL header byte. */
static bool parse_sps(const uint8_t *nal, int len, int *width, int *height) {
    bits_t b = {.len = 0, .pos = 0};
    for (int i = 0, zeros = 0; i < len && b.len < (int) sizeof(b.buf); i++) {
        if (zeros >= 2 && nal[i] == 3) {  // emulation prevention byte
            zeros = 0;
            continue;
        }
        zeros = nal[i] ? 0 : zeros + 1;
        b.buf[b.len++] = nal[i];
    }
    unsigned profile = bits(&b, 8);
    bits(&b, 16);  // constraint flags, level
    ue(&b);        // sps id
    unsigned chroma = 1;
    if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44 || profile == 83
            || profile == 86 || profile == 118 || profile == 128 || profile == 138 || profile == 139
            || profile == 134 || profile == 135) {
        chroma = ue(&b);
        if (chroma == 3) bit(&b);
        ue(&b);
        ue(&b);
        bit(&b);
        if (bit(&b)) {
            for (int i = 0; i < (chroma == 3 ? 12 : 8); i++)
                if (bit(&b)) skip_scaling_list(&b, i < 6 ? 16 : 64);
        }
    }
    ue(&b);  // log2_max_frame_num
    unsigned poc = ue(&b);
    if (poc == 0) {
        ue(&b);
    } else if (poc == 1) {
        bit(&b);
        se(&b);
        se(&b);
        unsigned n = ue(&b);
        for (unsigned i = 0; i < n && i < 256; i++) se(&b);
    }
    ue(&b);  // max_num_ref_frames
    bit(&b);
    unsigned w_mbs = ue(&b) + 1, h_units = ue(&b) + 1;
    unsigned frame_mbs_only = bit(&b);
    if (!frame_mbs_only) bit(&b);
    bit(&b);
    int w = (int) w_mbs * 16, h = (int) ((2 - frame_mbs_only) * h_units * 16);
    if (bit(&b)) {
        unsigned l = ue(&b), r = ue(&b), t = ue(&b), btm = ue(&b);
        int ux = chroma == 0 || chroma == 3 ? 1 : 2;
        int uy = (chroma == 1 ? 2 : 1) * (int) (2 - frame_mbs_only);
        w -= ux * (int) (l + r);
        h -= uy * (int) (t + btm);
    }
    if (w <= 0 || h <= 0 || w > 8192 || h > 8192) return false;
    *width = w;
    *height = h;
    return true;
}

static void scan_for_sps(const uint8_t *d, int len) {
    for (int i = 0; i + 4 < len; i++) {
        if (d[i] || d[i + 1] || d[i + 2] != 1) continue;
        int type = d[i + 3] & 0x1f;
        if (type == 7) {
            int w, h;
            if (parse_sps(d + i + 4, len - i - 4, &w, &h)) report_size(w, h);
            return;
        }
        if (type == 1 || type == 5) return;  // slices follow the parameter sets: nothing more to find
        i += 3;
    }
}

// The visible picture, from the decoder: crop-* where the codec pads its buffers (MediaTek pads to 16 lines).
static void log_output_format(AMediaCodec *codec) {
    AMediaFormat *fmt = AMediaCodec_getOutputFormat(codec);
    if (!fmt) return;
    int32_t w = 0, h = 0, l = 0, t = 0, r = -1, b = -1;
    AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, &w);
    AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, &h);
    if (AMediaFormat_getInt32(fmt, "crop-left", &l) && AMediaFormat_getInt32(fmt, "crop-right", &r)
            && AMediaFormat_getInt32(fmt, "crop-top", &t) && AMediaFormat_getInt32(fmt, "crop-bottom", &b)) {
        w = r - l + 1;
        h = b - t + 1;
    }
    AMediaFormat_delete(fmt);
    // Logged only: the SPS is the one source for the layout, so two sources cannot fight over a padded line.
    LOGI("decoder output %dx%d", w, h);
}

/* Output on its own thread: a decoded frame goes to the screen the moment it is ready, instead of waiting for the
 * next input frame to come along and drain it (one frame, ~33 ms, at 30 fps). */
static pthread_t output_thread;
static volatile bool output_running;

static void *output_loop(void *arg) {
    AMediaCodec *codec = arg;
    AMediaCodecBufferInfo info;
    while (output_running) {
        ssize_t out = AMediaCodec_dequeueOutputBuffer(codec, &info, 10000);
        if (out >= 0) AMediaCodec_releaseOutputBuffer(codec, out, true);
        else if (out == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) log_output_format(codec);
    }
    return NULL;
}

static void video_release_locked(void) {
    reported_width = reported_height = 0;
    if (!video_codec) return;
    output_running = false;
    pthread_join(output_thread, NULL);
    AMediaCodec_stop(video_codec);
    AMediaCodec_delete(video_codec);
    video_codec = NULL;
}

static bool video_create_locked(bool is_h265) {
    const char *mime = is_h265 ? "video/hevc" : "video/avc";
    video_codec = AMediaCodec_createDecoderByType(mime);
    if (!video_codec) {
        LOGE("no decoder for %s", mime);
        return false;
    }
    AMediaFormat *fmt = AMediaFormat_new();
    AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, mime);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, video_width);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, video_height);
    // Room to change size or turn portrait without a new decoder ("adaptive-playback" on the MediaTek decoder).
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_MAX_WIDTH, 1920);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_MAX_HEIGHT, 1920);
    // Real-time priority and a high operating rate: decode each frame as soon as it arrives. The MediaTek decoder
    // lists no "low-latency" feature, so that key is still sent only for decoders that have it.
    AMediaFormat_setInt32(fmt, "priority", 0);
    AMediaFormat_setFloat(fmt, "operating-rate", 120.0f);
    AMediaFormat_setInt32(fmt, "low-latency", 1);
    media_status_t st = AMediaCodec_configure(video_codec, fmt, video_window, NULL, 0);
    AMediaFormat_delete(fmt);
    if (st != AMEDIA_OK || AMediaCodec_start(video_codec) != AMEDIA_OK) {
        LOGE("video decoder configure/start failed: %d", st);
        AMediaCodec_delete(video_codec);
        video_codec = NULL;
        return false;
    }
    video_codec_h265 = is_h265;
    output_running = true;
    pthread_create(&output_thread, NULL, output_loop, video_codec);
    LOGI("video decoder %s %dx%d started", mime, video_width, video_height);
    return true;
}

void video_set_window(ANativeWindow *window) {
    pthread_mutex_lock(&video_lock);
    video_release_locked();
    if (video_window) ANativeWindow_release(video_window);
    video_window = window;
    pthread_mutex_unlock(&video_lock);
}

void video_set_size(int width, int height) {
    pthread_mutex_lock(&video_lock);
    if (width > 0 && height > 0 && (width != video_width || height != video_height)) {
        video_width = width;
        video_height = height;
        // The stream restarts with fresh SPS/PPS after a size change; a decoder sized for the old stream may not
        // take them, so start over.
        video_release_locked();
    }
    pthread_mutex_unlock(&video_lock);
}

/* Every access unit since the last keyframe. iOS sends a keyframe when mirroring starts and seldom after, and the
 * stream can start before the screen that shows it (the background service opens it on connect): without these a
 * late decoder would have nothing to start from. */
#define GOP_MAX_FRAMES 600
#define GOP_MAX_BYTES (24 * 1024 * 1024)
static struct {
    uint8_t *data[GOP_MAX_FRAMES];
    int len[GOP_MAX_FRAMES];
    int count, bytes;
    bool overflow;  // too long to keep: wait for the next keyframe
} gop;

static void gop_clear(void) {
    for (int i = 0; i < gop.count; i++) free(gop.data[i]);
    gop.count = gop.bytes = 0;
    gop.overflow = false;
}

static bool is_keyframe(const uint8_t *d, int len, bool is_h265) {
    for (int i = 0; i + 3 < len; i++) {
        if (d[i] || d[i + 1] || d[i + 2] != 1) continue;
        int type = is_h265 ? (d[i + 3] >> 1) & 0x3f : d[i + 3] & 0x1f;
        if (is_h265 ? type >= 16 && type <= 21 : type == 5) return true;
        i += 2;
    }
    return false;
}

/** True when this frame was kept, as the newest entry. */
static bool gop_add(const uint8_t *d, int len, bool is_h265) {
    if (is_keyframe(d, len, is_h265)) gop_clear();
    else if (gop.overflow || gop.count == 0) return false;  // no keyframe to start from
    if (gop.count == GOP_MAX_FRAMES || gop.bytes + len > GOP_MAX_BYTES) {
        gop_clear();
        gop.overflow = true;
        return false;
    }
    uint8_t *copy = malloc(len);
    if (!copy) return false;
    memcpy(copy, d, len);
    gop.data[gop.count] = copy;
    gop.len[gop.count++] = len;
    gop.bytes += len;
    return true;
}

static void video_feed_locked(const uint8_t *data, int len) {
    ssize_t in = AMediaCodec_dequeueInputBuffer(video_codec, 20000);
    if (in < 0) return;
    size_t cap = 0;
    uint8_t *buf = AMediaCodec_getInputBuffer(video_codec, in, &cap);
    if (buf && (size_t) len <= cap) {
        memcpy(buf, data, len);
        AMediaCodec_queueInputBuffer(video_codec, in, 0, len, now_us(), 0);
    } else {
        AMediaCodec_queueInputBuffer(video_codec, in, 0, 0, now_us(), 0);
        LOGE("video frame of %d bytes does not fit %zu", len, cap);
    }
}

void video_render(const unsigned char *data, int len, bool is_h265) {
    pthread_mutex_lock(&video_lock);
    if (!is_h265) scan_for_sps(data, len);
    bool kept = gop_add(data, len, is_h265);
    if (!video_window) goto out;
    if (video_codec && video_codec_h265 != is_h265) video_release_locked();
    if (!video_codec) {
        if (!video_create_locked(is_h265)) goto out;
        int replay = kept ? gop.count - 1 : gop.count;  // the current frame is fed below
        if (replay) LOGI("replaying %d frames from the last keyframe", replay);
        for (int i = 0; i < replay; i++) video_feed_locked(gop.data[i], gop.len[i]);
    }
    video_feed_locked(data, len);
out:
    pthread_mutex_unlock(&video_lock);
}

void video_stop(void) {
    pthread_mutex_lock(&video_lock);
    video_release_locked();
    gop_clear();
    pthread_mutex_unlock(&video_lock);
}

/* ---- audio ---- */

static pthread_mutex_t audio_lock = PTHREAD_MUTEX_INITIALIZER;
static AMediaCodec *audio_codec;   // AAC-ELD, AAC-LC
static void *audio_alac;           // ALAC
static unsigned char audio_ct;     // 0: nothing open; a format that failed stays here so it is not retried per packet
static bool audio_failed;
static AAudioStream *audio_stream;
static float audio_gain = 1.0f;

static void audio_release_locked(void) {
    if (audio_codec) {
        AMediaCodec_stop(audio_codec);
        AMediaCodec_delete(audio_codec);
        audio_codec = NULL;
    }
    if (audio_alac) {
        alac_destroy(audio_alac);
        audio_alac = NULL;
    }
    if (audio_stream) {
        AAudioStream_requestStop(audio_stream);
        AAudioStream_close(audio_stream);
        audio_stream = NULL;
    }
    audio_ct = 0;
    audio_failed = false;
}

/** Music-only AirPlay sends ALAC well ahead of time, so its stream gets a deep buffer and blocking writes. */
static bool audio_open_sink_locked(bool music) {
    AAudioStreamBuilder *b;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return false;
    AAudioStreamBuilder_setSampleRate(b, 44100);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(b, music ? AAUDIO_PERFORMANCE_MODE_NONE
                                                    : AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &audio_stream);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) {
        LOGE("AAudio open failed: %s", AAudio_convertResultToText(r));
        audio_stream = NULL;
        return false;
    }
    int32_t burst = AAudioStream_getFramesPerBurst(audio_stream);
    // Mirroring: a few bursts, as AirPlay packets arrive in clumps over Wi-Fi. Music: as deep as the sink allows.
    AAudioStream_setBufferSizeInFrames(audio_stream, music ? AAudioStream_getBufferCapacityInFrames(audio_stream)
                                                           : burst * 8);
    AAudioStream_requestStart(audio_stream);
    return true;
}

static bool audio_create_locked(unsigned char ct) {
    audio_ct = ct;
    if (ct == 2) {
        // The ALAC magic cookie UxPlay's GStreamer caps give: 352 frames per packet, 16-bit, stereo, 44100 Hz.
        static const uint8_t cookie[] = {
                0x00, 0x00, 0x00, 0x24, 0x61, 0x6c, 0x61, 0x63, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x01, 0x60, 0x00, 0x10, 0x28, 0x0a, 0x0e, 0x02, 0x00, 0xff,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xac, 0x44};
        audio_alac = alac_create(cookie, sizeof(cookie));
        if (!audio_alac) {
            LOGE("ALAC decoder refused the magic cookie");
            goto fail;
        }
        if (!audio_open_sink_locked(true)) goto fail;
        LOGI("audio ALAC started");
        return true;
    }
    // 8 AAC-ELD (screen mirroring), 4 AAC-LC; csd-0 as UxPlay's GStreamer caps give it.
    static const uint8_t eld[] = {0xf8, 0xe8, 0x50, 0x00};
    static const uint8_t lc[] = {0x12, 0x10};
    const uint8_t *csd = ct == 8 ? eld : ct == 4 ? lc : NULL;
    if (!csd) {
        LOGE("audio format ct=%d not supported", ct);
        goto fail;
    }
    audio_codec = AMediaCodec_createDecoderByType("audio/mp4a-latm");
    if (!audio_codec) goto fail;
    AMediaFormat *fmt = AMediaFormat_new();
    AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, "audio/mp4a-latm");
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, 44100);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, 2);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_IS_ADTS, 0);
    AMediaFormat_setBuffer(fmt, "csd-0", csd, ct == 8 ? sizeof(eld) : sizeof(lc));
    media_status_t st = AMediaCodec_configure(audio_codec, fmt, NULL, NULL, 0);
    AMediaFormat_delete(fmt);
    if (st != AMEDIA_OK || AMediaCodec_start(audio_codec) != AMEDIA_OK) {
        LOGE("audio decoder configure/start failed: %d", st);
        AMediaCodec_delete(audio_codec);
        audio_codec = NULL;
        goto fail;
    }
    if (!audio_open_sink_locked(false)) goto fail;
    LOGI("audio decoder ct=%d started", ct);
    return true;
fail:
    audio_release_locked();
    audio_ct = ct;
    audio_failed = true;
    return false;
}

static void audio_play_locked(int16_t *s, int frames, bool music) {
    if (audio_gain < 0.999f) for (int i = 0; i < frames * 2; i++) s[i] = (int16_t) (s[i] * audio_gain);
    // Mirroring never blocks the RTP thread: a full sink drops the packet. Music waits for room instead.
    aaudio_result_t r = AAudioStream_write(audio_stream, s, frames, music ? 200000000LL : 0);
}

void audio_render(unsigned char ct, const unsigned char *data, int len) {
    pthread_mutex_lock(&audio_lock);
    if (audio_ct && audio_ct != ct) audio_release_locked();
    if (audio_failed) goto out;
    if (!audio_ct && !audio_create_locked(ct)) goto out;

    if (audio_alac) {
        int16_t pcm[4096 * 2];
        int frames = alac_decode(audio_alac, data, len, pcm, 4096);
        if (frames > 0) audio_play_locked(pcm, frames, true);
        goto out;
    }
    ssize_t in = AMediaCodec_dequeueInputBuffer(audio_codec, 10000);
    if (in >= 0) {
        size_t cap = 0;
        uint8_t *buf = AMediaCodec_getInputBuffer(audio_codec, in, &cap);
        int n = buf && (size_t) len <= cap ? len : 0;
        if (n) memcpy(buf, data, n);
        AMediaCodec_queueInputBuffer(audio_codec, in, 0, n, now_us(), 0);
    }
    AMediaCodecBufferInfo info;
    ssize_t out;
    while ((out = AMediaCodec_dequeueOutputBuffer(audio_codec, &info, 0)) != AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
        if (out < 0) continue;
        size_t size = 0;
        uint8_t *pcm = AMediaCodec_getOutputBuffer(audio_codec, out, &size);
        if (pcm && info.size > 0) audio_play_locked((int16_t *) (pcm + info.offset), info.size / 4, false);
        AMediaCodec_releaseOutputBuffer(audio_codec, out, false);
    }
out:
    pthread_mutex_unlock(&audio_lock);
}

void audio_set_volume_db(float db) {
    // AirPlay volume: -144 is mute, otherwise -30..0 dB.
    audio_gain = db <= -144.0f ? 0.0f : powf(10.0f, db / 20.0f);
}

void audio_stop(void) {
    pthread_mutex_lock(&audio_lock);
    audio_release_locked();
    pthread_mutex_unlock(&audio_lock);
}
