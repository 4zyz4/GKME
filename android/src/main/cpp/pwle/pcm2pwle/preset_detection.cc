#include "pcm2pwle/preset_detection.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>
#include <utility>
#include <vector>

#include "pcm2pwle/common_utils.h"

namespace pcm2pwle {

std::vector<std::pair<double, double>> DetectPresets(
    const std::vector<double>& ampEnvelope,
    const std::vector<double>& freqEnvelope, int rate,
    double pulseToNeighborRatio, double ampRatioThreshold,
    double presetFreqThreshold, double minNeighborMs) {
  int n = ampEnvelope.size();
  if (n == 0) return {};

  std::vector<double> time(n);
  std::vector<double> amp = ampEnvelope;
  std::vector<double> freq = freqEnvelope;
  for (size_t i = 0; i < ampEnvelope.size(); ++i) {
    time[i] = (static_cast<double>(i) / rate) * 1000.0;
  }
  amp[0] = 0.0;
  freq[0] = 0.0;

  double maxAmp = 0.0;
  for (double val : amp) {
    if (val > maxAmp) maxAmp = val;
  }
  double ampThreshold = ampRatioThreshold * maxAmp;
  double minPulseDurationMs = 2.0;
  double maxPulseDurationMs = 20.0;

  std::vector<std::pair<double, double>> detectedPulses;
  bool inPotentialPulse = false;
  int pulseStartIndex = -1;

  for (size_t i = 0; i < ampEnvelope.size(); ++i) {
    if (amp[i] > ampThreshold && freq[i] > presetFreqThreshold) {
      if (!inPotentialPulse) {
        inPotentialPulse = true;
        pulseStartIndex = i;
      }
    } else {
      if (inPotentialPulse) {
        inPotentialPulse = false;
        int pulseEndIndex = i - 1;

        double startTime = time[pulseStartIndex];
        double endTime = time[pulseEndIndex];
        double durationMs = endTime - startTime;

        if (durationMs >= minPulseDurationMs &&
            durationMs <= maxPulseDurationMs) {
          double pAmpMax = -std::numeric_limits<double>::infinity();
          double pAmpSum = 0.0;
          int pAmpCount = 0;
          double pFreqMax = -std::numeric_limits<double>::infinity();

          for (int j = pulseStartIndex; j <= pulseEndIndex; ++j) {
            if (amp[j] > pAmpMax) pAmpMax = amp[j];
            pAmpSum += amp[j];
            pAmpCount++;
            if (freq[j] > pFreqMax) pFreqMax = freq[j];
          }
          double ampMean = pAmpCount > 0 ? (pAmpSum / pAmpCount) : 0.0;

          double ampLeftMin = std::numeric_limits<double>::infinity();
          double ampLeftSum = 0.0;
          int ampLeftCount = 0;
          double freqLeftMin = std::numeric_limits<double>::infinity();

          double leftWindowStart = std::max(0.0, startTime - minNeighborMs);
          for (int j = 0; j <= pulseStartIndex; ++j) {
            if (time[j] >= leftWindowStart) {
              if (amp[j] < ampLeftMin) ampLeftMin = amp[j];
              ampLeftSum += amp[j];
              ampLeftCount++;
              if (freq[j] < freqLeftMin) freqLeftMin = freq[j];
            }
          }
          if (ampLeftMin == std::numeric_limits<double>::infinity())
            ampLeftMin = 0.0;
          ampLeftMin = std::max(0.0, ampLeftMin);
          if (freqLeftMin == std::numeric_limits<double>::infinity())
            freqLeftMin = 0.0;
          freqLeftMin = std::max(0.0, freqLeftMin);
          double ampLeftMean =
              ampLeftCount > 0 ? (ampLeftSum / ampLeftCount) : 0.0;

          double ampRightMin = std::numeric_limits<double>::infinity();
          double ampRightSum = 0.0;
          int ampRightCount = 0;
          double freqRightMin = std::numeric_limits<double>::infinity();

          double rightWindowEnd =
              std::min(time.back(), endTime + minNeighborMs);
          for (int j = pulseEndIndex; j < ampEnvelope.size(); ++j) {
            if (time[j] <= rightWindowEnd) {
              if (amp[j] < ampRightMin) ampRightMin = amp[j];
              ampRightSum += amp[j];
              ampRightCount++;
              if (freq[j] < freqRightMin) freqRightMin = freq[j];
            }
          }
          if (ampRightMin == std::numeric_limits<double>::infinity())
            ampRightMin = 0.0;
          ampRightMin = std::max(0.0, ampRightMin);
          if (freqRightMin == std::numeric_limits<double>::infinity())
            freqRightMin = 0.0;
          freqRightMin = std::max(0.0, freqRightMin);
          double ampRightMean =
              ampRightCount > 0 ? (ampRightSum / ampRightCount) : 0.0;

          bool ampLeftCheck =
              (ampLeftMin == 0.0) ||
              (pAmpMax / (ampLeftMin != 0.0 ? ampLeftMin : 1e-6) >
               pulseToNeighborRatio);
          bool ampRightCheck =
              (ampRightMin == 0.0) ||
              (pAmpMax / (ampRightMin != 0.0 ? ampRightMin : 1e-6) >
               pulseToNeighborRatio);
          bool freqLeftCheck =
              (freqLeftMin == 0.0) ||
              (pFreqMax / (freqLeftMin != 0.0 ? freqLeftMin : 1e-6) >
               pulseToNeighborRatio);
          bool freqRightCheck =
              (freqRightMin == 0.0) ||
              (pFreqMax / (freqRightMin != 0.0 ? freqRightMin : 1e-6) >
               pulseToNeighborRatio);

          if (ampLeftCheck && ampRightCheck) {
            if (freqLeftCheck || freqRightCheck) {
              if (ampMean > ampLeftMean && ampMean > ampRightMean) {
                detectedPulses.push_back({startTime, endTime});
              }
            }
          }
        }
      }
    }
  }

  return detectedPulses;
}

std::vector<double> SilencePcmAtPresets(
    const std::vector<double>& pcm,
    const std::vector<std::pair<double, double>>& detectedPresets, int rate) {
  std::vector<double> modifiedPcm = pcm;
  for (const auto& [startTime, endTime] : detectedPresets) {
    int startSample = static_cast<int>((startTime / 1000.0) * rate);
    int endSample = static_cast<int>((endTime / 1000.0) * rate);
    for (int i = std::max(0, startSample);
         i <= std::min(static_cast<int>(modifiedPcm.size()) - 1, endSample);
         ++i) {
      modifiedPcm[i] = 0.0;
    }
  }

  double maxAbs = 0.0;
  for (double val : pcm) {
    double absVal = std::abs(val);
    if (absVal > maxAbs) {
      maxAbs = absVal;
    }
  }

  auto chunks =
      ZeroOutLowAmplitudeChunks(modifiedPcm, rate, 0.05, 20.0, maxAbs);
  for (const auto& chunk : chunks) {
    for (int i = chunk.first;
         i < chunk.second && i < static_cast<int>(modifiedPcm.size()); ++i) {
      modifiedPcm[i] = 0.0;
    }
  }

  return modifiedPcm;
}

std::vector<std::pair<double, double>> CalculatePresetInsertPoints(
    const std::vector<double>& pcm,
    const std::vector<std::pair<double, double>>& detectedPresets, int rate) {
  double maxAbs = 0.0;
  for (double val : pcm) {
    double absVal = std::abs(val);
    if (absVal > maxAbs) {
      maxAbs = absVal;
    }
  }
  if (maxAbs == 0.0) maxAbs = 1.0;

  std::vector<std::pair<double, double>> presetInsertPoints;
  for (const auto& [startTime, endTime] : detectedPresets) {
    int startSample = static_cast<int>((startTime / 1000.0) * rate);
    int endSample = static_cast<int>((endTime / 1000.0) * rate);
    double pulseMax = 0.0;
    for (int i = std::max(0, startSample);
         i <= std::min(static_cast<int>(pcm.size()) - 1, endSample); ++i) {
      double absVal = std::abs(pcm[i]);
      if (absVal > pulseMax) {
        pulseMax = absVal;
      }
    }
    presetInsertPoints.push_back({startTime, pulseMax / maxAbs});
  }

  return presetInsertPoints;
}

}  // namespace pcm2pwle
