package com.surveycontroller.android.provider.tencent

import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * 腾讯问卷提交成功后的服务端落库确认。
 */
object TencentSubmitVerifier {
    private data class SessionState(
        val lastSubmittedAt: Long,
        val lastAnswerId: Long,
    )

    suspend fun confirmPersisted(
        answerSessionId: String,
        initialSessionData: JSONObject,
        submitPayload: JSONObject,
        fetchSessionData: suspend () -> JSONObject,
        maxAttempts: Int = 3,
        delayMs: Long = 200,
    ) {
        val answerHash = submitPayload.optJSONObject("data")
            ?.optString("answer_hash")
            ?.trim()
            .orEmpty()
        if (answerHash.isEmpty()) {
            error("腾讯问卷提交返回缺少 answer_hash，无法确认服务端是否已收录")
        }
        if (answerSessionId.isBlank()) return

        val initial = sessionState(initialSessionData)
        val attempts = maxAttempts.coerceAtLeast(1)
        repeat(attempts) { attempt ->
            val current = sessionState(fetchSessionData())
            if (current.lastSubmittedAt > initial.lastSubmittedAt) return
            if (current.lastAnswerId > 0 && current.lastAnswerId != initial.lastAnswerId) return
            if (attempt < attempts - 1 && delayMs > 0) delay(delayMs)
        }
        error("腾讯问卷提交后未确认到服务端已记录答案")
    }

    private fun sessionState(data: JSONObject): SessionState {
        val answerSession = data.optJSONObject("answer_session")
        return SessionState(
            lastSubmittedAt = answerSession?.optLong("last_submitted_at", 0L) ?: 0L,
            lastAnswerId = answerSession?.optLong("last_answer_id", 0L) ?: 0L,
        )
    }
}
