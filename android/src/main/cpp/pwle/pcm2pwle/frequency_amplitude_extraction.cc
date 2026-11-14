#include "pcm2pwle/frequency_amplitude_extraction.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <optional>
#include <utility>
#include <vector>

#include "pcm2pwle/common_utils.h"
#include "pcm2pwle/dsp_utils.h"

namespace pcm2pwle {

std::vector<double> VibDataPreprocess(const std::vector<double>& data, int rate,
                                      double preprocessLowpassCutoff,
                                      std::optional<double> cropS) {
  if (data.empty()) return {};
  std::vector<double> working = data;

  if (preprocessLowpassCutoff > 0.0) {
    auto sos = ButterworthLowpassSos(5, preprocessLowpassCutoff, rate);
    working = Sosfiltfilt(sos, working);
  }

  if (cropS.has_value() && cropS.value() > 0.0) {
    size_t endIdx =
        std::min(working.size(), static_cast<size_t>(cropS.value() * rate));
    working.resize(endIdx);
  }

  double maxAbs = 0.0;
  for (double val : working) {
    double absVal = std::abs(val);
    if (absVal > maxAbs) {
      maxAbs = absVal;
    }
  }

  if (maxAbs > 0.0) {
    for (double& val : working) {
      val /= maxAbs;
    }
  }

  return working;
}

static std::pair<std::vector<double>, std::vector<double>> CalculateFreqEnv(
    const std::vector<double>& data, int rate, double envelopeLowpassCutoff) {
  int n = data.size();
  if (n == 0) return {{}, {}};

  std::vector<double> real, imag;
  Hilbert(data, &real, &imag);

  std::vector<double> ampEnvelopeRaw(n);
  std::vector<double> insPhase(n);
  for (size_t i = 0; i < data.size(); ++i) {
    ampEnvelopeRaw[i] = std::sqrt(real[i] * real[i] + imag[i] * imag[i]);
    insPhase[i] = std::atan2(imag[i], real[i]);
  }

  std::vector<double> unwrappedPhase = Unwrap(insPhase);
  std::vector<double> freqEnvelopeRaw(n);
  for (size_t i = 1; i < data.size(); ++i) {
    freqEnvelopeRaw[i] =
        ((unwrappedPhase[i] - unwrappedPhase[i - 1]) * rate) / (2.0 * M_PI);
  }
  freqEnvelopeRaw[0] = n > 1 ? freqEnvelopeRaw[1] : 0.0;

  double upperBound = 500.0;
  std::vector<int> validIndices;
  std::vector<double> validFreqs;
  for (size_t i = 0; i < data.size(); ++i) {
    double f = freqEnvelopeRaw[i];
    if (f >= 0.0 && f <= upperBound && !std::isnan(f) && std::isfinite(f)) {
      validIndices.push_back(i);
      validFreqs.push_back(f);
    }
  }

  if (!validIndices.empty() && validIndices.size() < static_cast<size_t>(n)) {
    size_t validIdx = 0;
    for (size_t i = 0; i < data.size(); ++i) {
      if (i <= validIndices.front()) {
        freqEnvelopeRaw[i] = validFreqs.front();
      } else if (i >= validIndices.back()) {
        freqEnvelopeRaw[i] = validFreqs.back();
      } else {
        while (validIdx + 1 < validIndices.size() &&
               validIndices[validIdx + 1] <= i) {
          validIdx++;
        }
        double x0 = validIndices[validIdx];
        double x1 = validIndices[validIdx + 1];
        double y0 = validFreqs[validIdx];
        double y1 = validFreqs[validIdx + 1];
        double t = (i - x0) / ((x1 - x0) != 0.0 ? (x1 - x0) : 1.0);
        freqEnvelopeRaw[i] = y0 + t * (y1 - y0);
      }
    }
  } else if (validIndices.empty()) {
    std::fill(freqEnvelopeRaw.begin(), freqEnvelopeRaw.end(), 0.0);
  }

  auto sos = ButterworthLowpassSos(5, envelopeLowpassCutoff, rate);
  std::vector<double> ampEnvelope = Sosfiltfilt(sos, ampEnvelopeRaw);
  std::vector<double> freqEnvelope = Sosfiltfilt(sos, freqEnvelopeRaw);

  return {ampEnvelope, freqEnvelope};
}

std::pair<std::vector<double>, std::vector<double>> ExtractVibAmpFreq(
    const std::vector<double>& data, int rate, double envelopeLowpassCutoff) {
  auto [ampEnvelope, freqEnvelope] =
      CalculateFreqEnv(data, rate, envelopeLowpassCutoff);

  auto chunks = ZeroOutLowAmplitudeChunks(data, rate, 0.01, 20.0);
  for (const auto& chunk : chunks) {
    for (int i = chunk.first;
         i < chunk.second && i < static_cast<int>(ampEnvelope.size()); ++i) {
      ampEnvelope[i] = 0.0;
      freqEnvelope[i] = 0.0;
    }
  }

  if (!ampEnvelope.empty()) {
    ampEnvelope.back() = 0.0;
    freqEnvelope.back() = 0.0;
  }

  return {ampEnvelope, freqEnvelope};
}

}  // namespace pcm2pwle
