#ifndef THIRD_PARTY_ANDROID_HAPTICS_TOOLS_PCM2PWLE_COMMON_UTILS_H_
#define THIRD_PARTY_ANDROID_HAPTICS_TOOLS_PCM2PWLE_COMMON_UTILS_H_

#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <variant>
#include <vector>

namespace pcm2pwle {

inline constexpr double kMinDuration = 20.0;
inline constexpr double kMinDurationMs = kMinDuration;
inline constexpr double kJndMultiple = kMinDuration / 10.0;
inline constexpr double kAmplitudeFrequencyJnd = 1.5 * kJndMultiple;
inline constexpr double kIntensityJnd = 1.0 * kJndMultiple;
inline constexpr double kAmpLowerLimit = 0.0;
inline constexpr double kAmpUpperLimit = 1.0;

struct FreqProfileObject {
  double minFrequencyHz;
  double resonantFrequencyHz;
  double maxFrequencyHz;
};

inline constexpr FreqProfileObject kPwleFreqProfile = {50.0, 136.0, 174.0};

enum class PointType {
  FREQUENCY,
  AMPLITUDE,
  COMBINED,
};

struct ControlPoint {
  double time;
  std::optional<double> amplitude;
  std::optional<double> frequency;
  double timeShift = 0.0;
};

struct BasicPwlePoint {
  double intensity;
  double sharpness;
  double duration;
};

struct AdvancedPwlePoint {
  double amplitude;
  double frequency;
  double duration;
};

struct PreprocessResult {
  std::vector<std::pair<double, double>> ampFittedDownsampled;
  std::vector<std::pair<double, double>> freqFittedDownsampled;
};

double PerceivedIntensity(double amp, double freq);

std::vector<std::pair<int, int>> ZeroOutLowAmplitudeChunks(
    const std::vector<double>& data, double rate,
    double amplitudeThresholdFraction = 0.05, double durationThresholdMs = 20.0,
    std::optional<double> ampMax = std::nullopt);

PreprocessResult PreprocessEnvelopes(const std::vector<double>& ampEnvelope,
                                     const std::vector<double>& freqEnvelope,
                                     double rate);

std::vector<ControlPoint> ZeroAmplitudeAtZeroFrequency(
    const std::vector<ControlPoint>& controlPoints,
    const std::vector<std::pair<double, double>>& envelope);

std::vector<ControlPoint> SetFirstLastPointAmpToZero(
    const std::vector<ControlPoint>& controlPoints,
    double ampToKeepRatio = 0.05);

void RejectInvalidWaveform(const std::vector<ControlPoint>& controlPoints,
                           double timeShiftThreshold = 100.0,
                           double meanFreqThreshold = 25.0);

void RejectInvalidBeatingWaveform(double beatingFreq);

double FreqToSharpness(
    double freq, const FreqProfileObject& freqProfile = kPwleFreqProfile);

double SharpnessToFreq(
    double sharpness, const FreqProfileObject& freqProfile = kPwleFreqProfile);

std::vector<AdvancedPwlePoint> GenerateAdvancedPwle(
    const std::vector<ControlPoint>& controlPoints);

std::vector<BasicPwlePoint> GenerateBasicPwle(
    const std::vector<ControlPoint>& controlPoints,
    const FreqProfileObject& freqProfile = kPwleFreqProfile);

struct PresetEvent {
  double time;
  std::string presetEnum;
  double intensity;
};

std::string GeneratePwleJson(
    const std::vector<std::variant<BasicPwlePoint, AdvancedPwlePoint>>& points,
    std::string_view pwleType, const std::string& outputPath,
    std::string_view jsonVersion = "2.0",
    const std::vector<std::pair<double, double>>& presetInsertPoints = {});

bool ParseHapticsJson(const std::string& jsonPath,
                      std::vector<std::vector<ControlPoint>>* envelopes,
                      std::string* pwleType, std::vector<PresetEvent>* presets);

}  // namespace pcm2pwle

#endif  // THIRD_PARTY_ANDROID_HAPTICS_TOOLS_PCM2PWLE_COMMON_UTILS_H_
