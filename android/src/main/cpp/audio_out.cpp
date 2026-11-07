// Low-latency phone audio output built directly on AAudio.
//
// The app never routes audio through SDL: this module owns AAudio streams keyed
// by an application handle (voice coil / controller speaker). If a stream
// cannot be opened (device gone, format unsupported, AAudio unavailable) the
// Kotlin side falls back to an AudioTrack built by AudioOutput.kt.
//
// AAudio device ids are the same ids Android exposes through
// AudioManager.getDevices(GET_DEVICES_OUTPUTS); 0 means "let the system pick".

#include <jni.h>
#include <aaudio/AAudio.h>
#include <android/log.h>

#include <cstdint>
#include <mutex>
#include <unordered_map>
#include <vector>

#define LOG_TAG "GkmeAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Sink {
    AAudioStream *stream = nullptr;
    int32_t channels = 2;
    int32_t sampleRate = 48000;
};

std::mutex g_mutex;
std::unordered_map<int, Sink> g_sinks;

const char *resultName(aaudio_result_t result) {
    return AAudio_convertResultToText(result);
}

// Opens one output stream in the given sharing mode. The producer writes
// 10 ms blocks from a normal-priority thread, so keep about four bursts queued:
// enough slack to survive scheduling jitter, still well below the old ~30 ms
// queue while remaining a low-latency stream.
AAudioStream *openStream(int32_t deviceId, int32_t sampleRate, int32_t channels,
                         aaudio_sharing_mode_t sharingMode) {
    AAudioStreamBuilder *builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK || builder == nullptr) {
        LOGE("AAudio_createStreamBuilder failed: %s", resultName(result));
        return nullptr;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, channels);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
    AAudioStreamBuilder_setSharingMode(builder, sharingMode);
    if (deviceId > 0) {
        AAudioStreamBuilder_setDeviceId(builder, deviceId);
    }

    AAudioStream *stream = nullptr;
    result = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK || stream == nullptr) {
        LOGE("AAudio open failed (device=%d rate=%d ch=%d sharing=%d): %s",
             deviceId, sampleRate, channels, static_cast<int>(sharingMode), resultName(result));
        return nullptr;
    }

    // AAudio may silently degrade the stream (e.g. some vendor HALs only give a
    // deep-buffer output even when LOW_LATENCY is requested). Treat that as an
    // open failure so the Kotlin side can fall back to AudioTrack instead of
    // streaming with 100+ ms of latency.
    const aaudio_performance_mode_t grantedMode = AAudioStream_getPerformanceMode(stream);
    if (grantedMode != AAUDIO_PERFORMANCE_MODE_LOW_LATENCY) {
        LOGE("AAudio refused LOW_LATENCY (granted mode=%d), closing", static_cast<int>(grantedMode));
        AAudioStream_close(stream);
        return nullptr;
    }

    // The app writes the requested PCM layout verbatim; any implicit conversion
    // would change pitch/tempo, so reject a mismatched stream.
    const int32_t actualRate = AAudioStream_getSampleRate(stream);
    const int32_t actualChannels = AAudioStream_getChannelCount(stream);
    const aaudio_format_t actualFormat = AAudioStream_getFormat(stream);
    if (actualRate != sampleRate || actualChannels != channels ||
        actualFormat != AAUDIO_FORMAT_PCM_I16) {
        LOGE("AAudio returned mismatched format (rate=%d ch=%d fmt=%d), closing",
             actualRate, actualChannels, static_cast<int>(actualFormat));
        AAudioStream_close(stream);
        return nullptr;
    }

    const int32_t framesPerBurst = AAudioStream_getFramesPerBurst(stream);
    if (framesPerBurst > 0) {
        // Ignore failure: some HALs pin the buffer size and still run fine.
        AAudioStream_setBufferSizeInFrames(stream, framesPerBurst * 4);
    }

    result = AAudioStream_requestStart(stream);
    if (result != AAUDIO_OK) {
        LOGE("AAudio requestStart failed: %s", resultName(result));
        AAudioStream_close(stream);
        return nullptr;
    }

    LOGI("AAudio sink opened: device=%d rate=%d ch=%d burst=%d sharing=%d",
         deviceId, sampleRate, channels, framesPerBurst, static_cast<int>(sharingMode));
    return stream;
}

AAudioStream *openLowLatencyStream(int32_t deviceId, int32_t sampleRate, int32_t channels) {
    // Exclusive mode is the lowest-latency path. Not every HAL accepts it, so
    // retry shared before giving up on AAudio entirely.
    AAudioStream *stream = openStream(deviceId, sampleRate, channels, AAUDIO_SHARING_MODE_EXCLUSIVE);
    if (stream == nullptr) {
        LOGI("AAudio exclusive mode unavailable, retrying shared");
        stream = openStream(deviceId, sampleRate, channels, AAUDIO_SHARING_MODE_SHARED);
    }
    return stream;
}

// Caller must hold g_mutex.
void closeLocked(int handle) {
    auto it = g_sinks.find(handle);
    if (it == g_sinks.end()) {
        return;
    }
    if (it->second.stream != nullptr) {
        AAudioStream_requestStop(it->second.stream);
        AAudioStream_close(it->second.stream);
    }
    g_sinks.erase(it);
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_zyz4_gkme_audio_AudioNative_nativeOpen(JNIEnv *env, jobject thiz, jint handle,
                                                jint deviceId, jint sampleRate, jint channels,
                                                jint maxQueuedMs) {
    (void) env;
    (void) thiz;
    (void) maxQueuedMs;  // AAudio paces the writer through its own buffer.
    if (sampleRate <= 0 || channels <= 0 || channels > 2) {
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    closeLocked(static_cast<int>(handle));

    AAudioStream *stream = openLowLatencyStream(static_cast<int32_t>(deviceId),
                                                static_cast<int32_t>(sampleRate),
                                                static_cast<int32_t>(channels));
    if (stream == nullptr) {
        return JNI_FALSE;
    }

    Sink sink;
    sink.stream = stream;
    sink.channels = static_cast<int32_t>(channels);
    sink.sampleRate = static_cast<int32_t>(sampleRate);
    g_sinks[static_cast<int>(handle)] = sink;
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_zyz4_gkme_audio_AudioNative_nativeWrite(JNIEnv *env, jobject thiz, jint handle,
                                                 jbyteArray data, jint waitBudgetMs) {
    (void) thiz;
    if (data == nullptr) {
        return -1;
    }
    const jsize length = env->GetArrayLength(data);
    if (length <= 0) {
        return 0;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_sinks.find(static_cast<int>(handle));
    if (it == g_sinks.end() || it->second.stream == nullptr) {
        return -1;
    }

    Sink &sink = it->second;
    const int32_t frameBytes = sink.channels * 2;
    const int32_t totalFrames = length / frameBytes;
    if (totalFrames <= 0) {
        return 0;
    }

    std::vector<uint8_t> buffer(static_cast<size_t>(length));
    env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte *>(buffer.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return -1;
    }

    const int64_t timeoutNs =
        (waitBudgetMs > 0 ? static_cast<int64_t>(waitBudgetMs) : 200LL) * 1000000LL;
    int32_t framesWritten = 0;
    while (framesWritten < totalFrames) {
        const int32_t written = AAudioStream_write(
            sink.stream,
            buffer.data() + static_cast<size_t>(framesWritten) * frameBytes,
            totalFrames - framesWritten,
            timeoutNs);
        if (written < 0) {
            if (written == AAUDIO_ERROR_TIMEOUT) {
                // Queue is still full: report what made it in and let the next
                // call continue instead of tearing the stream down.
                break;
            }
            LOGE("AAudio write failed (handle=%d): %s",
                 static_cast<int>(handle), resultName(written));
            return -1;
        }
        if (written == 0) {
            break;
        }
        framesWritten += written;
    }
    return static_cast<jint>(framesWritten * frameBytes);
}

JNIEXPORT void JNICALL
Java_com_zyz4_gkme_audio_AudioNative_nativeClose(JNIEnv *env, jobject thiz, jint handle) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    closeLocked(static_cast<int>(handle));
}

JNIEXPORT void JNICALL
Java_com_zyz4_gkme_audio_AudioNative_nativeCloseAll(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    for (auto &entry : g_sinks) {
        if (entry.second.stream != nullptr) {
            AAudioStream_requestStop(entry.second.stream);
            AAudioStream_close(entry.second.stream);
        }
    }
    g_sinks.clear();
}

}  // extern "C"
