#ifndef THIRD_PARTY_ANDROID_HAPTICS_TOOLS_PCM2PWLE_FREQUENCY_AMPLITUDE_EXTRACTION_H_
#define THIRD_PARTY_ANDROID_HAPTICS_TOOLS_PCM2PWLE_FREQUENCY_AMPLITUDE_EXTRACTION_H_

#include <optional>
#include <utility>
#include <vector>

namespace pcm2pwle {

std::vector<double> VibDataPreprocess(
    const std::vector<double>& data, int rate,
    double preprocessLowpassCutoff = 500.0,
    std::optional<double> cropS = std::nullopt);

std::pair<std::vector<double>, std::vector<double>> ExtractVibAmpFreq(
    const std::vector<double>& data, int rate,
    double envelopeLowpassCutoff = 100.0);

}  // namespace pcm2pwle

#endif  // THIRD_PARTY_ANDROID_HAPTICS_TOOLS_PCM2PWLE_FREQUENCY_AMPLITUDE_EXTRACTION_H_
