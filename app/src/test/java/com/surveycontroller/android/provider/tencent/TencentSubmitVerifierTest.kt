package com.surveycontroller.android.provider.tencent

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TencentSubmitVerifierTest {

    @Test
    fun confirm_persisted_accepts_advanced_submitted_time() = runBlocking {
        var calls = 0

        TencentSubmitVerifier.confirmPersisted(
            answerSessionId = "session-1",
            initialSessionData = session(lastSubmittedAt = 100, lastAnswerId = 10),
            submitPayload = submitPayload("hash-1"),
            maxAttempts = 3,
            delayMs = 0,
            fetchSessionData = {
                calls += 1
                session(lastSubmittedAt = 101, lastAnswerId = 10)
            },
        )

        assertEquals(1, calls)
    }

    @Test
    fun confirm_persisted_accepts_changed_answer_id() = runBlocking {
        var calls = 0

        TencentSubmitVerifier.confirmPersisted(
            answerSessionId = "session-1",
            initialSessionData = session(lastSubmittedAt = 100, lastAnswerId = 10),
            submitPayload = submitPayload("hash-1"),
            maxAttempts = 3,
            delayMs = 0,
            fetchSessionData = {
                calls += 1
                session(lastSubmittedAt = 100, lastAnswerId = 11)
            },
        )

        assertEquals(1, calls)
    }

    @Test
    fun confirm_persisted_fails_when_answer_hash_is_missing() = runBlocking {
        try {
            TencentSubmitVerifier.confirmPersisted(
                answerSessionId = "session-1",
                initialSessionData = session(lastSubmittedAt = 100, lastAnswerId = 10),
                submitPayload = JSONObject("""{"code":"OK","data":{}}"""),
                maxAttempts = 1,
                delayMs = 0,
                fetchSessionData = { session(lastSubmittedAt = 101, lastAnswerId = 10) },
            )
            fail("Expected missing answer_hash to fail")
        } catch (e: IllegalStateException) {
            assertEquals("腾讯问卷提交返回缺少 answer_hash，无法确认服务端是否已收录", e.message)
        }
    }

    @Test
    fun confirm_persisted_fails_when_session_state_does_not_advance() = runBlocking {
        var calls = 0

        try {
            TencentSubmitVerifier.confirmPersisted(
                answerSessionId = "session-1",
                initialSessionData = session(lastSubmittedAt = 100, lastAnswerId = 10),
                submitPayload = submitPayload("hash-1"),
                maxAttempts = 2,
                delayMs = 0,
                fetchSessionData = {
                    calls += 1
                    session(lastSubmittedAt = 100, lastAnswerId = 10)
                },
            )
            fail("Expected unchanged session state to fail")
        } catch (e: IllegalStateException) {
            assertEquals("腾讯问卷提交后未确认到服务端已记录答案", e.message)
        }

        assertEquals(2, calls)
    }

    @Test
    fun confirm_persisted_skips_session_polling_without_answer_session_id() = runBlocking {
        var calls = 0

        TencentSubmitVerifier.confirmPersisted(
            answerSessionId = "",
            initialSessionData = session(lastSubmittedAt = 100, lastAnswerId = 10),
            submitPayload = submitPayload("hash-1"),
            maxAttempts = 3,
            delayMs = 0,
            fetchSessionData = {
                calls += 1
                session(lastSubmittedAt = 100, lastAnswerId = 10)
            },
        )

        assertEquals(0, calls)
    }

    private fun session(lastSubmittedAt: Long, lastAnswerId: Long): JSONObject =
        JSONObject()
            .put(
                "answer_session",
                JSONObject()
                    .put("last_submitted_at", lastSubmittedAt)
                    .put("last_answer_id", lastAnswerId),
            )

    private fun submitPayload(answerHash: String): JSONObject =
        JSONObject()
            .put("code", "OK")
            .put("data", JSONObject().put("answer_hash", answerHash))
}
