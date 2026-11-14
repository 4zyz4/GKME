#include "pcm2pwle/control_point_extraction.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <optional>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

#include "pcm2pwle/common_utils.h"

namespace pcm2pwle {

std::vector<ControlPoint> Rdp(
    const std::vector<std::pair<double, double>>& data, double epsilon,
    PointType pointType) {
  if (data.size() <= 2) {
    std::vector<ControlPoint> res;
    res.reserve(data.size());
    for (const auto& [t, v] : data) {
      ControlPoint cp;
      cp.time = t;
      cp.amplitude = (pointType == PointType::FREQUENCY)
                         ? std::nullopt
                         : std::optional<double>(v);
      cp.frequency = (pointType == PointType::AMPLITUDE)
                         ? std::nullopt
                         : std::optional<double>(v);
      cp.timeShift = 0.0;
      res.push_back(cp);
    }
    return res;
  }

  double dataMax = 0.0;
  for (const auto& [t, v] : data) {
    if (v > dataMax) dataMax = v;
  }
  double normY = dataMax > 0.0 ? dataMax : 1.0;

  std::vector<std::pair<double, double>> points(data.size());
  for (size_t i = 0; i < data.size(); ++i) {
    points[i] = {data[i].first / 1000.0, data[i].second / normY};
  }

  std::vector<int> indices = {0, static_cast<int>(data.size()) - 1};

  auto rdpRecursive = [&](auto& self, int startIndex, int endIndex) -> void {
    double maxDist = 0.0;
    int indexOfMaxDist = 0;

    auto p1 = points[startIndex];
    auto p2 = points[endIndex];

    double lineVecX = p2.first - p1.first;
    double lineVecY = p2.second - p1.second;
    double lineLenSq = lineVecX * lineVecX + lineVecY * lineVecY;

    if (lineLenSq == 0.0) {
      for (int i = startIndex + 1; i < endIndex; ++i) {
        double dx = points[i].first - p1.first;
        double dy = points[i].second - p1.second;
        double dist = std::sqrt(dx * dx + dy * dy);
        if (dist > maxDist) {
          maxDist = dist;
          indexOfMaxDist = i;
        }
      }
    } else {
      for (int i = startIndex + 1; i < endIndex; ++i) {
        double pointVecX = points[i].first - p1.first;
        double pointVecY = points[i].second - p1.second;
        double t = (pointVecX * lineVecX + pointVecY * lineVecY) / lineLenSq;
        double closestX = p1.first;
        double closestY = p1.second;
        if (t < 0.0) {
          closestX = p1.first;
          closestY = p1.second;
        } else if (t > 1.0) {
          closestX = p2.first;
          closestY = p2.second;
        } else {
          closestX = p1.first + t * lineVecX;
          closestY = p1.second + t * lineVecY;
        }
        double dx = points[i].first - closestX;
        double dy = points[i].second - closestY;
        double dist = std::sqrt(dx * dx + dy * dy);
        if (dist > maxDist) {
          maxDist = dist;
          indexOfMaxDist = i;
        }
      }
    }

    if (maxDist > epsilon) {
      self(self, startIndex, indexOfMaxDist);
      indices.push_back(indexOfMaxDist);
      self(self, indexOfMaxDist, endIndex);
    }
  };

  rdpRecursive(rdpRecursive, 0, static_cast<int>(points.size()) - 1);
  std::sort(indices.begin(), indices.end());
  indices.erase(std::unique(indices.begin(), indices.end()), indices.end());

  std::vector<ControlPoint> result;
  result.reserve(indices.size());
  for (int idx : indices) {
    ControlPoint cp;
    cp.time = data[idx].first;
    cp.amplitude = (pointType == PointType::FREQUENCY)
                       ? std::nullopt
                       : std::optional<double>(data[idx].second);
    cp.frequency = (pointType == PointType::AMPLITUDE)
                       ? std::nullopt
                       : std::optional<double>(data[idx].second);
    cp.timeShift = 0.0;
    result.push_back(cp);
  }
  return result;
}

bool IsWithinJndDb(double a1, double a2, double jndDb, double c) {
  double clampedA1 = std::max(a1, 0.01);
  double clampedA2 = std::max(a2, 0.01);
  double db = c * std::abs(std::log10(clampedA2 / clampedA1));
  return db <= jndDb;
}

bool RemoveControlPoints(const ControlPoint& currentPointRdp,
                         const ControlPoint& lastKeptPoint,
                         const ControlPoint& nextRdpPoint,
                         std::string_view pwleType) {
  if (!currentPointRdp.amplitude.has_value()) {
    return false;
  }

  if (pwleType == "advanced_pwle" || pwleType == "advanced") {
    if (!currentPointRdp.frequency.has_value()) {
      double curAmp = currentPointRdp.amplitude.value();
      double lastAmp = lastKeptPoint.amplitude.value_or(0.0);
      double nextAmp = nextRdpPoint.amplitude.value_or(0.0);
      if (IsWithinJndDb(curAmp, lastAmp, kAmplitudeFrequencyJnd, 20.0) &&
          IsWithinJndDb(curAmp, nextAmp, kAmplitudeFrequencyJnd, 20.0)) {
        return true;
      }
      if (IsWithinJndDb(curAmp, lastAmp, kAmplitudeFrequencyJnd, 20.0) &&
          currentPointRdp.time - lastKeptPoint.time <= kMinDuration / 2.0) {
        return true;
      }
      return false;
    } else {
      double curPInt = PerceivedIntensity(currentPointRdp.amplitude.value(),
                                          currentPointRdp.frequency.value());
      double lastPInt =
          PerceivedIntensity(lastKeptPoint.amplitude.value_or(0.0),
                             lastKeptPoint.frequency.value_or(0.0));
      double nextPInt =
          PerceivedIntensity(nextRdpPoint.amplitude.value_or(0.0),
                             nextRdpPoint.frequency.value_or(0.0));
      if (IsWithinJndDb(curPInt, lastPInt, kIntensityJnd, 10.0) &&
          IsWithinJndDb(curPInt, nextPInt, kIntensityJnd, 10.0)) {
        return true;
      }
      if (IsWithinJndDb(curPInt, lastPInt, kIntensityJnd, 10.0) &&
          currentPointRdp.time - lastKeptPoint.time <= kMinDuration / 2.0) {
        return true;
      }
      return false;
    }
  } else {
    double curAmp = currentPointRdp.amplitude.value();
    double lastAmp = lastKeptPoint.amplitude.value_or(0.0);
    double nextAmp = nextRdpPoint.amplitude.value_or(0.0);
    if (IsWithinJndDb(curAmp, lastAmp, kIntensityJnd, 10.0) &&
        IsWithinJndDb(curAmp, nextAmp, kIntensityJnd, 10.0)) {
      return true;
    }
    if (IsWithinJndDb(curAmp, lastAmp, kIntensityJnd, 10.0) &&
        currentPointRdp.time - lastKeptPoint.time <= kMinDuration / 2.0) {
      return true;
    }
    return false;
  }
}

std::vector<ControlPoint> RdpWithMinDistance(
    const std::vector<ControlPoint>& rdpPoints, std::string_view pwleType) {
  if (kMinDuration <= 0.0 || rdpPoints.size() <= 1) {
    return rdpPoints;
  }

  std::vector<ControlPoint> finalRdpPoints = {rdpPoints[0]};

  for (size_t i = 1; i < rdpPoints.size(); ++i) {
    ControlPoint currentPointRdp = rdpPoints[i];
    const ControlPoint& lastKeptPoint = finalRdpPoints.back();

    double durationCurToLastKept = currentPointRdp.time - lastKeptPoint.time;
    if (durationCurToLastKept < kMinDuration - 1e-6) {
      if (i + 1 < rdpPoints.size()) {
        const ControlPoint& nextRdpPoint = rdpPoints[i + 1];
        bool removePoint = RemoveControlPoints(currentPointRdp, lastKeptPoint,
                                               nextRdpPoint, pwleType);
        if (!removePoint) {
          double newXCurrent = lastKeptPoint.time + kMinDuration;
          ControlPoint modifiedPoint = currentPointRdp;
          modifiedPoint.time = newXCurrent;
          double timeShift = newXCurrent - currentPointRdp.time;
          modifiedPoint.timeShift += timeShift;
          finalRdpPoints.push_back(modifiedPoint);
        }
      }
    } else {
      finalRdpPoints.push_back(currentPointRdp);
    }
  }

  const auto& lastOriginal = rdpPoints.back();
  const auto& lastKept = finalRdpPoints.back();
  if (lastKept.time != lastOriginal.time) {
    ControlPoint pointToAdd = lastOriginal;
    if (pointToAdd.time - lastKept.time < kMinDuration) {
      pointToAdd.time = lastKept.time + kMinDuration;
    }
    finalRdpPoints.push_back(pointToAdd);
  }

  return finalRdpPoints;
}

double Interp1d(const std::vector<std::pair<double, double>>& grid,
                double targetTime) {
  if (grid.empty()) return 0.0;
  if (grid.size() == 1) return grid.front().second;

  if (targetTime <= grid.front().first) {
    double x0 = grid[0].first;
    double x1 = grid[1].first;
    double y0 = grid[0].second;
    double y1 = grid[1].second;
    double denom = (x1 - x0) != 0.0 ? (x1 - x0) : 1.0;
    double t = (targetTime - x0) / denom;
    return y0 + t * (y1 - y0);
  }
  if (targetTime >= grid.back().first) {
    size_t last = grid.size() - 1;
    double x0 = grid[last - 1].first;
    double x1 = grid[last].first;
    double y0 = grid[last - 1].second;
    double y1 = grid[last].second;
    double denom = (x1 - x0) != 0.0 ? (x1 - x0) : 1.0;
    double t = (targetTime - x0) / denom;
    return y0 + t * (y1 - y0);
  }

  int low = 0;
  int high = static_cast<int>(grid.size()) - 1;
  while (low <= high) {
    int mid = (low + high) / 2;
    if (grid[mid].first == targetTime) return grid[mid].second;
    if (grid[mid].first < targetTime) {
      low = mid + 1;
    } else {
      high = mid - 1;
    }
  }
  int i0 = std::max(0, high);
  int i1 = std::min(static_cast<int>(grid.size()) - 1, low);
  if (i0 == i1) return grid[i0].second;
  double denom = (grid[i1].first - grid[i0].first) != 0.0
                     ? (grid[i1].first - grid[i0].first)
                     : 1.0;
  double t = (targetTime - grid[i0].first) / denom;
  return grid[i0].second + t * (grid[i1].second - grid[i0].second);
}

std::vector<ControlPoint> CombineAmpFreqPointsSelective(
    const std::vector<ControlPoint>& ampPoints,
    const std::vector<std::pair<double, double>>& ampFitted,
    const std::vector<ControlPoint>& freqPoints,
    const std::vector<std::pair<double, double>>& freqFitted,
    std::string_view pwleType) {
  std::vector<ControlPoint> combinedPoints;
  combinedPoints.reserve(ampPoints.size() + freqPoints.size());
  for (const auto& p : ampPoints) {
    ControlPoint cp;
    cp.time = p.time;
    cp.frequency = std::max(0.0, Interp1d(freqFitted, p.time));
    cp.amplitude = p.amplitude;
    cp.timeShift = p.timeShift;
    combinedPoints.push_back(cp);
  }

  if (freqPoints.size() > 2) {
    for (size_t i = 1; i < freqPoints.size() - 1; ++i) {
      const auto& currentFreqPoint = freqPoints[i];
      const auto& prevFreqPoint = freqPoints[i - 1];
      const auto& nextFreqPoint = freqPoints[i + 1];

      double curFreq = currentFreqPoint.frequency.value_or(0.0);
      double prevFreq = prevFreqPoint.frequency.value_or(0.0);
      double nextFreq = nextFreqPoint.frequency.value_or(0.0);
      double curTime = currentFreqPoint.time;

      if (!IsWithinJndDb(curFreq, nextFreq, kAmplitudeFrequencyJnd, 20.0) ||
          !IsWithinJndDb(curFreq, prevFreq, kAmplitudeFrequencyJnd, 20.0)) {
        std::vector<ControlPoint> keptPointsBefore;
        std::vector<ControlPoint> keptPointsAfter;
        for (const auto& p : combinedPoints) {
          if (p.time <= curTime) keptPointsBefore.push_back(p);
          if (p.time >= curTime) keptPointsAfter.push_back(p);
        }

        if (!keptPointsBefore.empty() && !keptPointsAfter.empty()) {
          const auto& prevKeptPoint = keptPointsBefore.back();
          const auto& nextKeptPoint = keptPointsAfter.front();

          double curAmp = std::max(0.0, Interp1d(ampFitted, curTime));
          double prevKeptFreq =
              std::max(0.0, Interp1d(freqFitted, prevKeptPoint.time));

          double curFreqPInt = curAmp;
          double prevKeptPInt = prevKeptPoint.amplitude.value_or(0.0);
          if (pwleType == "advanced_pwle" || pwleType == "advanced") {
            curFreqPInt = PerceivedIntensity(curAmp, curFreq);
            prevKeptPInt = PerceivedIntensity(
                prevKeptPoint.amplitude.value_or(0.0), prevKeptFreq);
          }

          if (IsWithinJndDb(curFreqPInt, prevKeptPInt, kIntensityJnd, 10.0) &&
              IsWithinJndDb(curFreq, nextFreq, kAmplitudeFrequencyJnd * 2.0,
                            20.0) &&
              IsWithinJndDb(curFreq, prevFreq, kAmplitudeFrequencyJnd * 2.0,
                            20.0) &&
              curTime - prevKeptPoint.time > 0.0 &&
              curTime - prevKeptPoint.time < kMinDuration / 2.0) {
            continue;
          }

          if (nextKeptPoint.time - prevKeptPoint.time >= kMinDuration * 2.0) {
            double adjustedTime = curTime;
            if (adjustedTime - prevKeptPoint.time < kMinDuration) {
              adjustedTime = prevKeptPoint.time + kMinDuration;
              curAmp = Interp1d(ampFitted, adjustedTime);
            }
            if (nextKeptPoint.time - adjustedTime < kMinDuration) {
              adjustedTime = nextKeptPoint.time - kMinDuration;
              curAmp = Interp1d(ampFitted, adjustedTime);
            }

            double f = std::max(0.0, Interp1d(freqFitted, adjustedTime));
            double a = std::max(0.0, Interp1d(ampFitted, adjustedTime));

            ControlPoint newPt;
            newPt.time = adjustedTime;
            newPt.frequency = f;
            newPt.amplitude = a;
            newPt.timeShift = adjustedTime - curTime;
            combinedPoints.push_back(newPt);
            std::sort(combinedPoints.begin(), combinedPoints.end(),
                      [](const ControlPoint& aPt, const ControlPoint& bPt) {
                        return aPt.time < bPt.time;
                      });
          }
        }
      }
    }
  }

  std::vector<ControlPoint> uniquePoints;
  std::unordered_map<double, bool> seenTimes;
  for (const auto& p : combinedPoints) {
    if (!seenTimes[p.time]) {
      seenTimes[p.time] = true;
      uniquePoints.push_back(p);
    }
  }
  return uniquePoints;
}

std::vector<ControlPoint> ExtractControlPoints(
    const std::vector<double>& ampEnvelope,
    const std::vector<double>& freqEnvelope, int rate,
    std::string_view pwleType, double errorThreshold) {
  auto [ampFittedDownsampled, freqFittedDownsampled] =
      PreprocessEnvelopes(ampEnvelope, freqEnvelope, rate);

  auto allAmpPoints =
      Rdp(ampFittedDownsampled, errorThreshold, PointType::AMPLITUDE);
  auto allFreqPoints =
      Rdp(freqFittedDownsampled, errorThreshold, PointType::FREQUENCY);

  auto ampPoints = RdpWithMinDistance(allAmpPoints, pwleType);

  auto combinedPoints = CombineAmpFreqPointsSelective(
      ampPoints, ampFittedDownsampled, allFreqPoints, freqFittedDownsampled,
      pwleType);

  auto controlPoints =
      ZeroAmplitudeAtZeroFrequency(combinedPoints, freqFittedDownsampled);
  controlPoints = SetFirstLastPointAmpToZero(controlPoints);

  RejectInvalidWaveform(controlPoints, 100.0, 25.0);

  return controlPoints;
}

}  // namespace pcm2pwle
