package com.surveycontroller.android.core.questions

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 倾向预设权重生成。1:1 复刻桌面端 psycho_config.build_bias_weights。
 * 偏左：前面选项权重高；偏右：后面选项权重高；居中：中间高两端低。
 * 居中用 3 次曲线（两端适度衰减），左右倾向用 8 次曲线（极端压制低端）。
 */
object TendencyWeights {

    const val LEFT = "left"
    const val CENTER = "center"
    const val RIGHT = "right"
    const val CUSTOM = "custom"

    /** 倾向预设候选（值 to 显示名）。 */
    val PRESETS = listOf(
        LEFT to "偏左",
        CENTER to "居中",
        RIGHT to "偏右",
    )

    /** 根据倾向方向生成归一化到 0-100 的整数权重。 */
    fun buildBiasWeights(optionCount: Int, bias: String): List<Double> {
        val count = maxOf(1, optionCount)
        if (count == 1) return listOf(100.0)
        val linear: List<Double> = when (bias) {
            LEFT -> List(count) { i -> 1.0 - i.toDouble() / (count - 1) }
            RIGHT -> List(count) { i -> i.toDouble() / (count - 1) }
            else -> {
                val center = (count - 1) / 2.0
                List(count) { i -> 1.0 - abs(i - center) / center }
            }
        }
        val power = if (bias == CENTER) 3.0 else 8.0
        val raw = linear.map { it.pow(power) }
        val maxVal = raw.maxOrNull() ?: 0.0
        if (maxVal == 0.0) {
            val each = (100.0 / count).roundToInt().toDouble()
            return List(count) { each }
        }
        return raw.map { (it / maxVal * 100).roundToInt().toDouble() }
    }
}
