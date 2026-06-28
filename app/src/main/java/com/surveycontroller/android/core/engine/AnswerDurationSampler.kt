package com.surveycontroller.android.core.engine

import com.surveycontroller.android.core.model.ExecutionConfig
import kotlin.random.Random

/**
 * 作答时长采样。固定区间必须严格使用用户设置，不能回落到默认 90 秒。
 */
object AnswerDurationSampler {
    fun sampleSeconds(config: ExecutionConfig): Int {
        val range = config.answerDurationRangeSeconds
        val lo = range.first.coerceAtLeast(1)
        val hi = range.last.coerceAtLeast(lo)
        return if (hi > lo) Random.nextInt(lo, hi + 1) else lo
    }
}
