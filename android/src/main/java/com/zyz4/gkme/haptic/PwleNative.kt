package com.zyz4.gkme.haptic

/**
 * Google `android/haptics-tools` PCM2PWLE 的 JNI 封装。
 *
 * 把一段 PCM（归一化 -1..1）转成 **advanced PWLE** 控制点
 * `(amplitude 0..1, frequencyHz, durationMs)`，用于 Android 16+ 的
 * `VibrationEffect.WaveformEnvelopeBuilder`。算法（滤波 → 希尔伯特包络 →
 * RDP 控制点提取 → JND/最短时长约束）见 `cpp/pwle/README.md`。
 *
 * 纯 native 调用，失败（库缺失、波形无法转换）返回 null，调用方回退旧的 HE 通路。
 */
object PwleNative {

    private val loaded: Boolean = try {
        System.loadLibrary("gkme_pwle")
        true
    } catch (_: Throwable) {
        false
    }

    val isAvailable: Boolean get() = loaded

    /**
     * @param samples 单声道 PCM，[-1, 1]，长度约等于一个分块（推荐 200ms）。
     * @param sampleRate 采样率（Hz）。
     * @param limits 设备频率范围；只使用其中的 min/resonant/max。
     * @return 控制点列表；第一个点 `durationMs == 0` 表示初始状态（频率用于
     *         `setInitialFrequencyHz`）。无有效输出时返回 null。
     */
    fun convert(
        samples: FloatArray,
        sampleRate: Int,
        limits: PwleEncoder.Limits,
    ): List<PwleEncoder.ControlPoint>? {
        if (!loaded || samples.isEmpty() || sampleRate <= 0 || !limits.isValid()) return null
        val flat = try {
            nativeConvert(
                samples,
                sampleRate,
                limits.minFrequencyHz,
                limits.resonanceHz,
                limits.maxFrequencyHz,
            )
        } catch (_: Throwable) {
            null
        } ?: return null
        if (flat.size < 6) return null
        val points = ArrayList<PwleEncoder.ControlPoint>(flat.size / 3)
        var i = 0
        while (i + 2 < flat.size) {
            points.add(
                PwleEncoder.ControlPoint(
                    amplitude = flat[i].coerceIn(0f, 1f),
                    frequencyHz = flat[i + 1],
                    durationMs = flat[i + 2].toLong().coerceAtLeast(0L),
                )
            )
            i += 3
        }
        return points.takeIf { it.size >= 2 }
    }

    private external fun nativeConvert(
        samples: FloatArray,
        sampleRate: Int,
        minFrequencyHz: Float,
        resonantFrequencyHz: Float,
        maxFrequencyHz: Float,
    ): FloatArray?
}
