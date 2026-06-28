package com.surveycontroller.android.core.psychometrics

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 心理测量数学工具。复刻 software/core/psychometrics/utils.py。
 */
object PsychoMath {
    private val A = doubleArrayOf(-39.69683028665376, 220.9460984245205, -275.9285104469687, 138.3577518672690, -30.66479806614716, 2.506628277459239)
    private val B = doubleArrayOf(-54.47609879822406, 161.5858368580409, -155.6989798598866, 66.80131188771972, -13.28068155288572)
    private val C = doubleArrayOf(-0.007784894002430293, -0.3223964580411365, -2.400758277161838, -2.549732539343734, 4.374664141464968, 2.938163982698783)
    private val D = doubleArrayOf(0.007784695709041462, 0.3224671290700398, 2.445134137142996, 3.754408661907416)

    fun randn(): Double {
        var u = 0.0; var v = 0.0
        while (u == 0.0) u = Random.nextDouble()
        while (v == 0.0) v = Random.nextDouble()
        return sqrt(-2.0 * ln(u)) * cos(2.0 * Math.PI * v)
    }

    fun normalInv(p: Double): Double {
        if (p <= 0) return Double.NEGATIVE_INFINITY
        if (p >= 1) return Double.POSITIVE_INFINITY
        val plow = 0.02425
        val phigh = 1 - plow
        if (p < plow) {
            val q = sqrt(-2 * ln(p))
            return (((((C[0] * q + C[1]) * q + C[2]) * q + C[3]) * q + C[4]) * q + C[5]) /
                ((((D[0] * q + D[1]) * q + D[2]) * q + D[3]) * q + 1)
        }
        if (p > phigh) {
            val q = sqrt(-2 * ln(1 - p))
            return -(((((C[0] * q + C[1]) * q + C[2]) * q + C[3]) * q + C[4]) * q + C[5]) /
                ((((D[0] * q + D[1]) * q + D[2]) * q + D[3]) * q + 1)
        }
        val q = p - 0.5
        val r = q * q
        return (((((A[0] * r + A[1]) * r + A[2]) * r + A[3]) * r + A[4]) * r + A[5]) * q /
            (((((B[0] * r + B[1]) * r + B[2]) * r + B[3]) * r + B[4]) * r + 1)
    }

    fun zToCategory(z: Double, optionCount: Int): Int {
        val m = optionCount.coerceIn(2, 50)
        for (j in 1 until m) {
            if (z <= normalInv(j.toDouble() / m)) return j - 1
        }
        return m - 1
    }

    fun variance(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
    }

    fun cronbachAlpha(matrix: List<List<Double>>): Double {
        if (matrix.isEmpty()) return 0.0
        val k = matrix[0].size
        if (k < 2) return 0.0
        val totals = matrix.map { it.sum() }
        val varTotal = variance(totals)
        if (varTotal == 0.0) return 0.0
        var sumItemVar = 0.0
        for (j in 0 until k) sumItemVar += variance(matrix.map { it[j] })
        return (k.toDouble() / (k - 1)) * (1 - sumItemVar / varTotal)
    }

    fun computeRhoFromAlpha(alpha: Double, k: Int): Double {
        if (alpha <= 0 || alpha >= 1 || k < 2) return 0.2
        val denom = k - alpha * (k - 1)
        if (denom <= 0) return 0.2
        return (alpha / denom).coerceIn(1e-6, 0.999999)
    }

    fun computeSigmaEFromAlpha(alpha: Double, k: Int): Double {
        val rho = computeRhoFromAlpha(alpha, k)
        return sqrt((1 / rho) - 1)
    }

    fun normalizeTargetAlpha(value: Double?): Double {
        val a = value ?: 0.85
        if (a.isNaN()) return 0.85
        return a.coerceIn(0.60, 0.95)
    }
}

/**
 * 目标配比与方向推断。复刻 orientation.py。
 */
object Orientation {
    private const val LEFT = 0.4
    private const val RIGHT = 0.6
    private const val DOMINANCE = 1.15
    private const val MIN_ANCHOR = 0.2

    fun normalizeProbabilityList(values: List<Double>): List<Double> {
        val cleaned = values.map { v -> if (v.isNaN() || v.isInfinite() || v < 0) 0.0 else v }
        val total = cleaned.sum()
        return when {
            total > 0 -> cleaned.map { it / total }
            cleaned.isEmpty() -> emptyList()
            else -> List(cleaned.size) { 1.0 / cleaned.size }
        }
    }

    fun buildBiasTargetProbabilities(optionCount: Int, bias: String): List<Double> {
        val count = optionCount.coerceAtLeast(2)
        if (count == 2) return when (bias) {
            "left" -> listOf(0.75, 0.25)
            "right" -> listOf(0.25, 0.75)
            else -> listOf(0.5, 0.5)
        }
        val linear = when (bias) {
            "left" -> (0 until count).map { 1.0 - it.toDouble() / (count - 1) }
            "right" -> (0 until count).map { it.toDouble() / (count - 1) }
            else -> {
                val center = (count - 1) / 2.0
                (0 until count).map { 1.0 - abs(it - center) / maxOf(center, 1.0) }
            }
        }
        val power = if (bias == "center") 3 else 8
        return normalizeProbabilityList(linear.map { maxOf(it, 0.0).pow(power) })
    }

    data class ItemOrientation(val choiceKey: String, val meanRatio: Double, val direction: String, val skewStrength: Double)
    data class DimensionOrientation(
        val itemOrientations: Map<String, ItemOrientation>,
        val anchorDirection: String,
        val anchorStrength: Double,
        val reversedKeys: Set<String>,
        val ambiguousAnchor: Boolean,
    )

    private fun meanRatio(probs: List<Double>, optionCount: Int): Double {
        if (probs.isEmpty()) return 0.5
        val denom = maxOf(1, optionCount - 1)
        val weighted = probs.mapIndexed { i, w -> i * w }.sum()
        return (weighted / denom).coerceIn(0.0, 1.0)
    }

    private fun directionFromRatio(r: Double): String = when {
        r <= LEFT -> "left"
        r >= RIGHT -> "right"
        else -> "center"
    }

    fun inferItem(item: PsychometricItem): ItemOrientation {
        val probs = item.targetProbabilities.ifEmpty { buildBiasTargetProbabilities(item.optionCount, item.bias) }
        val ratio = meanRatio(normalizeProbabilityList(probs), item.optionCount)
        return ItemOrientation(item.choiceKey, ratio, directionFromRatio(ratio), abs(ratio - 0.5))
    }

    fun inferDimension(items: List<PsychometricItem>): DimensionOrientation {
        val orientations = HashMap<String, ItemOrientation>()
        var left = 0.0; var right = 0.0
        for (item in items) {
            val o = inferItem(item)
            orientations[o.choiceKey] = o
            if (o.direction == "left") left += o.skewStrength
            else if (o.direction == "right") right += o.skewStrength
        }
        val anchorDirection: String; val anchorStrength: Double; val weaker: Double
        when {
            left > right -> { anchorDirection = "left"; anchorStrength = left; weaker = right }
            right > left -> { anchorDirection = "right"; anchorStrength = right; weaker = left }
            else -> { anchorDirection = "center"; anchorStrength = left; weaker = right }
        }
        val ambiguous = anchorDirection == "center" || anchorStrength < MIN_ANCHOR || anchorStrength <= weaker * DOMINANCE
        val reversed = if (!ambiguous) orientations.values
            .filter { it.direction in listOf("left", "right") && it.direction != anchorDirection }
            .map { it.choiceKey }.toSet() else emptySet()
        return DimensionOrientation(orientations, anchorDirection, anchorStrength, reversed, ambiguous)
    }
}

/** 信效度题目项。复刻 PsychometricItem。 */
data class PsychometricItem(
    val kind: String,
    val questionIndex: Int,
    val rowIndex: Int? = null,
    val optionCount: Int = 5,
    val bias: String = "center",
    val targetProbabilities: List<Double> = emptyList(),
    val scoreByChoiceIndex: List<Int> = emptyList(),
) {
    val choiceKey: String get() = if (rowIndex != null) "q:$questionIndex:row:$rowIndex" else "q:$questionIndex"

    fun choiceIndexForScore(scoreIndex: Int): Int {
        if (scoreByChoiceIndex.isEmpty()) return scoreIndex.coerceIn(0, optionCount - 1)
        val idx = scoreByChoiceIndex.indexOf(scoreIndex)
        return if (idx >= 0) idx.coerceIn(0, optionCount - 1) else scoreIndex.coerceIn(0, optionCount - 1)
    }
}
