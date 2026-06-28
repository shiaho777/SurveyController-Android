package com.surveycontroller.android.core

import com.surveycontroller.android.core.questions.TendencyWeights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TendencyWeightsTest {

    @Test
    fun left_bias_descends_first_option_highest() {
        val w = TendencyWeights.buildBiasWeights(5, TendencyWeights.LEFT)
        assertEquals(5, w.size)
        assertEquals(100.0, w.first(), 0.001)        // 第一项最高
        assertTrue(w[0] > w[1] && w[1] >= w[2])       // 单调下降
        assertEquals(0.0, w.last(), 0.001)            // 末项被压制为 0
    }

    @Test
    fun right_bias_mirror_of_left() {
        val left = TendencyWeights.buildBiasWeights(5, TendencyWeights.LEFT)
        val right = TendencyWeights.buildBiasWeights(5, TendencyWeights.RIGHT)
        assertEquals(left, right.reversed())
    }

    @Test
    fun center_bias_peaks_in_middle() {
        val w = TendencyWeights.buildBiasWeights(5, TendencyWeights.CENTER)
        assertEquals(100.0, w[2], 0.001)              // 中间最高
        assertTrue(w[2] > w[0] && w[2] > w[4])
        assertEquals(w[0], w[4], 0.001)               // 两端对称
    }

    @Test
    fun single_option_is_full_weight() {
        assertEquals(listOf(100.0), TendencyWeights.buildBiasWeights(1, TendencyWeights.LEFT))
    }
}
