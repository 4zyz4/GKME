#ifndef THIRD_PARTY_ANDROID_HAPTICS_TOOLS_PCM2PWLE_CONTROL_POINT_EXTRACTION_H_
#define THIRD_PARTY_ANDROID_HAPTICS_TOOLS_PCM2PWLE_CONTROL_POINT_EXTRACTION_H_

#include <string_view>
#include <utility>
#include <vector>

#include "pcm2pwle/common_utils.h"

namespace pcm2pwle {

std::vector<ControlPoint> Rdp(
    const std::vector<std::pair<double, double>>& data, double epsilon,
    PointType pointType);

bool IsWithinJndDb(double a1, double a2, double jndDb, double c = 20.0);

bool RemoveControlPoints(const ControlPoint& currentPointRdp,
                         const ControlPoint& lastKeptPoint,
                         const ControlPoint& nextRdpPoint,
                         std::string_view pwleType);

std::vector<ControlPoint> RdpWithMinDistance(
    const std::vector<ControlPoint>& rdpPoints, std::string_view pwleType);

double Interp1d(const std::vector<std::pair<double, double>>& grid,
                double targetTime);

std::vector<ControlPoint> CombineAmpFreqPointsSelective(
    const std::vector<ControlPoint>& ampPoints,
    const std::vector<std::pair<double, double>>& ampFitted,
    const std::vector<ControlPoint>& freqPoints,
    const std::vector<std::pair<double, double>>& freqFitted,
    std::string_view pwleType);

std::vector<ControlPoint> ExtractControlPoints(
    const std::vector<double>& ampEnvelope,
    const std::vector<double>& freqEnvelope, int rate,
    std::string_view pwleType = "basic_pwle", double errorThreshold = 0.01);

}  // namespace pcm2pwle

#endif  // THIRD_PARTY_ANDROID_HAPTICS_TOOLS_PCM2PWLE_CONTROL_POINT_EXTRACTION_H_
