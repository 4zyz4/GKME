#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <optional>
#include <string_view>
#include <variant>
#include <vector>

#include "pcm2pwle/converter.h"

#define PWLE_TAG "GKME_Pwle"
#define PLOGW(...) __android_log_print(ANDROID_LOG_WARN, PWLE_TAG, __VA_ARGS__)

namespace {

constexpr int kTriple = 3;

float clamp_frequency(double hz, float min_hz, float resonant_hz, float max_hz) {
    if (!std::isfinite(hz) || hz <= 0.0) {
        hz = std::isfinite(resonant_hz) && resonant_hz > 0.0 ? resonant_hz : min_hz;
    }
    if (hz < min_hz) hz = min_hz;
    if (hz > max_hz) hz = max_hz;
    return static_cast<float>(hz);
}

}  // namespace

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_zyz4_gkme_haptic_PwleNative_nativeConvert(
    JNIEnv* env,
    jclass /*clazz*/,
    jfloatArray samples,
    jint sample_rate,
    jfloat min_hz,
    jfloat resonant_hz,
    jfloat max_hz) {
    if (samples == nullptr || sample_rate <= 0 || min_hz <= 0.0f ||
        max_hz <= min_hz) {
        return nullptr;
    }
    const jsize count = env->GetArrayLength(samples);
    if (count < 64) {
        return nullptr;
    }

    std::vector<jfloat> raw(static_cast<size_t>(count));
    env->GetFloatArrayRegion(samples, 0, count, raw.data());

    // pcm2pwle normalizes internally; remember the chunk peak so the streamed
    // effects keep their relative loudness across chunks.
    double peak = 0.0;
    std::vector<double> pcm(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        const double v = static_cast<double>(raw[static_cast<size_t>(i)]);
        pcm[static_cast<size_t>(i)] = v;
        peak = std::max(peak, std::abs(v));
    }
    if (peak <= 1e-5) {
        return nullptr;
    }

    pcm2pwle::ConversionOptions options;
    options.pwleType = "advanced_pwle";
    // The toolkit replaces detected transients with CLICK/THUD presets and then
    // silences them in the PCM. We only consume the PWLE envelope here (there is
    // no portable way to combine primitives with an envelope through the public
    // builder), so disable preset detection to keep transients in the envelope.
    options.ampRatioThreshold = 2.0;
    options.freqProfile.minFrequencyHz = min_hz;
    options.freqProfile.resonantFrequencyHz =
        std::isfinite(resonant_hz) && resonant_hz > 0.0f ? resonant_hz : min_hz;
    options.freqProfile.maxFrequencyHz = max_hz;

    std::vector<std::variant<pcm2pwle::BasicPwlePoint, pcm2pwle::AdvancedPwlePoint>>
        converted;
    try {
        converted = pcm2pwle::RunPcmToHapticsConversion(
                        pcm, sample_rate, std::string_view("advanced_pwle"), options)
                        .first;
    } catch (...) {
        PLOGW("pcm2pwle conversion failed");
        return nullptr;
    }
    if (converted.size() < 2) {
        return nullptr;
    }

    std::vector<float> out;
    out.reserve(converted.size() * kTriple);
    for (size_t i = 0; i < converted.size(); ++i) {
        const auto* point =
            std::get_if<pcm2pwle::AdvancedPwlePoint>(&converted[i]);
        if (point == nullptr) {
            return nullptr;
        }
        double amplitude = point->amplitude * peak;
        amplitude = std::clamp(amplitude, 0.0, 1.0);
        // The first point is the initial state (duration 0) and is used by the
        // Kotlin side as the builder's initial frequency.
        double duration = i == 0 ? 0.0 : std::max(1.0, point->duration);
        out.push_back(static_cast<float>(amplitude));
        out.push_back(clamp_frequency(point->frequency, min_hz, resonant_hz, max_hz));
        out.push_back(static_cast<float>(duration));
    }

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(out.size()));
    if (result == nullptr) {
        return nullptr;
    }
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}
