#ifndef AIRPLAYTV_RENDERER_H
#define AIRPLAYTV_RENDERER_H

#include <android/native_window.h>
#include <stdbool.h>

void video_set_window(ANativeWindow *window);
void video_set_size(int width, int height);
/** Called with the decoded picture's visible size whenever the decoder reports it. */
void video_set_size_listener(void (*listener)(int width, int height));
void video_render(const unsigned char *data, int len, bool is_h265);
void video_stop(void);

void audio_render(unsigned char ct, const unsigned char *data, int len);
void audio_set_volume_db(float db);
void audio_stop(void);

#endif
