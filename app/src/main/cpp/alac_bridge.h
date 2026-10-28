#ifndef AIRPLAYTV_ALAC_BRIDGE_H
#define AIRPLAYTV_ALAC_BRIDGE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** NULL when the magic cookie is not understood. */
void *alac_create(const uint8_t *cookie, int len);
/** Interleaved 16-bit stereo into out, which holds max_frames; returns frames decoded, or -1. */
int alac_decode(void *decoder, const uint8_t *in, int len, int16_t *out, int max_frames);
void alac_destroy(void *decoder);

#ifdef __cplusplus
}
#endif

#endif
