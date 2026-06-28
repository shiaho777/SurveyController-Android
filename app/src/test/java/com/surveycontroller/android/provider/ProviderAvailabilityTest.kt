package com.surveycontroller.android.provider

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAvailabilityTest {
    @Test
    fun detects_unavailable_messages_from_api_payloads() {
        val payload = JSONObject()
            .put("code", "SURVEY_CLOSED")
            .put("message", "问卷已关闭，无法继续作答")

        assertTrue(ProviderAvailability.isUnavailableText("问卷已过期"))
        assertTrue(ProviderAvailability.isUnavailableText("survey closed"))
        assertFalse(ProviderAvailability.isUnavailableText("submit format invalid"))
        assertEquals(
            "腾讯问卷 当前不可填写：问卷已关闭，无法继续作答",
            ProviderAvailability.unavailableMessage("腾讯问卷", payload),
        )
    }
}
