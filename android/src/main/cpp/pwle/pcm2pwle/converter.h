#ifndef THIRD_PARTY_ANDROID_HAPTICS_TOOLS_PCM2PWLE_CONVERTER_H_
#define THIRD_PARTY_ANDROID_HAPTICS_TOOLS_PCM2PWLE_CONVERTER_H_

#include <string>
#include <string_view>
#include <utility>
#include <variant>
#include <vector>

#include "pcm2pwle/common_utils.h"

namespace pcm2pwle {

struct ConversionOptions {
  std::string pcmFile;
  std::string outputFile;
  std::string pwleType = "basic_pwle";
  double cropS = -1.0;
  double errorThreshold = 0.01;
  double pulseToNeighborRatio = 5.0;
  double ampRatioThreshold = 0.05;
  double presetFreqThreshold = 100.0;
  FreqProfileObject freqProfile = kPwleFreqProfile;
};

std::pair<std::vector<std::variant<BasicPwlePoint, AdvancedPwlePoint>>,
          std::vector<std::pair<double, double>>>
RunPcmToHapticsConversion(const std::vector<double>& pcmRaw, int rate,
                          std::string_view pwleType,
                          const ConversionOptions& options);

}  // namespace pcm2pwle

#endif  // THIRD_PARTY_ANDROID_HAPTICS_TOOLS_PCM2PWLE_CONVERTER_H_
