package com.surveycontroller.android.core.ai

import com.surveycontroller.android.core.backend.BackendClient
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.questions.AiTextException
import com.surveycontroller.android.core.questions.AiTextProvider
import com.surveycontroller.android.core.questions.RandomText

/**
 * 免费 AI 填空（限时免费，走项目后端）。复刻 call_free_ai_api_async 的客户端调用。
 */
class FreeAiTextProvider(
    private val backend: BackendClient,
    private val systemPrompt: String = "",
) : AiTextProvider {
    override suspend fun generate(question: SurveyQuestionMeta, blankCount: Int, threadName: String): List<String> {
        val questionType = if (blankCount > 1) "multi_fill_blank" else "fill_blank"
        val title = question.title.ifBlank { "请作答" }
        try {
            val answers = backend.freeAiAnswer(title, questionType, blankCount, systemPrompt.ifBlank { null })
            return normalize(answers, blankCount)
        } catch (e: Exception) {
            if (e is AiTextException) throw e
            throw AiTextException("免费 AI 填空失败：${e.message ?: e::class.java.simpleName}", e)
        }
    }

    /** 批量预填：把所有 AI 填空题一次性提交，按题号返回答案。 */
    override suspend fun prefill(questions: List<Pair<SurveyQuestionMeta, Int>>, threadName: String): Map<Int, List<String>> {
        if (questions.isEmpty()) return emptyMap()
        val items = questions.map { (q, blanks) ->
            BackendClient.FreeAiBatchItem(
                itemId = q.num.toString(),
                questionType = if (blanks > 1) "multi_fill_blank" else "fill_blank",
                content = q.title.ifBlank { "请作答" },
                blankCount = blanks,
            )
        }
        try {
            val result = backend.freeAiBatch(items, systemPrompt.ifBlank { null })
            val byNum = HashMap<Int, List<String>>()
            for ((q, blanks) in questions) {
                result[q.num.toString()]?.let { byNum[q.num] = normalize(it, blanks) }
            }
            return byNum
        } catch (e: Exception) {
            if (e is AiTextException) throw e
            throw AiTextException("免费 AI 批量填空失败：${e.message ?: e::class.java.simpleName}", e)
        }
    }

    private fun normalize(answers: List<String>, blankCount: Int): List<String> = when {
        answers.isEmpty() -> throw AiTextException("AI 未返回可用填空内容")
        answers.size >= blankCount -> answers.take(blankCount)
        else -> answers + List(blankCount - answers.size) { answers.last() }
    }
}
