package com.surveycontroller.android.core.questions

import com.surveycontroller.android.core.model.ExecutionConfig
import com.surveycontroller.android.core.model.ExecutionState
import com.surveycontroller.android.core.model.SurveyQuestionMeta

/** 反向填充答案（按题号返回固定答案）。阶段5实现。 */
interface ReverseFillResolver {
    fun resolve(questionNum: Int, threadName: String): ReverseFillAnswer?
}

data class ReverseFillAnswer(
    val kind: String,                       // choice / text / multi_text / matrix
    val choiceIndex: Int? = null,
    val choiceIndexes: List<Int> = emptyList(),
    val textValue: String? = null,
    val textValues: List<String> = emptyList(),
    val matrixChoiceIndexes: List<Int> = emptyList(),
    val sliderValue: Double? = null,
    val locationParts: List<String> = emptyList(),
)

/** AI 填空文本提供者。阶段6实现。 */
interface AiTextProvider {
    suspend fun generate(question: SurveyQuestionMeta, blankCount: Int, threadName: String): List<String>

    /** 批量预填（免费 AI 批量模式）。默认无批量能力，返回空。 */
    suspend fun prefill(questions: List<Pair<SurveyQuestionMeta, Int>>, threadName: String): Map<Int, List<String>> = emptyMap()
}

class AiTextException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 人设权重提升。阶段6实现，默认透传。 */
interface PersonaBoost {
    fun boost(optionTexts: List<String>, probabilities: List<Double>): List<Double> = probabilities
    fun gender(): String? = null
}

object NoPersonaBoost : PersonaBoost

/**
 * 答案生成所需的运行时上下文。统一传给各 Provider 的答案构建器。
 */
class AnswerContext(
    val config: ExecutionConfig,
    val state: ExecutionState,
    val threadName: String = "",
    val tendency: TendencyState = TendencyState(),
    val reverseFill: ReverseFillResolver? = null,
    val aiText: AiTextProvider? = null,
    val persona: PersonaBoost = NoPersonaBoost,
    val psychoPlan: PsychoPlanLookup? = null,
    val allowAiPlaceholder: Boolean = false,
) {
    /** 本份问卷的已答记录（供条件规则引用）。 */
    val answered: AnsweredTracker = AnsweredTracker()

    /** 条件规则引擎（从 config.answerRules 解析）。 */
    val consistency: ConsistencyEngine = ConsistencyEngine(
        rules = config.answerRules.mapNotNull { AnswerRule.fromMap(it) },
        tracker = answered,
    )

    fun dimensionOf(questionNum: Int): String? = config.questionDimensionMap[questionNum]

    fun hasReliabilityDimension(questionNum: Int): Boolean =
        !dimensionOf(questionNum).isNullOrBlank()

    fun isStrictRatio(questionNum: Int): Boolean =
        config.questionStrictRatioMap[questionNum] == true
}
