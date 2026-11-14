#include "pcm2pwle/common_utils.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <iterator>
#include <optional>
#include <sstream>
#include <string>
#include <string_view>
#include <utility>
#include <variant>
#include <vector>

#include "absl/log/log.h"
#include "absl/strings/numbers.h"
#include "pcm2pwle/dsp_utils.h"

namespace pcm2pwle {

double PerceivedIntensity(double amp, double freq) {
  double f = freq;
  if (f < 50.0) {
    f = 50.0;
  }
  double fLog = std::log10(f);
  double k = 267.0 - 373.0 * fLog + 177.0 * std::pow(fLog, 2) -
             27.8 * std::pow(fLog, 3);
  double e =
      -2.28 + 8.04 * fLog - 5.48 * std::pow(fLog, 2) + 1.1 * std::pow(fLog, 3);
  return k * std::pow(std::max(0.0, amp), e);
}

std::vector<std::pair<int, int>> ZeroOutLowAmplitudeChunks(
    const std::vector<double>& data, double rate,
    double amplitudeThresholdFraction, double durationThresholdMs,
    std::optional<double> ampMax) {
  int n = data.size();
  if (n == 0) return {};
  double maxAbs = ampMax.value_or(0.0);
  if (maxAbs == 0.0) {
    for (size_t i = 0; i < data.size(); ++i) {
      double absVal = std::abs(data[i]);
      if (absVal > maxAbs) {
        maxAbs = absVal;
      }
    }
  }
  double threshold =
      amplitudeThresholdFraction * (maxAbs != 0.0 ? maxAbs : 1.0);
  int minSamples =
      static_cast<int>(std::floor((durationThresholdMs / 1000.0) * rate));

  std::vector<std::pair<int, int>> chunks;
  bool inSilence = false;
  int silenceStart = 0;
  for (size_t i = 0; i < data.size(); ++i) {
    bool isLow = std::abs(data[i]) < threshold;
    if (isLow && !inSilence) {
      inSilence = true;
      silenceStart = i;
    } else if (!isLow && inSilence) {
      inSilence = false;
      if (i - silenceStart >= minSamples) {
        chunks.push_back({silenceStart, static_cast<int>(i)});
      }
    }
  }
  if (inSilence && n - silenceStart >= minSamples) {
    chunks.push_back({silenceStart, n});
  }
  return chunks;
}

static std::vector<std::pair<double, double>> downsampleSignal(
    const std::vector<double>& data, double originalRate, int dsK) {
  double signalDuration =
      (static_cast<double>(data.size()) / originalRate) * 1000.0;
  std::vector<double> downsampled = Decimate(data, dsK);
  for (auto& val : downsampled) {
    if (val < 0.0) val = 0.0;
  }
  size_t m = downsampled.size();
  std::vector<std::pair<double, double>> result(m);
  for (size_t i = 0; i < m; ++i) {
    double t =
        (signalDuration / static_cast<double>(m)) * static_cast<double>(i);
    result[i] = {t, downsampled[i]};
  }
  return result;
}

PreprocessResult PreprocessEnvelopes(const std::vector<double>& ampEnvelope,
                                     const std::vector<double>& freqEnvelope,
                                     double rate) {
  int medianFilterK = static_cast<int>(std::floor((rate * 0.01) / 2.0)) * 2 + 1;
  std::vector<double> ampFitted = Medfilt(ampEnvelope, medianFilterK);
  if (!ampFitted.empty()) {
    ampFitted.front() = 0.0;
    ampFitted.back() = 0.0;
  }
  std::vector<double> freqFitted = Medfilt(freqEnvelope, medianFilterK);

  int dsK = std::max(1, static_cast<int>(std::floor(rate / 1000.0)));
  auto ampFittedDownsampled = downsampleSignal(ampFitted, rate, dsK);
  auto freqFittedDownsampled = downsampleSignal(freqFitted, rate, dsK);

  auto chunks = ZeroOutLowAmplitudeChunks(ampEnvelope, rate, 0.01, 10.0);
  for (const auto& chunk : chunks) {
    double startTime = (chunk.first / rate) * 1000.0;
    double endTime = (chunk.second / rate) * 1000.0;
    for (size_t j = 0; j < ampFittedDownsampled.size(); ++j) {
      double t = ampFittedDownsampled[j].first;
      if (t >= startTime && t <= endTime) {
        ampFittedDownsampled[j].second = 0.0;
        freqFittedDownsampled[j].second = 0.0;
      }
    }
  }
  return {ampFittedDownsampled, freqFittedDownsampled};
}

std::vector<ControlPoint> ZeroAmplitudeAtZeroFrequency(
    const std::vector<ControlPoint>& controlPoints,
    const std::vector<std::pair<double, double>>& envelope) {
  std::vector<ControlPoint> newControlPoints;
  double maxTime = envelope.empty() ? 0.0 : envelope.back().first;
  for (const auto& p : controlPoints) {
    ControlPoint cp = p;
    if (cp.frequency.has_value() && cp.frequency.value() == 0.0) {
      cp.amplitude = 0.0;
    }
    if (!envelope.empty() && cp.time > maxTime + 20.0) {
      continue;
    }
    newControlPoints.push_back(cp);
  }
  return newControlPoints;
}

std::vector<ControlPoint> SetFirstLastPointAmpToZero(
    const std::vector<ControlPoint>& controlPoints, double ampToKeepRatio) {
  if (controlPoints.empty()) return {};
  std::vector<ControlPoint> points = controlPoints;
  double ampPeak = 0.0;
  for (const auto& p : points) {
    if (p.amplitude.value_or(0.0) > ampPeak) {
      ampPeak = p.amplitude.value_or(0.0);
    }
  }

  if (points.front().amplitude.value_or(0.0) >= ampToKeepRatio * ampPeak) {
    ControlPoint firstPointToAdd;
    firstPointToAdd.time = 0.0;
    firstPointToAdd.frequency = points.front().frequency;
    firstPointToAdd.amplitude = 0.0;
    firstPointToAdd.timeShift = 0.0;
    for (auto& p : points) {
      p.time += kMinDuration;
    }
    points.insert(points.begin(), firstPointToAdd);
  } else {
    points.front().amplitude = 0.0;
  }

  if (points.back().amplitude.value_or(0.0) >= ampToKeepRatio * ampPeak) {
    ControlPoint lastPointToAdd;
    lastPointToAdd.time = points.back().time + kMinDuration;
    lastPointToAdd.frequency = points.back().frequency;
    lastPointToAdd.amplitude = 0.0;
    lastPointToAdd.timeShift = 0.0;
    points.push_back(lastPointToAdd);
  } else {
    points.back().amplitude = 0.0;
  }
  return points;
}

void RejectInvalidWaveform(const std::vector<ControlPoint>& controlPoints,
                           double timeShiftThreshold,
                           double meanFreqThreshold) {
  double maxShift = 0.0;
  bool hasShift = false;
  double freqSum = 0.0;
  int freqCount = 0;
  for (const auto& p : controlPoints) {
    if (p.amplitude.has_value() && p.amplitude.value() != 0.0) {
      if (!hasShift || p.timeShift > maxShift) {
        maxShift = p.timeShift;
        hasShift = true;
      }
    }
    if (p.frequency.has_value() && p.frequency.value() != 0.0) {
      freqSum += p.frequency.value();
      freqCount++;
    }
  }
  if (hasShift && maxShift > timeShiftThreshold) {
    LOG(WARNING) << "max_time_shift " << maxShift << " > "
                 << timeShiftThreshold;
  }
  if (freqCount > 0 && (freqSum / freqCount) < meanFreqThreshold) {
    LOG(WARNING) << "mean_freq " << (freqSum / freqCount) << " < "
                 << meanFreqThreshold;
  }
}

void RejectInvalidBeatingWaveform(double beatingFreq) {
  double rejectionFreq = 1000.0 / (2.0 * kMinDuration);
  if (beatingFreq > rejectionFreq) {
    LOG(WARNING) << "beating_freq " << beatingFreq << " > " << rejectionFreq;
  }
}

double FreqToSharpness(double freq, const FreqProfileObject& freqProfile) {
  double freqLowerLimit = freqProfile.minFrequencyHz;
  double resFreq = freqProfile.resonantFrequencyHz;
  double freqUpperLimit = freqProfile.maxFrequencyHz;

  double f = freq;
  if (f < freqLowerLimit) f = freqLowerLimit;
  if (f > freqUpperLimit) f = freqUpperLimit;

  if (f <= resFreq) {
    double denom =
        (resFreq - freqLowerLimit) != 0.0 ? (resFreq - freqLowerLimit) : 1.0;
    return ((f - freqLowerLimit) / denom) * 0.7;
  } else {
    double denom =
        (freqUpperLimit - resFreq) != 0.0 ? (freqUpperLimit - resFreq) : 1.0;
    return ((f - resFreq) / denom) * 0.3 + 0.7;
  }
}

double SharpnessToFreq(double sharpness, const FreqProfileObject& freqProfile) {
  double freqLowerLimit = freqProfile.minFrequencyHz;
  double resFreq = freqProfile.resonantFrequencyHz;
  double freqUpperLimit = freqProfile.maxFrequencyHz;

  double s = std::max(0.0, std::min(1.0, sharpness));
  if (s <= 0.7) {
    return (s / 0.7) * (resFreq - freqLowerLimit) + freqLowerLimit;
  } else {
    return ((s - 0.7) / 0.3) * (freqUpperLimit - resFreq) + resFreq;
  }
}

std::vector<AdvancedPwlePoint> GenerateAdvancedPwle(
    const std::vector<ControlPoint>& controlPoints) {
  double maxAmp = 0.0;
  for (const auto& p : controlPoints) {
    if (p.amplitude.value_or(0.0) > maxAmp) {
      maxAmp = p.amplitude.value_or(0.0);
    }
  }
  maxAmp = std::min(maxAmp, kAmpUpperLimit);
  if (maxAmp == 0.0) {
    maxAmp = kAmpUpperLimit;
  }

  std::vector<AdvancedPwlePoint> pwlePoints;
  for (size_t i = 0; i < controlPoints.size(); ++i) {
    double pAmp = controlPoints[i].amplitude.value_or(0.0);
    double pFreq = controlPoints[i].frequency.value_or(0.0);
    double pTime = controlPoints[i].time;

    if (pAmp < kAmpLowerLimit) pAmp = kAmpLowerLimit;
    if (pAmp > kAmpUpperLimit) pAmp = kAmpUpperLimit;
    pAmp = pAmp / maxAmp;

    double pDuration = (i == 0) ? 0.0 : (pTime - controlPoints[i - 1].time);
    pwlePoints.push_back({pAmp, pFreq, std::round(pDuration)});
  }
  return pwlePoints;
}

std::vector<BasicPwlePoint> GenerateBasicPwle(
    const std::vector<ControlPoint>& controlPoints,
    const FreqProfileObject& freqProfile) {
  double maxAmp = 0.0;
  for (const auto& p : controlPoints) {
    if (p.amplitude.value_or(0.0) > maxAmp) {
      maxAmp = p.amplitude.value_or(0.0);
    }
  }
  maxAmp = std::min(maxAmp, kAmpUpperLimit);
  if (maxAmp == 0.0) {
    maxAmp = kAmpUpperLimit;
  }

  std::vector<BasicPwlePoint> pwlePoints;
  for (size_t i = 0; i < controlPoints.size(); ++i) {
    double pAmp = controlPoints[i].amplitude.value_or(0.0);
    double pFreq = controlPoints[i].frequency.value_or(0.0);
    double pTime = controlPoints[i].time;

    if (pAmp < kAmpLowerLimit) pAmp = kAmpLowerLimit;
    if (pAmp > kAmpUpperLimit) pAmp = kAmpUpperLimit;
    pAmp = pAmp / maxAmp;

    double pSharpness = FreqToSharpness(pFreq, freqProfile);
    double pDuration = (i == 0) ? 0.0 : (pTime - controlPoints[i - 1].time);
    pwlePoints.push_back({pAmp, pSharpness, std::round(pDuration)});
  }
  return pwlePoints;
}

struct EventItem {
  double time;
  std::string type;
  std::optional<BasicPwlePoint> basicPoint;
  std::optional<AdvancedPwlePoint> advPoint;
  double intensity;
};

static bool addPwleJsonEvent(std::ostringstream& oss,
                             const std::vector<EventItem>& piece,
                             std::string_view pwleType, double startTime,
                             bool isFirst) {
  if (piece.size() <= 1) return false;
  if (!isFirst) {
    oss << ",\n";
  }
  oss << "      {\n";
  oss << "        \"startTimeMillis\": " << static_cast<int>(startTime)
      << ",\n";
  if (pwleType == "advanced_pwle") {
    double initFreq = piece[1].advPoint->frequency;
    oss << "        \"advancedEnvelope\": {\n";
    oss << "          \"initialFrequency\": " << std::setprecision(16)
        << initFreq << ",\n";
    oss << "          \"controlPoint\": [\n";
    for (size_t i = 1; i < piece.size(); ++i) {
      const auto& p = *piece[i].advPoint;
      oss << "            {\n";
      oss << "              \"amplitude\": " << std::setprecision(16)
          << p.amplitude << ",\n";
      oss << "              \"frequencyHz\": " << std::setprecision(16)
          << p.frequency << ",\n";
      oss << "              \"durationMillis\": "
          << static_cast<int>(p.duration) << "\n";
      oss << "            }" << (i + 1 < piece.size() ? "," : "") << "\n";
    }
    oss << "          ]\n";
    oss << "        }\n";
  } else {
    double initSharpness = piece[1].basicPoint->sharpness;
    oss << "        \"basicEnvelope\": {\n";
    oss << "          \"initialSharpness\": " << std::setprecision(16)
        << initSharpness << ",\n";
    oss << "          \"controlPoint\": [\n";
    for (size_t i = 1; i < piece.size(); ++i) {
      const auto& p = *piece[i].basicPoint;
      oss << "            {\n";
      oss << "              \"intensity\": " << std::setprecision(16)
          << p.intensity << ",\n";
      oss << "              \"sharpness\": " << std::setprecision(16)
          << p.sharpness << ",\n";
      oss << "              \"durationMillis\": "
          << static_cast<int>(p.duration) << "\n";
      oss << "            }" << (i + 1 < piece.size() ? "," : "") << "\n";
    }
    oss << "          ]\n";
    oss << "        }\n";
  }
  oss << "      }";
  return true;
}

std::string GeneratePwleJson(
    const std::vector<std::variant<BasicPwlePoint, AdvancedPwlePoint>>& points,
    std::string_view pwleType, const std::string& outputPath,
    std::string_view jsonVersion,
    const std::vector<std::pair<double, double>>& presetInsertPoints) {
  std::vector<EventItem> events;
  double currTime = 0.0;
  for (const auto& ptVariant : points) {
    if (std::holds_alternative<BasicPwlePoint>(ptVariant)) {
      const auto& p = std::get<BasicPwlePoint>(ptVariant);
      currTime += p.duration;
      events.push_back({currTime, "pwle", p, std::nullopt, 0.0});
    } else {
      const auto& p = std::get<AdvancedPwlePoint>(ptVariant);
      currTime += p.duration;
      events.push_back({currTime, "pwle", std::nullopt, p, 0.0});
    }
  }

  for (const auto& [startTime, intensity] : presetInsertPoints) {
    events.push_back(
        {startTime, "preset", std::nullopt, std::nullopt, intensity});
  }

  std::sort(events.begin(), events.end(),
            [](const EventItem& a, const EventItem& b) {
              if (a.time != b.time) return a.time < b.time;
              return a.type == "preset" && b.type != "preset";
            });

  std::ostringstream oss;
  oss << "{\n";
  oss << "  \"version\": \"" << jsonVersion << "\",\n";
  oss << "  \"hapticEffect\": {\n";
  oss << "    \"events\": [\n";

  std::vector<EventItem> currentPiece;
  double lastPwleTime = 0.0;
  bool firstEvent = true;

  for (const auto& ev : events) {
    if (ev.type == "preset") {
      if (!currentPiece.empty()) {
        if (addPwleJsonEvent(oss, currentPiece, pwleType, lastPwleTime,
                             firstEvent)) {
          firstEvent = false;
        }
        currentPiece.clear();
      }
      if (!firstEvent) {
        oss << ",\n";
      }
      oss << "      {\n";
      oss << "        \"startTimeMillis\": " << static_cast<int>(ev.time)
          << ",\n";
      oss << "        \"preset\": {\n";
      oss << "          \"presetEnum\": \"CLICK\",\n";
      oss << "          \"intensity\": " << std::setprecision(16)
          << ev.intensity << "\n";
      oss << "        }\n";
      oss << "      }";
      firstEvent = false;
    } else {
      if (currentPiece.empty()) {
        lastPwleTime = ev.time;
        EventItem startItem = ev;
        if (pwleType == "advanced_pwle") {
          startItem.advPoint->duration = 0.0;
        } else {
          startItem.basicPoint->duration = 0.0;
        }
        currentPiece.push_back(startItem);
      } else {
        currentPiece.push_back(ev);
      }
    }
  }

  if (!currentPiece.empty()) {
    addPwleJsonEvent(oss, currentPiece, pwleType, lastPwleTime, firstEvent);
  }

  oss << "\n    ]\n";
  oss << "  }\n";
  oss << "}\n";

  std::string jsonStr = oss.str();
  if (!outputPath.empty()) {
    std::ofstream out(outputPath);
    if (!out.is_open()) {
      LOG(ERROR) << "Failed to open output file: " << outputPath;
      return "";
    }
    out << jsonStr;
    out.close();
    LOG(INFO) << "Successfully wrote Haptics JSON to " << outputPath;
  }
  return jsonStr;
}

namespace {
struct JsonTok {
  enum Type {
    STRING,
    NUMBER,
    LBRACE,
    RBRACE,
    LBRACKET,
    RBRACKET,
    COLON,
    COMMA,
    TRUE_LIT,
    FALSE_LIT,
    NULL_LIT,
    END_OF_FILE
  };
  Type type;
  std::string str_val;
  double num_val = 0.0;
};

std::vector<JsonTok> tokenizeJson(const std::string& s) {
  std::vector<JsonTok> toks;
  size_t i = 0;
  while (i < s.size()) {
    char c = s[i];
    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
      ++i;
    } else if (c == '{') {
      toks.push_back({JsonTok::LBRACE, "{", 0.0});
      ++i;
    } else if (c == '}') {
      toks.push_back({JsonTok::RBRACE, "}", 0.0});
      ++i;
    } else if (c == '[') {
      toks.push_back({JsonTok::LBRACKET, "[", 0.0});
      ++i;
    } else if (c == ']') {
      toks.push_back({JsonTok::RBRACKET, "]", 0.0});
      ++i;
    } else if (c == ':') {
      toks.push_back({JsonTok::COLON, ":", 0.0});
      ++i;
    } else if (c == ',') {
      toks.push_back({JsonTok::COMMA, ",", 0.0});
      ++i;
    } else if (c == '"') {
      ++i;
      std::string str;
      while (i < s.size() && s[i] != '"') {
        if (s[i] == '\\' && i + 1 < s.size()) ++i;
        str += s[i++];
      }
      if (i < s.size() && s[i] == '"') ++i;
      toks.push_back({JsonTok::STRING, str, 0.0});
    } else if ((c >= '0' && c <= '9') || c == '-' || c == '+') {
      size_t start = i++;
      while (i < s.size() &&
             ((s[i] >= '0' && s[i] <= '9') || s[i] == '.' || s[i] == 'e' ||
              s[i] == 'E' || s[i] == '-' || s[i] == '+')) {
        ++i;
      }
      double val = 0.0;
      if (!absl::SimpleAtod(absl::string_view(s).substr(start, i - start),
                            &val)) {
        // Fallback to 0.0 on parse error, mirroring original try/catch
        // semantic.
      }
      toks.push_back({JsonTok::NUMBER, "", val});
    } else if (s.compare(i, 4, "true") == 0) {
      toks.push_back({JsonTok::TRUE_LIT, "true", 1.0});
      i += 4;
    } else if (s.compare(i, 5, "false") == 0) {
      toks.push_back({JsonTok::FALSE_LIT, "false", 0.0});
      i += 5;
    } else if (s.compare(i, 4, "null") == 0) {
      toks.push_back({JsonTok::NULL_LIT, "null", 0.0});
      i += 4;
    } else {
      ++i;
    }
  }
  toks.push_back({JsonTok::END_OF_FILE, "", 0.0});
  return toks;
}
}  // namespace

bool ParseHapticsJson(const std::string& jsonPath,
                      std::vector<std::vector<ControlPoint>>* envelopes,
                      std::string* pwleType,
                      std::vector<PresetEvent>* presets) {
  std::ifstream in(jsonPath);
  if (!in.is_open()) return false;
  std::string content((std::istreambuf_iterator<char>(in)),
                      std::istreambuf_iterator<char>());
  in.close();

  auto toks = tokenizeJson(content);
  size_t idx = 0;

  envelopes->clear();
  presets->clear();
  *pwleType = "";

  while (idx < toks.size() && toks[idx].type != JsonTok::END_OF_FILE) {
    if (toks[idx].type == JsonTok::STRING && toks[idx].str_val == "events") {
      ++idx;
      if (idx < toks.size() && toks[idx].type == JsonTok::COLON) ++idx;
      if (idx < toks.size() && toks[idx].type == JsonTok::LBRACKET) {
        ++idx;
        while (idx < toks.size() && toks[idx].type != JsonTok::RBRACKET) {
          if (toks[idx].type == JsonTok::LBRACE) {
            ++idx;
            double startTime = 0.0;
            std::string eventType = "";
            double initialSharpness = 0.0;
            double initialFreq = 0.0;
            std::vector<ControlPoint> currentEnv;
            std::string presetEnum = "";
            double presetIntensity = 1.0;

            while (idx < toks.size() && toks[idx].type != JsonTok::RBRACE) {
              if (toks[idx].type == JsonTok::STRING) {
                const std::string& key = toks[idx].str_val;
                ++idx;
                if (idx < toks.size() && toks[idx].type == JsonTok::COLON) {
                  ++idx;
                }
                if (key == "startTimeMillis") {
                  if (idx < toks.size() && toks[idx].type == JsonTok::NUMBER) {
                    startTime = toks[idx].num_val;
                    ++idx;
                  }
                } else if (key == "basicEnvelope") {
                  eventType = "basic";
                  *pwleType = "basic_pwle";
                  if (idx < toks.size() && toks[idx].type == JsonTok::LBRACE) {
                    ++idx;
                    while (idx < toks.size() &&
                           toks[idx].type != JsonTok::RBRACE) {
                      if (toks[idx].type == JsonTok::STRING &&
                          toks[idx].str_val == "initialSharpness") {
                        idx += 2;
                        if (idx < toks.size() &&
                            toks[idx].type == JsonTok::NUMBER) {
                          initialSharpness = toks[idx].num_val;
                          ++idx;
                        }
                      } else if (toks[idx].type == JsonTok::STRING &&
                                 toks[idx].str_val == "controlPoint") {
                        idx += 2;
                        if (idx < toks.size() &&
                            toks[idx].type == JsonTok::LBRACKET) {
                          ++idx;
                          double currTime = startTime;
                          ControlPoint initPt;
                          initPt.time = startTime;
                          initPt.amplitude = 0.0;
                          initPt.frequency = SharpnessToFreq(initialSharpness);
                          currentEnv.push_back(initPt);

                          while (idx < toks.size() &&
                                 toks[idx].type != JsonTok::RBRACKET) {
                            if (toks[idx].type == JsonTok::LBRACE) {
                              ++idx;
                              double intensity = 0.0;
                              double sharpness = 0.0;
                              double duration = 0.0;
                              while (idx < toks.size() &&
                                     toks[idx].type != JsonTok::RBRACE) {
                                if (toks[idx].type == JsonTok::STRING) {
                                  const std::string& cpKey = toks[idx].str_val;
                                  idx += 2;
                                  if (cpKey == "intensity") {
                                    intensity = toks[idx].num_val;
                                    ++idx;
                                  } else if (cpKey == "sharpness") {
                                    sharpness = toks[idx].num_val;
                                    ++idx;
                                  } else if (cpKey == "durationMillis") {
                                    duration = toks[idx].num_val;
                                    ++idx;
                                  }
                                } else {
                                  ++idx;
                                }
                              }
                              currTime += duration;
                              ControlPoint cp;
                              cp.time = currTime;
                              cp.amplitude = intensity;
                              cp.frequency = SharpnessToFreq(sharpness);
                              currentEnv.push_back(cp);
                            }
                            ++idx;
                          }
                        }
                      } else {
                        ++idx;
                      }
                    }
                  }
                } else if (key == "advancedEnvelope") {
                  eventType = "advanced";
                  *pwleType = "advanced_pwle";
                  if (idx < toks.size() && toks[idx].type == JsonTok::LBRACE) {
                    ++idx;
                    while (idx < toks.size() &&
                           toks[idx].type != JsonTok::RBRACE) {
                      if (toks[idx].type == JsonTok::STRING &&
                          toks[idx].str_val == "initialFrequency") {
                        idx += 2;
                        if (idx < toks.size() &&
                            toks[idx].type == JsonTok::NUMBER) {
                          initialFreq = toks[idx].num_val;
                          ++idx;
                        }
                      } else if (toks[idx].type == JsonTok::STRING &&
                                 toks[idx].str_val == "controlPoint") {
                        idx += 2;
                        if (idx < toks.size() &&
                            toks[idx].type == JsonTok::LBRACKET) {
                          ++idx;
                          double currTime = startTime;
                          ControlPoint initPt;
                          initPt.time = startTime;
                          initPt.amplitude = 0.0;
                          initPt.frequency = initialFreq;
                          currentEnv.push_back(initPt);

                          while (idx < toks.size() &&
                                 toks[idx].type != JsonTok::RBRACKET) {
                            if (toks[idx].type == JsonTok::LBRACE) {
                              ++idx;
                              double amplitude = 0.0;
                              double freq = 0.0;
                              double duration = 0.0;
                              while (idx < toks.size() &&
                                     toks[idx].type != JsonTok::RBRACE) {
                                if (toks[idx].type == JsonTok::STRING) {
                                  const std::string& cpKey = toks[idx].str_val;
                                  idx += 2;
                                  if (cpKey == "amplitude") {
                                    amplitude = toks[idx].num_val;
                                    ++idx;
                                  } else if (cpKey == "frequencyHz") {
                                    freq = toks[idx].num_val;
                                    ++idx;
                                  } else if (cpKey == "durationMillis") {
                                    duration = toks[idx].num_val;
                                    ++idx;
                                  }
                                } else {
                                  ++idx;
                                }
                              }
                              currTime += duration;
                              ControlPoint cp;
                              cp.time = currTime;
                              cp.amplitude = amplitude;
                              cp.frequency = freq;
                              currentEnv.push_back(cp);
                            }
                            ++idx;
                          }
                        }
                      } else {
                        ++idx;
                      }
                    }
                  }
                } else if (key == "preset") {
                  eventType = "preset";
                  if (idx < toks.size() && toks[idx].type == JsonTok::LBRACE) {
                    ++idx;
                    while (idx < toks.size() &&
                           toks[idx].type != JsonTok::RBRACE) {
                      if (toks[idx].type == JsonTok::STRING) {
                        const std::string& pKey = toks[idx].str_val;
                        idx += 2;
                        if (pKey == "presetEnum") {
                          presetEnum = toks[idx].str_val;
                          ++idx;
                        } else if (pKey == "intensity") {
                          presetIntensity = toks[idx].num_val;
                          ++idx;
                        }
                      } else {
                        ++idx;
                      }
                    }
                  }
                } else {
                  ++idx;
                }
              } else {
                ++idx;
              }
            }
            if (eventType == "basic" || eventType == "advanced") {
              envelopes->push_back(currentEnv);
            } else if (eventType == "preset") {
              presets->push_back({startTime, presetEnum, presetIntensity});
            }
          }
          ++idx;
        }
      }
    } else {
      ++idx;
    }
  }

  std::sort(envelopes->begin(), envelopes->end(),
            [](const std::vector<ControlPoint>& a,
               const std::vector<ControlPoint>& b) {
              if (a.empty() || b.empty()) return false;
              return a[0].time < b[0].time;
            });

  return !envelopes->empty() || !presets->empty();
}

}  // namespace pcm2pwle
