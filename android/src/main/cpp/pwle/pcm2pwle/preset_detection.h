#ifndef THIRD_PARTY_ANDROID_HAPTICS_TOOLS_PCM2PWLE_PRESET_DETECTION_H_
#define THIRD_PARTY_ANDROID_HAPTICS_TOOLS_PCM2PWLE_PRESET_DETECTION_H_

#include <utility>
#include <vector>

namespace pcm2pwle {

std::vector<std::pair<double, double>> DetectPresets(
    const std::vector<double>& ampEnvelope,
    const std::vector<double>& freqEnvelope, int rate,
    double pulseToNeighborRatio = 5.0, double ampRatioThreshold = 0.05,
    double presetFreqThreshold = 100.0, double minNeighborMs = 10.0);

std::vector<double> SilencePcmAtPresets(
    const std::vector<double>& pcm,
    const std::vector<std::pair<double, double>>& detectedPresets, int rate);

std::vector<std::pair<double, double>> CalculatePresetInsertPoints(
    const std::vector<double>& pcm,
    const std::vector<std::pair<double, double>>& detectedPresets, int rate);

}  // namespace pcm2pwle

#endif  // THIRD_PARTY_ANDROID_HAPTICS_TOOLS_PCM2PWLE_PRESET_DETECTION_H_
