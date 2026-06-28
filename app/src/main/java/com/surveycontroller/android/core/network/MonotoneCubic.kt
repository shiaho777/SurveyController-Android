package com.surveycontroller.android.core.network

import kotlin.math.sqrt

/**
 * 单调三次（Fritsch–Carlson）插值。1:1 复刻 software/ui/pages/more/ip_usage_math.py，
 * 用于 IP 用量折线图的平滑曲线绘制（不过冲、保持单调）。
 */
object MonotoneCubic {

    /** 计算各节点切线斜率。 */
    fun computeSlopes(xs: List<Double>, ys: List<Double>): DoubleArray {
        val n = xs.size
        if (n == 0) return DoubleArray(0)
        if (n == 1) return doubleArrayOf(0.0)
        val d = DoubleArray(n - 1) { i -> (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]) }
        val m = DoubleArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = (d[i - 1] + d[i]) / 2.0
        for (i in 0 until n - 1) {
            if (kotlin.math.abs(d[i]) < 1e-10) {
                m[i] = 0.0
                m[i + 1] = 0.0
            } else {
                val a = m[i] / d[i]
                val b = m[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9) {
                    val t = 3.0 / sqrt(s)
                    m[i] = t * a * d[i]
                    m[i + 1] = t * b * d[i]
                }
            }
        }
        return m
    }

    /**
     * 在区间内按每段 segments 个采样点生成平滑曲线点序列（含端点）。
     * 返回 (x, y) 列表。
     */
    fun interpolate(xs: List<Double>, ys: List<Double>, segments: Int = 12): List<Pair<Double, Double>> {
        val n = xs.size
        if (n == 0) return emptyList()
        if (n == 1) return listOf(xs[0] to ys[0])
        val ms = computeSlopes(xs, ys)
        val out = ArrayList<Pair<Double, Double>>()
        for (i in 0 until n - 1) {
            val h = xs[i + 1] - xs[i]
            for (j in 0 until segments) {
                val t = j.toDouble() / segments
                val t2 = t * t
                val t3 = t2 * t
                val yi = (2 * t3 - 3 * t2 + 1) * ys[i] +
                    (t3 - 2 * t2 + t) * h * ms[i] +
                    (-2 * t3 + 3 * t2) * ys[i + 1] +
                    (t3 - t2) * h * ms[i + 1]
                out.add((xs[i] + t * h) to yi)
            }
        }
        out.add(xs[n - 1] to ys[n - 1])
        return out
    }
}
