// A C face for Apple's ALAC decoder (third_party/alac, Apache 2.0), so renderer.c stays plain C.
#include "alac_bridge.h"

#include "ALACBitUtilities.h"
#include "ALACDecoder.h"

extern "C" void *alac_create(const uint8_t *cookie, int len) {
    auto *dec = new ALACDecoder();
    if (dec->Init(const_cast<uint8_t *>(cookie), (uint32_t) len) != 0) {
        delete dec;
        return nullptr;
    }
    return dec;
}

extern "C" int alac_decode(void *decoder, const uint8_t *in, int len, int16_t *out, int max_frames) {
    auto *dec = static_cast<ALACDecoder *>(decoder);
    // Decode() decodes exactly numSamples frames into buffers sized for the cookie's frameLength: asking for
    // more overruns them (heap corruption) and fails the packet. A short last packet carries its own count.
    uint32_t frames_per_packet = dec->mConfig.frameLength;
    if (frames_per_packet == 0 || frames_per_packet > (uint32_t) max_frames) return -1;
    BitBuffer bits;
    BitBufferInit(&bits, const_cast<uint8_t *>(in), (uint32_t) len);
    uint32_t frames = 0;
    if (dec->Decode(&bits, reinterpret_cast<uint8_t *>(out), frames_per_packet, 2, &frames) != 0) return -1;
    return (int) frames;
}

extern "C" void alac_destroy(void *decoder) {
    delete static_cast<ALACDecoder *>(decoder);
}
