package com.surveycontroller.android.core

import com.surveycontroller.android.core.network.MonotoneCubic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonotoneCubicTest {

    @Test
    fun slopes_match_python_reference_for_linear_data() {
        // 线性数据：斜率应处处相等（=2），对齐 compute_monotone_slopes
        val xs = listOf(0.0, 1.0, 2.0, 3.0)
        val ys = listOf(0.0, 2.0, 4.0, 6.0)
        val m = MonotoneCubic.computeSlopes(xs, ys)
        m.forEach { assertEquals(2.0, it, 1e-9) }
    }

    @Test
    fun flat_segment_forces_zero_slope() {
        // 相邻两点相等 → 该段两端斜率被强制为 0（Fritsch–Carlson 单调约束）
        val xs = listOf(0.0, 1.0, 2.0)
        val ys = listOf(5.0, 5.0, 9.0)
        val m = MonotoneCubic.computeSlopes(xs, ys)
        assertEquals(0.0, m[0], 1e-9)
        assertEquals(0.0, m[1], 1e-9)
    }

    @Test
    fun interpolation_preserves_endpoints_and_monotonicity() {
        val xs = listOf(0.0, 1.0, 2.0, 3.0)
        val ys = listOf(0.0, 10.0, 30.0, 100.0)
        val pts = MonotoneCubic.interpolate(xs, ys, segments = 10)
        // 端点保持
        assertEquals(0.0, pts.first().second, 1e-9)
        assertEquals(100.0, pts.last().second, 1e-9)
        // 单调递增不过冲：曲线值始终落在 [0,100]
        pts.forEach { (_, y) -> assertTrue("y=$y", y >= -1e-6 && y <= 100.0 + 1e-6) }
        // 整体非递减
        for (i in 1 until pts.size) assertTrue(pts[i].second >= pts[i - 1].second - 1e-6)
    }

    @Test
    fun single_point_is_safe() {
        val pts = MonotoneCubic.interpolate(listOf(0.0), listOf(7.0))
        assertEquals(1, pts.size)
        assertEquals(7.0, pts[0].second, 1e-9)
    }
}
