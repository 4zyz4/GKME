#include "pcm2pwle/beating_detection.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>
#include <optional>
#include <string_view>
#include <utility>
#include <vector>

#include "pcm2pwle/common_utils.h"
#include "pcm2pwle/control_point_extraction.h"
#include "pcm2pwle/dsp_utils.h"

namespace pcm2pwle {

static std::vector<int> LocalMaxima1d(const std::vector<double>& x) {
  std::vector<int> midpoints;
  int n = x.size();
  for (size_t i = 1; i + 1 < x.size(); ++i) {
    if (x[i - 1] < x[i]) {
      int iAhead = i + 1;
      while (iAhead < n - 1 && x[iAhead] == x[i]) {
        iAhead++;
      }
      if (x[iAhead] < x[i]) {
        int left = i;
        int right = iAhead - 1;
        midpoints.push_back((left + right) / 2);
        i = iAhead;
      }
    }
  }
  return midpoints;
}

static std::vector<int> SelectByPeakDistance(
    const std::vector<int>& peaks, const std::vector<double>& priority,
    double distance) {
  if (peaks.empty()) return {};
  std::vector<int> order(peaks.size());
  for (size_t i = 0; i < peaks.size(); ++i) order[i] = i;
  std::stable_sort(order.begin(), order.end(),
                   [&](int a, int b) { return priority[a] > priority[b]; });

  std::vector<bool> keep(peaks.size(), true);
  for (size_t i = 0; i < order.size(); ++i) {
    int idx = order[i];
    if (!keep[idx]) continue;
    int peakPos = peaks[idx];
    for (size_t j = 0; j < peaks.size(); ++j) {
      if (keep[j] && j != static_cast<size_t>(idx) &&
          std::abs(peaks[j] - peakPos) < distance) {
        keep[j] = false;
      }
    }
  }

  std::vector<int> result;
  for (size_t i = 0; i < peaks.size(); ++i) {
    if (keep[i]) {
      result.push_back(peaks[i]);
    }
  }
  return result;
}

static std::pair<std::vector<int>, std::vector<double>> ComputeProminences(
    const std::vector<double>& x, const std::vector<int>& peaks,
    double minProminence) {
  std::vector<int> kept;
  std::vector<double> proms;
  for (int p : peaks) {
    int lb = p;
    while (lb > 0 && x[lb - 1] <= x[p]) {
      lb--;
    }
    double lmin = x[p];
    for (int j = lb; j <= p; ++j) {
      if (x[j] < lmin) lmin = x[j];
    }

    int rb = p;
    while (rb + 1 < x.size() && x[rb + 1] <= x[p]) {
      rb++;
    }
    double rmin = x[p];
    for (int j = p; j <= rb; ++j) {
      if (x[j] < rmin) rmin = x[j];
    }

    double prom = x[p] - std::max(lmin, rmin);
    if (prom >= minProminence) {
      kept.push_back(p);
      proms.push_back(prom);
    }
  }
  return {kept, proms};
}

static std::vector<int> FindPeaks(const std::vector<double>& x,
                                  std::optional<double> distance,
                                  std::optional<double> prominence) {
  auto peaks = LocalMaxima1d(x);
  if (distance.has_value() && distance.value() >= 1.0) {
    std::vector<double> priority(peaks.size());
    for (size_t i = 0; i < peaks.size(); ++i) {
      priority[i] = x[peaks[i]];
    }
    peaks = SelectByPeakDistance(peaks, priority, distance.value());
  }
  if (prominence.has_value()) {
    auto [kept, proms] = ComputeProminences(x, peaks, prominence.value());
    peaks = kept;
  }
  return peaks;
}

std::optional<std::pair<double, double>> IsBeating(
    const std::vector<double>& data, int rate, double peakRatioThreshold,
    double freqDiffThreshold, double peakEnergyRatioThreshold,
    double energyOutsidePeaksRatioThreshold) {
  int n = data.size();
  if (n == 0) return std::nullopt;

  auto fftOut = Rfft(data);
  int numFreqs = fftOut.size();
  std::vector<double> yf(numFreqs);
  std::vector<double> xf(numFreqs);
  double maxYf = 0.0;
  for (int i = 0; i < numFreqs; ++i) {
    yf[i] = std::abs(fftOut[i]);
    if (yf[i] > maxYf) maxYf = yf[i];
    xf[i] = (static_cast<double>(i) * rate) / static_cast<double>(n);
  }

  if (maxYf > 0.0) {
    for (int i = 0; i < numFreqs; ++i) {
      yf[i] /= maxYf;
    }
  }

  auto sos = ButterworthLowpassSos(5, 0.2, 2.0);
  yf = Sosfiltfilt(sos, yf);

  auto initialPeaks = LocalMaxima1d(yf);
  auto [peaks, prominences] = ComputeProminences(yf, initialPeaks, 0.0);

  if (peaks.size() < 2) {
    return std::nullopt;
  }

  std::vector<int> sortedOrder(peaks.size());
  for (size_t i = 0; i < peaks.size(); ++i) sortedOrder[i] = i;
  std::sort(sortedOrder.begin(), sortedOrder.end(),
            [&](int a, int b) { return prominences[a] > prominences[b]; });

  int peak1Idx = peaks[sortedOrder[0]];
  int peak2Idx = peaks[sortedOrder[1]];

  double freq1 = xf[peak1Idx];
  double freq2 = xf[peak2Idx];
  double fftBeatFreq = std::abs(freq1 - freq2);

  if (fftBeatFreq < 1.0 || fftBeatFreq > freqDiffThreshold) {
    return std::nullopt;
  }

  double peak1Amp = yf[peak1Idx];
  double peak2Amp = yf[peak2Idx];
  double peakRatio = peak1Amp / (peak2Amp != 0.0 ? peak2Amp : 1e-6);
  if (peakRatio > peakRatioThreshold) {
    return std::nullopt;
  }

  double spectrumEnergy = 0.0;
  for (int i = 0; i < numFreqs; ++i) {
    spectrumEnergy += yf[i] * yf[i];
  }
  if (spectrumEnergy == 0.0) return std::nullopt;

  double peakEnergy = peak1Amp * peak1Amp + peak2Amp * peak2Amp;
  if (peakEnergy / spectrumEnergy < peakEnergyRatioThreshold) {
    return std::nullopt;
  }

  double freqLeft = std::min(freq1, freq2) - 50.0;
  double freqRight = std::max(freq1, freq2) + 50.0;
  double energyOutsidePeaks = 0.0;
  for (int i = 0; i < numFreqs; ++i) {
    if (xf[i] < freqLeft || xf[i] > freqRight) {
      energyOutsidePeaks += yf[i] * yf[i];
    }
  }
  if (energyOutsidePeaks / spectrumEnergy > energyOutsidePeaksRatioThreshold) {
    return std::nullopt;
  }

  std::vector<double> real, imag;
  Hilbert(data, &real, &imag);
  std::vector<double> ampEnvelope(n);
  std::vector<double> insPhase(n);
  for (size_t i = 0; i < data.size(); ++i) {
    ampEnvelope[i] = std::sqrt(real[i] * real[i] + imag[i] * imag[i]);
    insPhase[i] = std::atan2(imag[i], real[i]);
  }
  std::vector<double> unwrapped = Unwrap(insPhase);
  std::vector<double> freqEnvelope(n);
  for (size_t i = 1; i < data.size(); ++i) {
    freqEnvelope[i] = ((unwrapped[i] - unwrapped[i - 1]) / (2.0 * M_PI)) * rate;
  }
  freqEnvelope[0] = n > 1 ? freqEnvelope[1] : 0.0;

  auto chunks = ZeroOutLowAmplitudeChunks(data, rate, 0.01, 20.0);
  for (const auto& chunk : chunks) {
    for (int i = chunk.first; i < chunk.second && i < data.size(); ++i) {
      ampEnvelope[i] = 0.0;
      freqEnvelope[i] = 0.0;
    }
  }

  double minDistance = (1.0 / fftBeatFreq) * rate * 0.95;
  double maxAmp = 0.0;
  for (size_t i = 0; i < data.size(); ++i) {
    if (std::abs(ampEnvelope[i]) > maxAmp) maxAmp = std::abs(ampEnvelope[i]);
  }
  double valleyProminence = maxAmp * 0.1;

  std::vector<double> negAmp(n);
  for (size_t i = 0; i < data.size(); ++i) negAmp[i] = -ampEnvelope[i];

  auto valleyIdxs = FindPeaks(negAmp, minDistance, valleyProminence);
  if (valleyIdxs.empty()) return std::nullopt;

  double valleyFreqSum = 0.0;
  double minAbsValleyFreq = std::numeric_limits<double>::infinity();
  for (int idx : valleyIdxs) {
    double absF = std::abs(freqEnvelope[idx]);
    valleyFreqSum += absF;
    if (absF < minAbsValleyFreq) minAbsValleyFreq = absF;
  }
  if (minAbsValleyFreq == 0.0) return std::nullopt;
  double meanValleyFreq = valleyFreqSum / valleyIdxs.size();

  std::vector<double> sortedAbsFreq(n);
  for (size_t i = 0; i < data.size(); ++i)
    sortedAbsFreq[i] = std::abs(freqEnvelope[i]);
  std::sort(sortedAbsFreq.begin(), sortedAbsFreq.end());
  double freqQ1 = sortedAbsFreq[static_cast<size_t>(n * 0.25)];
  double freqQ3 = sortedAbsFreq[static_cast<size_t>(n * 0.75)];
  double freqIqr = freqQ3 - freqQ1;
  double outlierFreq = freqQ3 + 1.5 * freqIqr;
  double avgFreq = (freq1 + freq2) / 2.0;
  double outlierFreqLow = avgFreq - 10.0;
  double outlierFreqHigh = std::min(avgFreq * 2.0, outlierFreq);

  if (meanValleyFreq > outlierFreqLow && meanValleyFreq < outlierFreqHigh) {
    return std::nullopt;
  }

  return std::make_pair(freq1, freq2);
}

std::vector<ControlPoint> ExtractBeatingControlPoints(
    const std::vector<double>& ampEnvelope,
    const std::vector<double>& freqEnvelope,
    const std::pair<double, double>& beatingFreqs, int rate,
    std::string_view pwleType, double errorThreshold) {
  double carrierFreq = (beatingFreqs.first + beatingFreqs.second) / 2.0;
  double beatingFreq = std::abs(beatingFreqs.first - beatingFreqs.second);

  auto [ampFittedDownsampled, freqFittedDownsampled] =
      PreprocessEnvelopes(ampEnvelope, freqEnvelope, rate);

  auto allAmpPoints =
      Rdp(ampFittedDownsampled, errorThreshold, PointType::AMPLITUDE);
  auto ampPoints = RdpWithMinDistance(allAmpPoints, pwleType);

  std::vector<ControlPoint> controlPoints;
  controlPoints.reserve(ampPoints.size());
  for (const auto& point : ampPoints) {
    ControlPoint cp;
    cp.time = point.time;
    cp.amplitude = point.amplitude;
    cp.frequency = carrierFreq;
    cp.timeShift = point.timeShift;
    controlPoints.push_back(cp);
  }

  controlPoints =
      ZeroAmplitudeAtZeroFrequency(controlPoints, freqFittedDownsampled);
  controlPoints = SetFirstLastPointAmpToZero(controlPoints);

  RejectInvalidWaveform(controlPoints, 100.0, 25.0);
  RejectInvalidBeatingWaveform(beatingFreq);

  return controlPoints;
}

}  // namespace pcm2pwle
