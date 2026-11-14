#ifndef THIRD_PARTY_ANDROID_HAPTICS_TOOLS_PCM2PWLE_BEATING_DETECTION_H_
#define THIRD_PARTY_ANDROID_HAPTICS_TOOLS_PCM2PWLE_BEATING_DETECTION_H_

#include <optional>
#include <string_view>
#include <utility>
#include <vector>

#include "pcm2pwle/common_utils.h"

namespace pcm2pwle {

std::optional<std::pair<double, double>> IsBeating(
    const std::vector<double>& data, int rate, double peakRatioThreshold = 2.0,
    double freqDiffThreshold = 60.0, double peakEnergyRatioThreshold = 0.1,
    double energyOutsidePeaksRatioThreshold = 0.1);

std::vector<ControlPoint> ExtractBeatingControlPoints(
    const std::vector<double>& ampEnvelope,
    const std::vector<double>& freqEnvelope,
    const std::pair<double, double>& beatingFreqs, int rate,
    std::string_view pwleType = "basic_pwle", double errorThreshold = 0.01);

}  // namespace pcm2pwle

#endif  // THIRD_PARTY_ANDROID_HAPTICS_TOOLS_PCM2PWLE_BEATING_DETECTION_H_
