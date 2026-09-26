package com.zyz4.gkme.input

import kotlin.math.sqrt

/**
 * Shared sensitivity curve used by the gyro / accelerometer stick modes and the on-screen
 * joystick.
 *
 * The curve is stored as a flat `[x0, y0, x1, y1, ...]` list of points in `[0, 1]` (the same
 * format as [com.zyz4.gkme.view.CurveEditorView]). The implicit knots `(0, 0)` and `(1, 1)`
 * are always appended, so a null/empty curve is the identity mapping.
 *
 * Interpolation is a **shape-preserving monotone cubic Hermite spline** (Fritsch–Carlson) rather
 * than a plain Catmull-Rom spline. Catmull-Rom overshoots between knots, so it cannot hold a
 * flat plateau (it dips) or a clean upward bulge (it flattens then jumps); the monotone spline
 * passes through every knot without overshooting and is monotonic on every monotonic interval,
 * while still rounding off local peaks.
 */
object SensitivityCurve {

    /** Evaluates the curve's `y` for an input `t` in `[0, 1]`. Returns [t] for an empty curve. */
    fun evaluate(curve: List<Float>?, t: Float): Float {
        val knots = buildKnots(curve) ?: return t.coerceIn(0f, 1f)
        val n = knots.size
        val cx = t.coerceIn(0f, 1f)
        if (cx <= knots[0].first) return knots[0].second
        if (cx >= knots[n - 1].first) return knots[n - 1].second

        val h = FloatArray(n - 1)
        val d = FloatArray(n - 1)
        for (i in 0 until n - 1) {
            h[i] = knots[i + 1].first - knots[i].first
            d[i] = if (h[i] > 1e-9f) (knots[i + 1].second - knots[i].second) / h[i] else 0f
        }

        // Fritsch–Carlson tangents.
        val m = FloatArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) {
            val d0 = d[i - 1]
            val d1 = d[i]
            if (d0 * d1 <= 0f) {
                // Local extremum: flatten to keep the spline from overshooting.
                m[i] = 0f
            } else {
                val w1 = 2f * h[i] + h[i - 1]
                val w2 = h[i] + 2f * h[i - 1]
                m[i] = (w1 + w2) / (w1 / d0 + w2 / d1)
            }
        }
        for (i in 0 until n - 1) {
            val di = d[i]
            if (di == 0f) {
                m[i] = 0f
                m[i + 1] = 0f
                continue
            }
            var a = m[i] / di
            var b = m[i + 1] / di
            if (a < 0f) { m[i] = 0f; a = 0f }
            if (b < 0f) { m[i + 1] = 0f; b = 0f }
            val s = a * a + b * b
            if (s > 9f) {
                val tau = 3f / sqrt(s)
                m[i] = tau * a * di
                m[i + 1] = tau * b * di
            }
        }

        var seg = 0
        for (i in 0 until n - 1) {
            if (cx >= knots[i].first && cx <= knots[i + 1].first) {
                seg = i
                break
            }
        }

        val x0 = knots[seg].first
        val y0 = knots[seg].second
        val y1 = knots[seg + 1].second
        val hh = h[seg]
        val tt = if (hh > 1e-9f) (cx - x0) / hh else 0f
        val t2 = tt * tt
        val t3 = t2 * tt
        return (2f * t3 - 3f * t2 + 1f) * y0 +
            (t3 - 2f * t2 + tt) * hh * m[seg] +
            (-2f * t3 + 3f * t2) * y1 +
            (t3 - t2) * hh * m[seg + 1]
    }

    /**
     * Remaps the vector `(x, y)` by applying [curve] to its magnitude, keeping its direction.
     * [fullScale] is the input magnitude that corresponds to the curve's `x = 1`. Inputs beyond
     * [fullScale] keep their magnitude (the curve endpoint slope is treated as 1), so fast inputs
     * still grow linearly. Returns `(x, y)` unchanged for an empty curve.
     */
    fun applyRadial(curve: List<Float>?, x: Float, y: Float, fullScale: Float): Pair<Float, Float> {
        if (fullScale <= 0f) return x to y
        val mag = sqrt(x * x + y * y)
        if (mag < 1e-6f) return x to y
        val normalized = (mag / fullScale).coerceIn(0f, 1f)
        if (normalized <= 1e-6f) return x to y
        val shaped = evaluate(curve, normalized)
        val factor = shaped / normalized
        return x * factor to y * factor
    }

    /**
     * Builds the knot list: `(0, 0)`, the user points (clamped, sorted, x-deduplicated, points at
     * the endpoints dropped so the implicit anchors win) and `(1, 1)`. Returns null when there is
     * no usable user point.
     */
    private fun buildKnots(curve: List<Float>?): List<Pair<Float, Float>>? {
        if (curve == null || curve.size < 2) return null
        val raw = ArrayList<Pair<Float, Float>>(curve.size / 2)
        var i = 0
        while (i + 1 < curve.size) {
            val x = curve[i]
            val y = curve[i + 1]
            if (x.isFinite() && y.isFinite()) raw.add(x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f))
            i += 2
        }
        if (raw.isEmpty()) return null
        val knots = ArrayList<Pair<Float, Float>>(raw.size + 2)
        knots.add(0f to 0f)
        for (p in raw.sortedBy { it.first }) {
            if (p.first <= 1e-6f || p.first >= 1f - 1e-6f) continue
            if (p.first - knots[knots.size - 1].first <= 1e-6f) {
                knots[knots.size - 1] = p
            } else {
                knots.add(p)
            }
        }
        knots.add(1f to 1f)
        return knots
    }
}
