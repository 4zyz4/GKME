// Minimal Abseil logging shim for the vendored Android haptics-tools
// pcm2pwle sources. The converter only uses LOG(WARNING/INFO/ERROR) for
// diagnostics; route them to stderr so the upstream .cc files stay unmodified.
#pragma once

#include <iostream>

#define LOG(severity) std::cerr
