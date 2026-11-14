#include "pcm2pwle/dsp_utils.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstddef>
#include <utility>
#include <vector>

#include "pocketfft_hdronly.h"

namespace pcm2pwle {

std::vector<BiquadSection> ButterworthLowpassSos(int order, double cutoffHz,
                                                 double sampleRate) {
  double nyquist = sampleRate / 2.0;
  double cutoffNorm = std::max(1e-5, std::min(0.999, cutoffHz / nyquist));
  double wa = 4.0 * std::tan((M_PI * cutoffNorm) / 2.0);

  std::vector<BiquadSection> sections;
  if (order % 2 == 1) {
    double prodDenom = 1.0;
    double pReal = -1.0 * wa;
    double zReal = (4.0 + pReal) / (4.0 - pReal);
    double a1Real = -zReal;
    prodDenom *= std::abs(4.0 - pReal);

    sections.push_back({0.0, 0.0, 0.0, a1Real, 0.0});

    int numPairs = order / 2;
    for (int k = 0; k < numPairs; ++k) {
      double theta = (M_PI * (2 * (k + 1))) / (2.0 * order);
      std::complex<double> p(-std::cos(theta), std::sin(theta));
      std::complex<double> pScaled = p * wa;
      std::complex<double> z = (4.0 + pScaled) / (4.0 - pScaled);
      double a1 = -2.0 * z.real();
      double a2 = std::norm(z);
      prodDenom *= std::norm(4.0 - pScaled);

      if (k < numPairs - 1) {
        sections.push_back({1.0, 2.0, 1.0, a1, a2});
      } else {
        sections.push_back({1.0, 1.0, 0.0, a1, a2});
      }
    }

    double kz = std::pow(wa, order) / prodDenom;
    sections[0].b0 = kz;
    sections[0].b1 = 2.0 * kz;
    sections[0].b2 = kz;
  }
  return sections;
}

std::vector<BiquadSection> Cheby1LowpassSos(int order, double rippleDb,
                                            double cutoffHz,
                                            double sampleRate) {
  double nyquist = sampleRate / 2.0;
  double cutoffNorm = std::max(1e-5, std::min(0.999, cutoffHz / nyquist));
  double eps = std::sqrt(std::pow(10.0, 0.1 * rippleDb) - 1.0);
  double mu = std::asinh(1.0 / eps) / static_cast<double>(order);
  double wa = 4.0 * std::tan((M_PI * cutoffNorm) / 2.0);

  std::vector<BiquadSection> sections;
  sections.reserve(order / 2);
  double prodDenom = 1.0;
  for (int k = 0; k < order / 2; ++k) {
    double theta = (M_PI * (2 * k + 1)) / (2.0 * order);
    std::complex<double> p(-std::sinh(mu) * std::cos(theta),
                           std::cosh(mu) * std::sin(theta));
    std::complex<double> pScaled = p * wa;
    std::complex<double> z = (4.0 + pScaled) / (4.0 - pScaled);
    double a1 = -2.0 * z.real();
    double a2 = std::norm(z);
    prodDenom *= std::norm(4.0 - pScaled);
    double b0 = (k > 0) ? 1.0 : 0.0;
    sections.push_back({b0, 2.0 * b0, b0, a1, a2});
  }

  double prodP = 1.0;
  for (int k = 0; k < order / 2; ++k) {
    double theta = (M_PI * (2 * k + 1)) / (2.0 * order);
    std::complex<double> p(-std::sinh(mu) * std::cos(theta),
                           std::cosh(mu) * std::sin(theta));
    prodP *= std::norm(p);
  }
  double ka = prodP / std::sqrt(1.0 + eps * eps);
  double kz = (ka * std::pow(wa, order)) / prodDenom;
  sections[0].b0 = kz;
  sections[0].b1 = 2.0 * kz;
  sections[0].b2 = kz;

  return sections;
}

std::vector<double> Sosfiltfilt(const std::vector<BiquadSection>& sos,
                                const std::vector<double>& data) {
  int n = data.size();
  if (n == 0) return {};
  int nSections = sos.size();
  int ntaps = 2 * nSections + 1;
  int zeroB2Count = 0;
  int zeroA2Count = 0;
  for (const auto& s : sos) {
    if (s.b2 == 0.0) zeroB2Count++;
    if (s.a2 == 0.0) zeroA2Count++;
  }
  ntaps -= std::min(zeroB2Count, zeroA2Count);
  int edge = ntaps * 3;
  if (n <= edge) {
    edge = n - 1;
  }
  if (edge < 0) edge = 0;

  std::vector<std::pair<double, double>> zi(nSections);
  double scale = 1.0;
  for (int i = 0; i < nSections; ++i) {
    const auto& s = sos[i];
    double denom = 1.0 + s.a1 + s.a2;
    if (std::abs(denom) < 1e-12) denom = 1e-12;
    double s1 = (s.b1 + s.b2 - (s.a1 + s.a2) * s.b0) / denom;
    double s2 = (s.b2 - s.a2 * s.b0) - s.a2 * s1;
    zi[i] = {scale * s1, scale * s2};
    scale *= (s.b0 + s.b1 + s.b2) / denom;
  }

  int extLen = n + 2 * edge;
  std::vector<double> ext(extLen);
  for (int i = 0; i < edge; ++i) {
    ext[i] = 2.0 * data[0] - data[edge - i];
  }
  for (int i = 0; i < n; ++i) {
    ext[edge + i] = data[i];
  }
  for (int i = 0; i < edge; ++i) {
    ext[edge + n + i] = 2.0 * data[n - 1] - data[n - 2 - i];
  }

  double x0 = ext[0];
  std::vector<double> current = ext;
  for (int sIdx = 0; sIdx < nSections; ++sIdx) {
    const auto& s = sos[sIdx];
    double s1 = zi[sIdx].first * x0;
    double s2 = zi[sIdx].second * x0;
    std::vector<double> next(extLen);
    for (int i = 0; i < extLen; ++i) {
      double x = current[i];
      double y = s.b0 * x + s1;
      s1 = s.b1 * x - s.a1 * y + s2;
      s2 = s.b2 * x - s.a2 * y;
      next[i] = y;
    }
    current = std::move(next);
  }

  std::reverse(current.begin(), current.end());
  double y0 = current[0];
  for (int sIdx = 0; sIdx < nSections; ++sIdx) {
    const auto& s = sos[sIdx];
    double s1 = zi[sIdx].first * y0;
    double s2 = zi[sIdx].second * y0;
    std::vector<double> next(extLen);
    for (int i = 0; i < extLen; ++i) {
      double x = current[i];
      double y = s.b0 * x + s1;
      s1 = s.b1 * x - s.a1 * y + s2;
      s2 = s.b2 * x - s.a2 * y;
      next[i] = y;
    }
    current = std::move(next);
  }
  std::reverse(current.begin(), current.end());

  std::vector<double> result(n);
  for (int i = 0; i < n; ++i) {
    result[i] = current[edge + i];
  }
  return result;
}

std::vector<double> Medfilt(const std::vector<double>& signal, int kernelSize) {
  int n = signal.size();
  std::vector<double> out(n, 0.0);
  if (n == 0) return out;
  int k = std::max(1, kernelSize | 1);
  int half = k / 2;
  std::vector<double> window;
  window.reserve(k);
  for (int j = -half; j <= half; ++j) {
    window.push_back((j >= 0 && j < n) ? signal[j] : 0.0);
  }
  std::sort(window.begin(), window.end());
  out[0] = window[half];
  for (int i = 1; i < n; ++i) {
    int remJ = i - 1 - half;
    double remVal = (remJ >= 0 && remJ < n) ? signal[remJ] : 0.0;
    auto it = std::lower_bound(window.begin(), window.end(), remVal);
    if (it != window.end() && *it == remVal) {
      window.erase(it);
    }
    int addJ = i + half;
    double addVal = (addJ >= 0 && addJ < n) ? signal[addJ] : 0.0;
    auto insIt = std::lower_bound(window.begin(), window.end(), addVal);
    window.insert(insIt, addVal);
    out[i] = window[half];
  }
  return out;
}

void Hilbert(const std::vector<double>& signal, std::vector<double>* real,
             std::vector<double>* imag) {
  int n = signal.size();
  real->resize(n);
  imag->resize(n);
  if (n == 0) return;

  pocketfft::shape_t shape{static_cast<size_t>(n)};
  pocketfft::stride_t stride{sizeof(std::complex<double>)};
  pocketfft::shape_t axes{0};

  std::vector<std::complex<double>> cdata(n);
  for (int i = 0; i < n; ++i) {
    cdata[i] = std::complex<double>(signal[i], 0.0);
  }
  std::vector<std::complex<double>> spec(n);
  pocketfft::c2c(shape, stride, stride, axes, pocketfft::FORWARD, cdata.data(),
                 spec.data(), 1.0);

  if (n % 2 == 0) {
    for (int i = 1; i < n / 2; ++i) spec[i] *= 2.0;
    for (int i = n / 2 + 1; i < n; ++i)
      spec[i] = 0.0;
  } else {
    for (int i = 1; i <= n / 2; ++i) spec[i] *= 2.0;
    for (int i = n / 2 + 1; i < n; ++i)
      spec[i] = 0.0;
  }

  std::vector<std::complex<double>> res(n);
  pocketfft::c2c(shape, stride, stride, axes, pocketfft::BACKWARD, spec.data(),
                 res.data(), 1.0 / n);

  for (int i = 0; i < n; ++i) {
    (*real)[i] = res[i].real();
    (*imag)[i] = res[i].imag();
  }
}

std::vector<std::complex<double>> Rfft(const std::vector<double>& data) {
  int n = data.size();
  int nOut = n / 2 + 1;
  std::vector<std::complex<double>> out(nOut);
  if (n == 0) return out;

  pocketfft::shape_t shapeIn{static_cast<size_t>(n)};
  pocketfft::stride_t strideIn{sizeof(double)};
  pocketfft::shape_t shapeOut{static_cast<size_t>(nOut)};
  pocketfft::stride_t strideOut{sizeof(std::complex<double>)};
  pocketfft::shape_t axes{0};

  pocketfft::r2c(shapeIn, strideIn, strideOut, axes, pocketfft::FORWARD,
                 data.data(), out.data(), 1.0);
  return out;
}

std::vector<double> Unwrap(const std::vector<double>& phase) {
  int n = phase.size();
  std::vector<double> out(n);
  if (n == 0) return out;
  out[0] = phase[0];
  double shift = 0.0;
  for (int i = 1; i < n; ++i) {
    double diff = phase[i] - phase[i - 1];
    if (diff > M_PI) {
      shift -= 2.0 * M_PI;
    } else if (diff < -M_PI) {
      shift += 2.0 * M_PI;
    }
    out[i] = phase[i] + shift;
  }
  return out;
}

std::vector<double> Decimate(const std::vector<double>& data, int q) {
  if (q <= 1) return data;
  auto sos = Cheby1LowpassSos(8, 0.05, 0.8 / q, 2.0);
  auto filtered = Sosfiltfilt(sos, data);
  std::vector<double> out;
  out.reserve((filtered.size() + q - 1) / q);
  for (size_t i = 0; i < filtered.size(); i += q) {
    out.push_back(filtered[i]);
  }
  return out;
}

}  // namespace pcm2pwle
