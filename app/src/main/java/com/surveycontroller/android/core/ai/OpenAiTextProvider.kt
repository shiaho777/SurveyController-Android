package com.surveycontroller.android.core.ai

import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.network.HttpClient
import com.surveycontroller.android.core.questions.AiTextException
import com.surveycontroller.android.core.questions.AiTextProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 兼容接口的 AI 填空实现。对应桌面端 software/core/ai/runtime.py 的自定义 API 模式。
 */
class OpenAiTextProvider(
    private val http: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val systemPrompt: String,
    private val apiProtocol: String = "auto",
) : AiTextProvider {

    private companion object {
        const val PREFILL_MAX_CONCURRENCY = 4
    }

    override suspend fun generate(question: SurveyQuestionMeta, blankCount: Int, threadName: String): List<String> {
        val endpoint = resolveEndpoint(baseUrl, apiProtocol)
        val sys = systemPrompt.ifBlank {
            "你是问卷填写助手。根据题目生成简短、自然、合理的中文作答内容，只输出答案本身，不要解释。"
        }
        val userPrompt = buildString {
            append("题目：").append(question.title.ifBlank { "请作答" })
            if (blankCount > 1) append("\n需要生成 $blankCount 条作答，用换行分隔。")
        }
        try {
            val content = try {
                execute(endpoint.protocol, endpoint.url, sys, userPrompt)
            } catch (e: AiTextException) {
                if (!shouldFallbackToResponses(endpoint, e)) throw e
                execute("responses", endpoint.responsesFallbackUrl, sys, userPrompt)
            }
            val lines = content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            return when {
                lines.isEmpty() -> throw AiTextException("自定义 AI 未返回可用填空内容")
                lines.size >= blankCount -> lines.take(blankCount)
                else -> lines + List(blankCount - lines.size) { lines.last() }
            }
        } catch (e: Exception) {
            if (e is AiTextException) throw e
            throw AiTextException("自定义 AI 填空失败：${e.message ?: e::class.java.simpleName}", e)
        }
    }

    /**
     * v4.0.3：自定义服务商批量预填。复刻 software/core/ai/batch_runtime.py:_wait_provider_batch_result_async。
     * 信号量限流 = 4，逐题调 generate；失败题收集到 failed，若非空抛异常停止本轮提交。
     */
    override suspend fun prefill(
        questions: List<Pair<SurveyQuestionMeta, Int>>,
        threadName: String,
    ): Map<Int, List<String>> {
        if (questions.isEmpty()) return emptyMap()
        val semaphore = Semaphore(PREFILL_MAX_CONCURRENCY)
        val completed = linkedMapOf<Int, List<String>>()
        val failed = linkedMapOf<Int, String>()
        coroutineScope {
            questions.map { (q, blankCount) ->
                async {
                    semaphore.withPermit {
                        try {
                            val answers = generate(q, blankCount, threadName)
                            completed[q.num] = normalizeProviderItemAnswers(answers.joinToString("||"), blankCount)
                        } catch (e: Exception) {
                            failed[q.num] = e.message ?: "AI 调用失败"
                        }
                    }
                }
            }.awaitAll()
        }
        if (failed.isNotEmpty()) {
            val detail = failed.entries.take(5).joinToString("；") { "第${it.key}题：${it.value}" }
            throw AiTextException("AI 批量预取未完成，已停止本轮提交：$detail")
        }
        return completed
    }

    /**
     * v4.0.3：复刻 batch_runtime.py:_normalize_provider_item_answers。
     * multi 题（blankCount > 1）用 || 拆分，校验空答案和数量不匹配。
     */
    private fun normalizeProviderItemAnswers(rawAnswer: String, blankCount: Int): List<String> {
        val answers = if (blankCount > 1) {
            rawAnswer.split("||").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            listOf(rawAnswer.trim()).filter { it.isNotEmpty() }
        }
        if (answers.isEmpty()) throw AiTextException("AI 未返回有效答案")
        if (blankCount > 0 && answers.size != blankCount) {
            throw AiTextException("期望 $blankCount 个答案，实际返回 ${answers.size} 个")
        }
        return answers
    }

    private suspend fun execute(protocol: String, endpoint: String, sys: String, userPrompt: String): String {
        val body = if (protocol == "responses") responsesBody(sys, userPrompt) else chatBody(sys, userPrompt)
        val resp = http.postBody(
            url = endpoint,
            body = body.toString(),
            headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "application/json",
            ),
            timeoutSeconds = 30,
        )
        if (resp.statusCode !in 200..299) {
            throw AiTextException("自定义 AI 返回 HTTP ${resp.statusCode}")
        }
        val payload = JSONObject(resp.body.ifBlank { "{}" })
        return if (protocol == "responses") {
            extractResponsesText(payload)
        } else {
            extractChatCompletionText(payload)
        }
    }

    private fun chatBody(sys: String, userPrompt: String): JSONObject =
        JSONObject()
            .put("model", model.ifBlank { "gpt-3.5-turbo" })
            .put("max_tokens", 200)
            .put("temperature", 0.7)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", sys))
                    .put(JSONObject().put("role", "user").put("content", userPrompt)),
            )

    private fun responsesBody(sys: String, userPrompt: String): JSONObject =
        JSONObject()
            .put("model", model.ifBlank { "gpt-3.5-turbo" })
            .put("instructions", sys)
            .put("input", userPrompt)
            .put("max_output_tokens", 200)
            .put("temperature", 0.7)

    private data class EndpointResolution(
        val protocol: String,
        val url: String,
        val hasExplicitEndpoint: Boolean,
        val requestedProtocol: String,
        val baseUrl: String,
    ) {
        val responsesFallbackUrl: String get() = "$baseUrl/responses"
    }

    private fun shouldFallbackToResponses(endpoint: EndpointResolution, error: AiTextException): Boolean =
        endpoint.requestedProtocol == "auto" &&
            !endpoint.hasExplicitEndpoint &&
            isEndpointMismatch(error.message.orEmpty())

    private fun resolveEndpoint(rawBaseUrl: String, rawProtocol: String): EndpointResolution {
        val normalized = rawBaseUrl.trim().trimEnd('/')
        if (normalized.isEmpty()) {
            throw AiTextException("自定义 AI Base URL 为空")
        }
        val lower = normalized.lowercase()
        if (lower.endsWith("/chat/completions")) {
            return EndpointResolution("chat_completions", normalized, hasExplicitEndpoint = true, requestedProtocol = normalizeProtocol(rawProtocol), baseUrl = baseEndpointUrl(normalized, "/chat/completions"))
        }
        if (lower.endsWith("/responses")) {
            return EndpointResolution("responses", normalized, hasExplicitEndpoint = true, requestedProtocol = normalizeProtocol(rawProtocol), baseUrl = baseEndpointUrl(normalized, "/responses"))
        }
        if (lower.endsWith("/completions") && !lower.endsWith("/chat/completions")) {
            throw AiTextException("暂不支持旧版 /completions 协议，请改用 /chat/completions 或 /responses")
        }
        val protocol = normalizeProtocol(rawProtocol)
        return if (protocol == "responses") {
            EndpointResolution("responses", "$normalized/responses", hasExplicitEndpoint = false, requestedProtocol = protocol, baseUrl = normalized)
        } else {
            EndpointResolution("chat_completions", "$normalized/chat/completions", hasExplicitEndpoint = false, requestedProtocol = protocol, baseUrl = normalized)
        }
    }

    private fun normalizeProtocol(rawProtocol: String): String =
        when (rawProtocol.trim().lowercase()) {
            "responses" -> "responses"
            "chat_completions" -> "chat_completions"
            else -> "auto"
        }

    private fun baseEndpointUrl(endpoint: String, suffix: String): String =
        endpoint.removeSuffix(suffix).trimEnd('/')

    private fun extractChatCompletionText(payload: JSONObject): String {
        val choices = payload.optJSONArray("choices") ?: return ""
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
        return extractTextParts(message.opt("content")).joinToString("\n").trim()
    }

    private fun extractResponsesText(payload: JSONObject): String {
        payload.optString("output_text").trim().takeIf { it.isNotEmpty() }?.let { return it }
        val output = payload.optJSONArray("output") ?: return ""
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                val text = extractTextParts(part).joinToString("\n").trim()
                if (text.isNotEmpty()) return text
            }
        }
        return ""
    }

    private fun extractTextParts(value: Any?): List<String> {
        if (value is String) return listOf(value.trim()).filter { it.isNotEmpty() }
        if (value is JSONArray) {
            val parts = mutableListOf<String>()
            for (i in 0 until value.length()) {
                parts.addAll(extractTextParts(value.opt(i)))
            }
            return parts
        }
        if (value is JSONObject) {
            val type = value.optString("type").trim().lowercase()
            if (type !in setOf("text", "output_text", "input_text")) return emptyList()
            val text = value.optString("text").ifBlank { value.optString("content") }.trim()
            return listOf(text).filter { it.isNotEmpty() }
        }
        return emptyList()
    }

    private fun isEndpointMismatch(message: String): Boolean {
        val lower = message.lowercase()
        return listOf(
            "404",
            "405",
            "410",
            "not found",
            "no route",
            "no handler",
            "unsupported path",
            "invalid url",
            "method not allowed",
        ).any { lower.contains(it) }
    }
}
