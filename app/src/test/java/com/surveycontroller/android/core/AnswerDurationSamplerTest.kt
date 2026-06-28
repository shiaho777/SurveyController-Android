package com.surveycontroller.android.core

import com.surveycontroller.android.core.engine.AnswerDurationSampler
import com.surveycontroller.android.core.model.ExecutionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerDurationSamplerTest {
    @Test
    fun fixed_duration_uses_exact_configured_value_instead_of_defaulting_to_ninety() {
        val config = ExecutionConfig(answerDurationRangeSeconds = 120..120)

        assertEquals(120, AnswerDurationSampler.sampleSeconds(config))
    }

    @Test
    fun ranged_duration_stays_inside_configured_bounds() {
        val config = ExecutionConfig(answerDurationRangeSeconds = 12..13)
        val samples = List(40) { AnswerDurationSampler.sampleSeconds(config) }

        assertTrue(samples.all { it in 12..13 })
    }

    @Test
    fun defensive_reversed_range_uses_lower_bound_after_normalization() {
        val config = ExecutionConfig(answerDurationRangeSeconds = 150..100)

        assertEquals(150, AnswerDurationSampler.sampleSeconds(config))
    }

    @Test
    fun defensive_zero_range_never_returns_less_than_one_second() {
        val config = ExecutionConfig(answerDurationRangeSeconds = 0..0)

        assertEquals(1, AnswerDurationSampler.sampleSeconds(config))
    }
}
