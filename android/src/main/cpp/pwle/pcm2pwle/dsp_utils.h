#ifndef THIRD_PARTY_ANDROID_HAPTICS_TOOLS_PCM2PWLE_DSP_UTILS_H_
#define THIRD_PARTY_ANDROID_HAPTICS_TOOLS_PCM2PWLE_DSP_UTILS_H_

#include <complex>
#include <vector>

namespace pcm2pwle {

struct BiquadSection {
  double b0;
  double b1;
  double b2;
  double a1;
  double a2;
};

// Generates Second-Order Sections (SOS) for digital Butterworth lowpass filter.
std::vector<BiquadSection> ButterworthLowpassSos(int order, double cutoffHz,
                                                 double sampleRate);

// Generates SOS for digital Chebyshev Type I lowpass filter.
std::vector<BiquadSection> Cheby1LowpassSos(int order, double rippleDb,
                                            double cutoffHz, double sampleRate);

// Zero-phase forward-backward digital filtering matching
// scipy.signal.Sosfiltfilt.
std::vector<double> Sosfiltfilt(const std::vector<BiquadSection>& sos,
                                const std::vector<double>& data);

// 1D median filter with zero padding matching scipy.signal.Medfilt.
std::vector<double> Medfilt(const std::vector<double>& signal, int kernelSize);

// Computes discrete Hilbert transform to produce the analytic signal z(t).
void Hilbert(const std::vector<double>& signal, std::vector<double>* real,
             std::vector<double>* imag);

// Computes 1D real FFT matching scipy.fft.Rfft.
std::vector<std::complex<double>> Rfft(const std::vector<double>& data);

// Unwraps phase radian angles matching numpy.Unwrap.
std::vector<double> Unwrap(const std::vector<double>& phase);

// Downsamples the signal matching scipy.signal.Decimate (order 8 Chebyshev Type
// I).
std::vector<double> Decimate(const std::vector<double>& data, int q);

}  // namespace pcm2pwle

#endif  // THIRD_PARTY_ANDROID_HAPTICS_TOOLS_PCM2PWLE_DSP_UTILS_H_
