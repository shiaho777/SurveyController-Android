package com.surveycontroller.android.core.psychometrics

import com.surveycontroller.android.core.model.ExecutionConfig
import com.surveycontroller.android.core.questions.Probabilities
import kotlin.math.abs
import kotlin.math.floor

/**
 * 信效度题型的整批联合比例优化。复刻 software/core/psychometrics/joint_optimizer.py。
 * 为 target_num 份样本预生成锁定选择，使同维度量表题达到目标 Cronbach α。
 */
object JointOptimizer {
    private val SUPPORTED = setOf("single", "scale", "score", "dropdown", "matrix")
    private val BIAS_CHOICES = setOf("left", "center", "right")
    private const val MICRO_JITTER = 0.03

    fun choiceKey(qIndex: Int, rowIndex: Int? = null): String =
        if (rowIndex == null) "q:$qIndex" else "q:$qIndex:row:$rowIndex"

    data class BlueprintItem(
        val questionIndex: Int,
        val questionType: String,
        val optionCount: Int,
        val bias: String,
        val targetProbabilities: List<Double>,
        val rowIndex: Int? = null,
        val scoreByChoiceIndex: List<Int> = emptyList(),
    ) {
        val key: String get() = choiceKey(questionIndex, rowIndex)
        fun choiceIndexForScore(scoreIndex: Int): Int {
            if (scoreByChoiceIndex.isEmpty()) return scoreIndex.coerceIn(0, optionCount - 1)
            val idx = scoreByChoiceIndex.indexOf(scoreIndex)
            return if (idx >= 0) idx.coerceIn(0, optionCount - 1) else scoreIndex.coerceIn(0, optionCount - 1)
        }
        fun toItem() = PsychometricItem(
            kind = if (questionType == "matrix" && rowIndex != null) "matrix_row" else questionType,
            questionIndex = questionIndex, rowIndex = rowIndex, optionCount = optionCount,
            bias = bias, targetProbabilities = targetProbabilities, scoreByChoiceIndex = scoreByChoiceIndex,
        )
    }

    /** 每份样本的锁定选择计划。 */
    class SamplePlan(private val choices: Map<String, Int>) :
        com.surveycontroller.android.core.questions.PsychoPlanLookup {
        override fun getChoice(questionIndex: Int, rowIndex: Int?): Int? = choices[choiceKey(questionIndex, rowIndex)]
        override fun isLocked(questionIndex: Int, rowIndex: Int?): Boolean = choices.containsKey(choiceKey(questionIndex, rowIndex))
    }

    class AnswerPlan(private val answersBySample: Map<Int, Map<String, Int>>, val sampleCount: Int) {
        fun samplePlan(sampleIndex: Int): SamplePlan =
            SamplePlan(answersBySample[sampleIndex] ?: emptyMap())
    }

    private fun isRandomSentinel(prob: Any?): Boolean {
        if (prob == null) return true
        if (prob is Number) return prob.toInt() == -1
        if (prob is List<*>) {
            return prob.size == 1 && ((prob.firstOrNull() as? Number)?.toDouble() ?: 0.0) < 0.0
        }
        return false
    }

    private fun resolveOptionCount(prob: Any?, metaFallback: Int, default: Int): Int {
        if (prob is List<*> && prob.isNotEmpty() && !isRandomSentinel(prob)) return maxOf(2, prob.size)
        if (metaFallback > 0) return maxOf(2, metaFallback)
        return maxOf(2, default)
    }

    private fun inferBias(prob: Any?, optionCount: Int): String {
        if (prob !is List<*> || prob.isEmpty()) return "center"
        val weights = prob.map { (it as? Number)?.toDouble()?.coerceAtLeast(0.0) ?: 0.0 }
        val total = weights.sum()
        if (total <= 0) return "center"
        val denom = maxOf(1, optionCount - 1)
        val mean = weights.mapIndexed { i, w -> i * w }.sum() / total
        val ratio = mean / denom
        return when { ratio <= 0.4 -> "left"; ratio >= 0.6 -> "right"; else -> "center" }
    }

    private fun resolveBias(rawBias: Any?, prob: Any?, optionCount: Int, rowIndex: Int? = null): String {
        val raw = if (rawBias is List<*> && rowIndex != null) rawBias.getOrNull(rowIndex) else rawBias
        val normalized = raw?.toString()?.trim()?.lowercase().orEmpty()
        return if (normalized in BIAS_CHOICES) normalized else inferBias(prob, optionCount)
    }

    private fun targetProbs(prob: Any?, optionCount: Int, bias: String): List<Double> {
        if (isRandomSentinel(prob)) {
            return if (bias in BIAS_CHOICES) Orientation.buildBiasTargetProbabilities(optionCount, bias)
            else List(optionCount) { 1.0 / optionCount }
        }
        return Probabilities.normalizeDroplistProbs(prob, optionCount)
    }

    fun buildBlueprint(config: ExecutionConfig): Map<String, List<BlueprintItem>> {
        val grouped = LinkedHashMap<String, MutableList<BlueprintItem>>()
        for (qNum in config.questionConfigIndexMap.keys.sorted()) {
            val (qType, startIndex) = config.questionConfigIndexMap[qNum] ?: continue
            if (qType !in SUPPORTED) continue
            val dimension = config.questionDimensionMap[qNum]?.trim().orEmpty()
            if (dimension.isEmpty()) continue
            val meta = config.questionsMetadata[qNum]
            val metaOptionCount = meta?.options ?: 0
            val savedBias = config.questionPsychoBiasMap[qNum]

            when (qType) {
                "single", "scale", "score" -> {
                    val (prob, optionCount, scoreMap) = if (qType == "single") {
                        val sm = config.questionOrdinalScoreMap[qNum] ?: emptyList()
                        if (sm.isEmpty()) continue
                        val p = config.singleProb.getOrNull(startIndex) ?: -1
                        val oc = resolveOptionCount(p, metaOptionCount, sm.size)
                        if (sm.size != oc) continue
                        Triple(p, oc, sm)
                    } else {
                        val p = config.scaleProb.getOrNull(startIndex) ?: -1
                        Triple(p, resolveOptionCount(p, metaOptionCount, 5), emptyList())
                    }
                    val bias = resolveBias(savedBias, prob, optionCount)
                    grouped.getOrPut(dimension) { mutableListOf() }.add(
                        BlueprintItem(qNum, qType, optionCount, bias, targetProbs(prob, optionCount, bias), scoreByChoiceIndex = scoreMap),
                    )
                }
                "dropdown" -> {
                    val p = config.droplistProb.getOrNull(startIndex) ?: -1
                    val oc = resolveOptionCount(p, metaOptionCount, maxOf(metaOptionCount, 2))
                    val bias = resolveBias(savedBias, p, oc)
                    grouped.getOrPut(dimension) { mutableListOf() }.add(
                        BlueprintItem(qNum, "dropdown", oc, bias, targetProbs(p, oc, bias)),
                    )
                }
                "matrix" -> {
                    val rowCount = maxOf(1, meta?.rows ?: 1)
                    for (row in 0 until rowCount) {
                        val p = config.matrixProb.getOrNull(startIndex + row) ?: -1
                        val oc = resolveOptionCount(p, metaOptionCount, maxOf(metaOptionCount, 5))
                        val bias = resolveBias(savedBias, p, oc, row)
                        grouped.getOrPut(dimension) { mutableListOf() }.add(
                            BlueprintItem(qNum, "matrix", oc, bias, targetProbs(p, oc, bias), rowIndex = row),
                        )
                    }
                }
            }
        }
        return grouped
    }

    private fun integerQuotas(target: List<Double>, sampleCount: Int): List<Int> {
        if (sampleCount <= 0) return List(target.size) { 0 }
        val normalized = Orientation.normalizeProbabilityList(target)
        val raw = normalized.map { it * sampleCount }
        val quotas = raw.map { floor(it).toInt() }.toMutableList()
        val remainders = raw.mapIndexed { i, v -> v - quotas[i] }
        var remaining = sampleCount - quotas.sum()
        if (remaining > 0) {
            val ranked = normalized.indices.sortedWith(compareByDescending<Int> { remainders[it] }.thenByDescending { normalized[it] }.thenBy { -it })
            for (i in ranked.take(remaining)) quotas[i]++
        } else if (remaining < 0) {
            val ranked = normalized.indices.sortedWith(compareBy<Int> { remainders[it] }.thenBy { normalized[it] }.thenByDescending { -it })
            for (i in ranked.take(-remaining)) quotas[i] = maxOf(0, quotas[i] - 1)
        }
        return quotas
    }

    private fun assignChoicesFromScores(scores: List<Double>, quotas: List<Int>): List<Int> {
        val sampleCount = scores.size
        val ordered = mutableListOf<Int>()
        quotas.forEachIndexed { opt, q -> repeat(maxOf(0, q)) { ordered.add(opt) } }
        while (ordered.size < sampleCount) ordered.add(maxOf(0, quotas.size - 1))
        val rankedSamples = (0 until sampleCount).sortedBy { scores[it] }
        val assigned = IntArray(sampleCount)
        rankedSamples.forEachIndexed { order, sampleIndex -> assigned[sampleIndex] = ordered[order] }
        return assigned.toList()
    }

    private fun sigmaCandidates(targetAlpha: Double, itemCount: Int): List<Double> {
        val base = maxOf(0.0, PsychoMath.computeSigmaEFromAlpha(targetAlpha, itemCount))
        val raw = listOf(base * 1.5, base * 1.2, base, base * 0.8, base * 0.6, base * 0.4, base * 0.2, 0.1, 0.05)
        val seen = HashSet<Double>()
        val out = mutableListOf<Double>()
        for (r in raw) {
            val s = Math.round(maxOf(0.0, r) * 1e6) / 1e6
            if (seen.add(s)) out.add(s)
        }
        return out
    }

    private fun noiseMatrix(itemCount: Int, sampleCount: Int): List<List<Double>> =
        List(itemCount) { List(sampleCount) { PsychoMath.randn() } }

    private fun alphaFitKey(alpha: Double, targetAlpha: Double): Pair<Double, Int> =
        if (alpha.isNaN()) Double.MAX_VALUE to 1
        else abs(alpha - targetAlpha) to (if (alpha <= targetAlpha + 1e-6) 0 else 1)

    private fun evaluate(
        items: List<BlueprintItem>, sampleCount: Int, sigmaE: Double, theta: List<Double>,
        reversedKeys: Set<String>, standardNoise: List<List<Double>>, microNoise: List<List<Double>>,
    ): Pair<Double, Map<String, List<Int>>> {
        val choicesByItem = HashMap<String, List<Int>>()
        val responseRows = Array(sampleCount) { DoubleArray(items.size) }
        items.forEachIndexed { itemIndex, item ->
            val isReversed = item.key in reversedKeys
            val quotas = integerQuotas(item.targetProbabilities, sampleCount)
            val sign = if (isReversed) -1.0 else 1.0
            val scores = (0 until sampleCount).map { s ->
                sign * theta[s] + sigmaE * standardNoise[itemIndex][s] + MICRO_JITTER * microNoise[itemIndex][s]
            }
            val assignedScores = assignChoicesFromScores(scores, quotas)
            choicesByItem[item.key] = assignedScores.map { item.choiceIndexForScore(it) }
            assignedScores.forEachIndexed { s, scoreIndex ->
                responseRows[s][itemIndex] = if (isReversed) (item.optionCount - scoreIndex).toDouble() else (scoreIndex + 1).toDouble()
            }
        }
        return PsychoMath.cronbachAlpha(responseRows.map { it.toList() }) to choicesByItem
    }

    /** 主入口：构建联合答案计划。无可锁定项时返回 null。 */
    fun build(config: ExecutionConfig): AnswerPlan? {
        val sampleCount = maxOf(0, config.targetNum)
        if (sampleCount <= 0) return null
        val grouped = buildBlueprint(config)
        if (grouped.isEmpty()) return null
        val targetAlpha = PsychoMath.normalizeTargetAlpha(config.psychoTargetAlpha)

        val answersBySample = HashMap<Int, MutableMap<String, Int>>()
        for (i in 0 until sampleCount) answersBySample[i] = HashMap()
        var hasLocked = false

        for ((dimension, items) in grouped) {
            if (dimension.isBlank() || items.size < 2) continue
            val theta = List(sampleCount) { PsychoMath.randn() }
            val standardNoise = noiseMatrix(items.size, sampleCount)
            val microNoise = noiseMatrix(items.size, sampleCount)
            val reversedKeys = Orientation.inferDimension(items.map { it.toItem() }).reversedKeys

            var best: Pair<Double, Map<String, List<Int>>>? = null
            for (sigma in sigmaCandidates(targetAlpha, items.size)) {
                val r = evaluate(items, sampleCount, sigma, theta, reversedKeys, standardNoise, microNoise)
                if (best == null || alphaFitKey(r.first, targetAlpha) < alphaFitKey(best!!.first, targetAlpha)) best = r
            }
            val bestChoices = best?.second ?: continue
            for (item in items) {
                val assigned = bestChoices[item.key] ?: continue
                if (assigned.isEmpty()) continue
                hasLocked = true
                assigned.forEachIndexed { sampleIndex, choice ->
                    answersBySample.getOrPut(sampleIndex) { HashMap() }[item.key] = choice
                }
            }
        }
        if (!hasLocked) return null
        return AnswerPlan(answersBySample, sampleCount)
    }
}

private operator fun Pair<Double, Int>.compareTo(other: Pair<Double, Int>): Int {
    val c = first.compareTo(other.first)
    return if (c != 0) c else second.compareTo(other.second)
}
