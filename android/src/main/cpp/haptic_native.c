#include <jni.h>
#include <android/log.h>

#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>

#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

#define HAPTIC_TAG "DS5Haptics"
#define HLOGE(...) __android_log_print(ANDROID_LOG_ERROR, HAPTIC_TAG, __VA_ARGS__)
#define HLOGI(...) __android_log_print(ANDROID_LOG_INFO, HAPTIC_TAG, __VA_ARGS__)

#define INPUT_CHANNELS 2
#define OUTPUT_CHANNELS 4
#define UPSAMPLE_FACTOR 16
#define BYTES_PER_INPUT_FRAME (INPUT_CHANNELS * (int)sizeof(int16_t))
#define ISO_PACKET_COUNT 10
#define ISO_PACKET_SIZE 392
#define MAX_OUTPUT_BYTES (ISO_PACKET_COUNT * ISO_PACKET_SIZE)
#define BYTES_PER_OUTPUT_FRAME (OUTPUT_CHANNELS * (int)sizeof(int16_t))

static pthread_mutex_t g_haptic_mutex = PTHREAD_MUTEX_INITIALIZER;

static int g_usb_fd = -1;
static int g_haptic_iface = -1;
static int g_haptic_alt_setting = -1;
static uint8_t g_haptic_endpoint = 0;
static int g_haptic_enabled = 0;
static int16_t* g_upsampled_buffer = NULL;

static int16_t clamp_i16(int value) {
    if (value > 32767) {
        return 32767;
    }
    if (value < -32768) {
        return -32768;
    }
    return (int16_t) value;
}

static float sanitize_haptic_gain(float gain) {
    if (gain != gain) {
        return 0.5f;
    }
    if (gain < 0.0f) {
        return 0.0f;
    }
    if (gain > 2.75f) {
        return 2.75f;
    }
    return gain;
}

static void expand_stereo_to_quad(const int16_t* input, int input_frames, int16_t* output) {
    int i;
    for (i = 0; i < input_frames; i++) {
        const int16_t left = input[i * 2];
        const int16_t right = input[i * 2 + 1];
        output[i * 4] = 0;
        output[i * 4 + 1] = left;
        output[i * 4 + 2] = left;
        output[i * 4 + 3] = right;
    }
}

static void apply_haptic_gain_to_effective_channels(int16_t* quad, int input_frames, float gain) {
    int i;
    if (quad == NULL || input_frames <= 0 || gain == 1.0f) {
        return;
    }

    for (i = 0; i < input_frames; i++) {
        const int idx = i * 4;
        quad[idx + 1] = clamp_i16((int) (quad[idx + 1] * gain * 1.35f));
        quad[idx + 2] = clamp_i16((int) (quad[idx + 2] * gain * 1.65f));
        quad[idx + 3] = clamp_i16((int) (quad[idx + 3] * gain * 1.55f));
    }
}

static void linear_upsample_3k_to_48k(const int16_t* input_quad, int input_frames, int16_t* output_quad) {
    int out_idx;
    const int last = input_frames - 1;
    const int out_frames = input_frames * UPSAMPLE_FACTOR;

    if (input_frames <= 0) {
        return;
    }

    for (out_idx = 0; out_idx < out_frames; out_idx++) {
        int ch;
        const int src_idx = out_idx / UPSAMPLE_FACTOR;
        const int phase = out_idx % UPSAMPLE_FACTOR;
        const int next_idx = (src_idx < last) ? (src_idx + 1) : src_idx;

        for (ch = 0; ch < OUTPUT_CHANNELS; ch++) {
            const int s0 = input_quad[src_idx * OUTPUT_CHANNELS + ch];
            const int s1 = input_quad[next_idx * OUTPUT_CHANNELS + ch];
            const int mixed = ((UPSAMPLE_FACTOR - phase) * s0 + phase * s1) / UPSAMPLE_FACTOR;
            output_quad[out_idx * OUTPUT_CHANNELS + ch] = clamp_i16(mixed);
        }
    }
}

/*
 * Keep several isochronous URBs in flight so the host controller always has the
 * next 1 ms packet queued. The previous implementation submitted one URB and
 * waited for it to complete, which leaves a gap at every URB boundary that the
 * DualSense's audio buffer hears as periodic dropouts (the stuttering tone).
 */
#define PIPELINE_DEPTH 3

typedef struct {
    struct usbdevfs_urb* urb;
    uint8_t* buffer;
    int buffer_size;
} haptic_slot;

static haptic_slot g_slots[PIPELINE_DEPTH];
static int g_inflight = 0;

static void pipeline_reap(int blocking) {
    struct usbdevfs_urb* reaped = NULL;
    int op = blocking ? USBDEVFS_REAPURB : USBDEVFS_REAPURBNDELAY;
    int i;

    if (g_inflight <= 0) {
        return;
    }
    if (ioctl(g_usb_fd, op, &reaped) != 0) {
        return;
    }

    for (i = 0; i < PIPELINE_DEPTH; i++) {
        if (g_slots[i].urb == reaped) {
            if (reaped->status != 0) {
                HLOGE("Haptic URB completed with status=%d", reaped->status);
            }
            free(reaped);
            g_slots[i].urb = NULL;
            g_inflight--;
            return;
        }
    }
    HLOGE("Reaped unexpected haptic URB");
}

static void pipeline_flush(void) {
    while (g_inflight > 0) {
        pipeline_reap(1);
    }
}

static int submit_iso_pcm_locked(void* buffer, int length) {
    int frames;
    int packet_count;
    int base_frames;
    int extra_frames;
    int i;
    int slot = -1;
    size_t urb_size;
    struct usbdevfs_urb* urb;
    haptic_slot* s;

    if (buffer == NULL || length <= 0 || length > MAX_OUTPUT_BYTES ||
            length % BYTES_PER_OUTPUT_FRAME != 0) {
        return 0;
    }

    frames = length / BYTES_PER_OUTPUT_FRAME;
    packet_count = (frames + 48) / 49;
    if (packet_count <= 0 || packet_count > ISO_PACKET_COUNT) {
        return 0;
    }

    /* Reclaim completed URBs, then block for one if the pipeline is saturated. */
    pipeline_reap(0);
    if (g_inflight >= PIPELINE_DEPTH) {
        pipeline_reap(1);
    }
    for (i = 0; i < PIPELINE_DEPTH; i++) {
        if (g_slots[i].urb == NULL) {
            slot = i;
            break;
        }
    }
    if (slot < 0) {
        return 0;
    }

    s = &g_slots[slot];
    if (s->buffer_size < length) {
        uint8_t* grown = (uint8_t*) realloc(s->buffer, (size_t) length);
        if (grown == NULL) {
            HLOGE("Failed to allocate haptic buffer");
            return 0;
        }
        s->buffer = grown;
        s->buffer_size = length;
    }
    memcpy(s->buffer, buffer, (size_t) length);

    urb_size = sizeof(struct usbdevfs_urb) +
            ((size_t) packet_count * sizeof(struct usbdevfs_iso_packet_desc));
    urb = (struct usbdevfs_urb*) calloc(1, urb_size);
    if (urb == NULL) {
        HLOGE("Failed to allocate haptic URB");
        return 0;
    }

    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = g_haptic_endpoint;
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->buffer = s->buffer;
    urb->buffer_length = length;
    urb->number_of_packets = packet_count;

    base_frames = frames / packet_count;
    extra_frames = frames % packet_count;
    for (i = 0; i < packet_count; i++) {
        int packet_frames = base_frames + (i < extra_frames ? 1 : 0);
        urb->iso_frame_desc[i].length = packet_frames * BYTES_PER_OUTPUT_FRAME;
    }

    if (ioctl(g_usb_fd, USBDEVFS_SUBMITURB, urb) != 0) {
        HLOGE("Failed to submit haptic URB, errno=%d", errno);
        free(urb);
        return 0;
    }

    s->urb = urb;
    g_inflight++;
    return 1;
}

JNIEXPORT jboolean JNICALL
Java_com_zyz4_gkme_input_usb_HapticNative_nativeConnectHaptics(
        JNIEnv* env, jclass clazz, jint fd, jint ifaceId, jint altSetting, jbyte epAddr) {
    struct usbdevfs_setinterface set_interface;
    (void) env;
    (void) clazz;

    pthread_mutex_lock(&g_haptic_mutex);

    if (fd < 0) {
        HLOGE("nativeConnectHaptics: invalid fd=%d", fd);
        pthread_mutex_unlock(&g_haptic_mutex);
        return JNI_FALSE;
    }

    memset(&set_interface, 0, sizeof(set_interface));
    set_interface.interface = ifaceId;
    set_interface.altsetting = altSetting;

    if (ioctl(fd, USBDEVFS_SETINTERFACE, &set_interface) != 0) {
        HLOGE("Failed to set interface, errno=%d", errno);
        pthread_mutex_unlock(&g_haptic_mutex);
        return JNI_FALSE;
    }

    g_usb_fd = fd;
    g_haptic_iface = ifaceId;
    g_haptic_alt_setting = altSetting;
    g_haptic_endpoint = (uint8_t) epAddr;
    g_haptic_enabled = 0;

    HLOGI("Connected haptics fd=%d iface=%d alt=%d ep=0x%02X",
          g_usb_fd, g_haptic_iface, g_haptic_alt_setting, g_haptic_endpoint);

    pthread_mutex_unlock(&g_haptic_mutex);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_zyz4_gkme_input_usb_HapticNative_nativeEnableHaptics(
        JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;

    pthread_mutex_lock(&g_haptic_mutex);

    if (g_usb_fd < 0) {
        pthread_mutex_unlock(&g_haptic_mutex);
        return JNI_FALSE;
    }

    memset(g_slots, 0, sizeof(g_slots));
    g_inflight = 0;

    if (g_upsampled_buffer == NULL) {
        g_upsampled_buffer = (int16_t*) calloc(1, MAX_OUTPUT_BYTES);
    }

    g_haptic_enabled = (g_upsampled_buffer != NULL);

    pthread_mutex_unlock(&g_haptic_mutex);
    return g_haptic_enabled ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_zyz4_gkme_input_usb_HapticNative_nativeSendHapticFeedback(
        JNIEnv* env, jclass clazz, jobject buffer, jint length, jfloat intensityGain) {
    int16_t* input;
    int input_frames;
    int16_t* quad;
    int upsampled_frames;
    int output_samples;
    int output_bytes;
    int success;

    (void) clazz;

    pthread_mutex_lock(&g_haptic_mutex);

    if (g_usb_fd < 0 || !g_haptic_enabled || g_upsampled_buffer == NULL || buffer == NULL) {
        pthread_mutex_unlock(&g_haptic_mutex);
        return JNI_FALSE;
    }

    input = (int16_t*) (*env)->GetDirectBufferAddress(env, buffer);
    if (input == NULL || length <= 0) {
        pthread_mutex_unlock(&g_haptic_mutex);
        return JNI_FALSE;
    }

    input_frames = length / BYTES_PER_INPUT_FRAME;
    if (input_frames <= 0) {
        pthread_mutex_unlock(&g_haptic_mutex);
        return JNI_FALSE;
    }

    quad = (int16_t*) malloc((size_t) input_frames * OUTPUT_CHANNELS * sizeof(int16_t));
    if (quad == NULL) {
        pthread_mutex_unlock(&g_haptic_mutex);
        return JNI_FALSE;
    }

    expand_stereo_to_quad(input, input_frames, quad);
    apply_haptic_gain_to_effective_channels(quad, input_frames, sanitize_haptic_gain(intensityGain));

    upsampled_frames = input_frames * UPSAMPLE_FACTOR;
    output_samples = upsampled_frames * OUTPUT_CHANNELS;
    output_bytes = output_samples * (int) sizeof(int16_t);
    if (output_bytes <= 0 || output_bytes > MAX_OUTPUT_BYTES) {
        free(quad);
        pthread_mutex_unlock(&g_haptic_mutex);
        return JNI_FALSE;
    }

    linear_upsample_3k_to_48k(quad, input_frames, g_upsampled_buffer);
    free(quad);

    success = submit_iso_pcm_locked(g_upsampled_buffer, output_bytes);
    pthread_mutex_unlock(&g_haptic_mutex);
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_zyz4_gkme_input_usb_HapticNative_nativeSendNativeHapticPcm(
        JNIEnv* env, jclass clazz, jobject buffer, jint length) {
    void* pcm;
    jlong capacity;
    int success;
    (void) clazz;

    pthread_mutex_lock(&g_haptic_mutex);
    if (g_usb_fd < 0 || !g_haptic_enabled || buffer == NULL) {
        pthread_mutex_unlock(&g_haptic_mutex);
        return JNI_FALSE;
    }

    pcm = (*env)->GetDirectBufferAddress(env, buffer);
    capacity = (*env)->GetDirectBufferCapacity(env, buffer);
    if (pcm == NULL || length <= 0 || length > capacity ||
            length > MAX_OUTPUT_BYTES || length % BYTES_PER_OUTPUT_FRAME != 0) {
        pthread_mutex_unlock(&g_haptic_mutex);
        return JNI_FALSE;
    }

    success = submit_iso_pcm_locked(pcm, length);
    pthread_mutex_unlock(&g_haptic_mutex);
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_zyz4_gkme_input_usb_HapticNative_nativeCleanupHaptics(
        JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;

    pthread_mutex_lock(&g_haptic_mutex);

    g_haptic_enabled = 0;

    pipeline_flush();
    {
        int i;
        for (i = 0; i < PIPELINE_DEPTH; i++) {
            free(g_slots[i].buffer);
            g_slots[i].buffer = NULL;
            g_slots[i].buffer_size = 0;
            g_slots[i].urb = NULL;
        }
        g_inflight = 0;
    }

    if (g_upsampled_buffer != NULL) {
        free(g_upsampled_buffer);
        g_upsampled_buffer = NULL;
    }

    g_usb_fd = -1;
    g_haptic_iface = -1;
    g_haptic_alt_setting = -1;
    g_haptic_endpoint = 0;

    pthread_mutex_unlock(&g_haptic_mutex);
}
