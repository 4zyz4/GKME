package com.zyz4.gkme.service

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 单音音高跟踪器：对语音线圈 PCM 做带通 + **Hilbert 变换**，由解析信号的瞬时相位求
 * 瞬时频率，得到高分辨率的主导频率（Hz）与归一化幅度。
 *
 * 直接对应 android/haptics-tools `pcm2pwle` 的 `CalculateFreqEnv`：
 * 1. 带限（本实现用频域矩形窗代替 Butterworth 低通）；
 * 2. Hilbert 解析信号 → 幅度包络 + 瞬时相位；
 * 3. 解卷绕求相位差分 → 瞬时频率；
 * 4. 时间上做指数平滑（对应官方对包络的低通）。
 *
 * 纯 JVM 代码，方便单测。
 */
class PcmPitchTracker {

    /**
     * 一次分析结果：[freqHz] 主导频率，[amp] 归一化幅度 0..1。
     *
     * [voiced] 表示带内是否存在明确的主导音调：纯音/窄带为 true；宽带噪声/撞击（无明确
     * 音高）为 false。unvoiced 时 [freqHz] 置 0，下游据此改用谐振频率驱动（宽带内容在
     * 窄带 LRA 上本就无法复现音高，用谐振点能给出最大的机械能量，只由包络承载质感）。
     */
    data class Pitch(val freqHz: Double, val amp: Double, val voiced: Boolean = true)

    private companion object {
        const val FFT_SIZE = 2048      // ~43ms @48k
        const val HOP = 480            // 每 10ms 更新一次
        const val F_MIN = 30.0
        const val F_MAX = 600.0
        // 对新测得值做指数平滑，抑制逐帧抖动（对应官方对频率包络的低通）。
        const val SMOOTH = 0.4
        // 测量值明显偏离当前值时的“快速跟随”平滑系数（降低音高突变/滑音的滞后）。
        const val SMOOTH_FAST = 0.85
        // 触发快速跟随的偏差阈值（Hz）。
        const val FAST_DELTA_HZ = 25.0
        // 只统计幅度高于峰值该比例的样本，避开相位噪声。
        const val AMP_GATE = 0.3
        // 音调性（带内峰值功率 / 带内总功率）判定门限，带滞回避免在噪声边缘抖动。
        const val TONALITY_ON = 0.25
        const val TONALITY_OFF = 0.12
    }

    private var rate = 0
    private var inChannels = 0

    private val ring = FloatArray(FFT_SIZE)
    private var pos = 0
    private var total = 0L
    private var sinceHop = 0

    private val re = DoubleArray(FFT_SIZE)
    private val im = DoubleArray(FFT_SIZE)
    private val amp = DoubleArray(FFT_SIZE)
    private val phase = DoubleArray(FFT_SIZE)
    private val hann = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2.0 * PI * it / (FFT_SIZE - 1)) }

    private var hasOutput = false
    private var smoothFreq = 0.0
    private var smoothAmp = 0.0
    private var voiced = true

    fun reset() {
        pos = 0
        total = 0
        sinceHop = 0
        hasOutput = false
        smoothFreq = 0.0
        smoothAmp = 0.0
        voiced = true
    }

    private fun configure(sampleRate: Int, channels: Int) {
        if (sampleRate == rate && channels == inChannels) return
        rate = sampleRate
        inChannels = channels
        reset()
    }

    /**
     * 输入一帧交错 S16LE PCM，返回当前平滑后的 [Pitch]；尚未积累够一个窗口时返回 null。
     */
    fun process(pcm: ByteArray, channels: Int, sampleRate: Int): Pitch? {
        configure(sampleRate, channels)
        if (pcm.isEmpty()) return if (hasOutput) current() else null

        val ch = maxOf(channels, 1)
        val bytesPerFrame = ch * 2
        val numFrames = pcm.size / bytesPerFrame
        if (numFrames <= 0) return if (hasOutput) current() else null

        // 语音线圈：4 通道时在 ch2/ch3，否则退回 ch0/ch1。手机单马达取主导通道即可。
        val chIdx = if (ch >= 4) 2 else 0

        for (i in 0 until numFrames) {
            val base = i * bytesPerFrame + chIdx * 2
            ring[pos] = readShortLe(pcm, base) / 32768f
            pos = (pos + 1) % FFT_SIZE
            total++
            sinceHop++
            if (total >= FFT_SIZE && sinceHop >= HOP) {
                analyze()
                sinceHop = 0
            }
        }
        return if (hasOutput) current() else null
    }

    private fun current(): Pitch = Pitch(if (voiced) smoothFreq else 0.0, smoothAmp, voiced)

    private fun analyze() {
        // 按时间顺序取出窗口，去直流并加 Hann 窗
        var mean = 0.0
        for (i in 0 until FFT_SIZE) {
            val v = ring[(pos + i) % FFT_SIZE].toDouble()
            re[i] = v
            im[i] = 0.0
            mean += v
        }
        mean /= FFT_SIZE
        for (i in 0 until FFT_SIZE) re[i] = (re[i] - mean) * hann[i]

        fft(re, im)

        val binHz = rate.toDouble() / FFT_SIZE

        // 音调性 = 带内峰值功率 / 带内总功率。纯音/窄带接近 1；宽带噪声接近 1/带宽。
        // 必须在下面的解析信号构造（会置零带外 bin）之前统计。
        var bandPower = 0.0
        var bandPeak = 0.0
        for (k in 1 until FFT_SIZE / 2) {
            val f = k * binHz
            if (f < F_MIN || f > F_MAX) continue
            val p = re[k] * re[k] + im[k] * im[k]
            bandPower += p
            if (p > bandPeak) bandPeak = p
        }
        val tonality = if (bandPower > 1e-20) bandPeak / bandPower else 0.0

        // 构造解析信号频谱：负频置零、正频加倍；同时做 [F_MIN,F_MAX] 带通。
        for (k in 1 until FFT_SIZE / 2) {
            val f = k * binHz
            if (f >= F_MIN && f <= F_MAX) {
                re[k] *= 2.0
                im[k] *= 2.0
            } else {
                re[k] = 0.0
                im[k] = 0.0
            }
            re[FFT_SIZE - k] = 0.0
            im[FFT_SIZE - k] = 0.0
        }
        re[0] = 0.0; im[0] = 0.0
        re[FFT_SIZE / 2] = 0.0; im[FFT_SIZE / 2] = 0.0

        ifft(re, im)

        // 解析信号：re = 原信号，im = Hilbert 变换
        for (i in 0 until FFT_SIZE) {
            amp[i] = sqrt(re[i] * re[i] + im[i] * im[i])
            phase[i] = atan2(im[i], re[i])
        }

        // 只在窗口中段统计，避开窗边缘的吉布斯振铃
        val lo = FFT_SIZE / 4
        val hi = FFT_SIZE * 3 / 4
        var peakAmp = 0.0
        for (i in lo..hi) if (amp[i] > peakAmp) peakAmp = amp[i]
        if (peakAmp <= 1e-6) return

        // 解卷绕相位 -> 瞬时频率（幅度加权平均，避开低幅度相位噪声）
        val gate = AMP_GATE * peakAmp
        var sumW = 0.0
        var sumWF = 0.0
        var prevPhase = phase[lo]
        var unwrapped = phase[lo]
        for (i in (lo + 1)..hi) {
            var d = phase[i] - prevPhase
            if (d > PI) d -= 2.0 * PI
            if (d < -PI) d += 2.0 * PI
            unwrapped += d
            prevPhase = phase[i]

            val f = d * rate / (2.0 * PI)
            if (amp[i] >= gate && f >= F_MIN && f <= F_MAX) {
                sumW += amp[i]
                sumWF += amp[i] * f
            }
        }
        val freq = if (sumW > 0.0) sumWF / sumW else 0.0
        val peakNorm = peakAmp.coerceIn(0.0, 1.0)

        // 音调性滞回：明确有声调 -> voiced；明确宽带噪声 -> unvoiced；中间保持上一判定。
        voiced = when {
            tonality >= TONALITY_ON -> true
            tonality <= TONALITY_OFF -> false
            else -> voiced
        }

        if (!hasOutput) {
            smoothFreq = freq
            smoothAmp = peakNorm
            hasOutput = true
        } else {
            // 非对称平滑：测量值明显偏离当前值时快速跟随（降低音高突变/扫频的滞后），
            // 稳态小幅抖动仍用慢系数去抖。
            val alpha = if (abs(freq - smoothFreq) > FAST_DELTA_HZ) SMOOTH_FAST else SMOOTH
            smoothFreq += (freq - smoothFreq) * alpha
            smoothAmp += (peakNorm - smoothAmp) * SMOOTH
        }
        // unvoiced：丢弃音高（下游用谐振驱动），避免宽带内容被映射到无意义的音高。
        if (!voiced) smoothFreq = 0.0
    }

    /** 原地迭代 radix-2 FFT。 */
    private fun fft(r: DoubleArray, i: DoubleArray) {
        val n = r.size
        var j = 0
        for (m in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (m < j) {
                val tr = r[m]; r[m] = r[j]; r[j] = tr
                val ti = i[m]; i[m] = i[j]; i[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var start = 0
            while (start < n) {
                var curRe = 1.0
                var curIm = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val a = start + k
                    val b = a + half
                    val vRe = r[b] * curRe - i[b] * curIm
                    val vIm = r[b] * curIm + i[b] * curRe
                    r[b] = r[a] - vRe
                    i[b] = i[a] - vIm
                    r[a] += vRe
                    i[a] += vIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                start += len
            }
            len = len shl 1
        }
    }

    /** 原地逆 FFT（共轭法：ifft(X) = conj(fft(conj(X))) / N）。 */
    private fun ifft(r: DoubleArray, i: DoubleArray) {
        val n = r.size
        for (k in 0 until n) i[k] = -i[k]
        fft(r, i)
        for (k in 0 until n) {
            r[k] /= n
            i[k] = -i[k] / n
        }
    }
}

/** 读取交错 S16LE PCM 在 [offset] 处的样本（有符号）。 */
private fun readShortLe(pcm: ByteArray, offset: Int): Int {
    if (offset + 1 >= pcm.size) return 0
    val lo = pcm[offset].toInt() and 0xFF
    val hi = pcm[offset + 1].toInt()
    return (hi shl 8) or lo
}
