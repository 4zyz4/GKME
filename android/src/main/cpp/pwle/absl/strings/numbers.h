// Minimal Abseil numbers shim for the vendored Android haptics-tools
// pcm2pwle sources. Only absl::SimpleAtod is used, in the JSON parser.
#pragma once

#include <cerrno>
#include <cstdlib>
#include <string>
#include <string_view>

namespace absl {

using string_view = std::string_view;

inline bool SimpleAtod(std::string_view text, double* out) {
    if (out == nullptr || text.empty()) {
        return false;
    }
    std::string buffer(text);
    char* end = nullptr;
    errno = 0;
    double value = std::strtod(buffer.c_str(), &end);
    if (end == buffer.c_str() || *end != '\0') {
        return false;
    }
    *out = value;
    return true;
}

}  // namespace absl
