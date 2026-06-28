package com.surveycontroller.android.provider

import org.json.JSONObject

object ProviderAvailability {
    private val unavailableKeywords = listOf(
        "已暂停",
        "暂停",
        "已停止",
        "停止",
        "未开放",
        "尚未开始",
        "未到开始时间",
        "不可填写",
        "不能填写",
        "无法作答",
        "已结束",
        "已过期",
        "过期",
        "已关闭",
        "关闭",
        "not open",
        "not started",
        "closed",
        "expired",
        "ended",
        "unavailable",
    )

    fun unavailableMessage(providerLabel: String, payload: JSONObject): String? {
        val message = listOf(
            payload.optString("message"),
            payload.optString("msg"),
            payload.optString("detail"),
            payload.optString("reason"),
            payload.optString("code"),
        ).firstOrNull { isUnavailableText(it) } ?: return null
        return "$providerLabel 当前不可填写：${message.trim()}"
    }

    fun isUnavailableText(raw: String?): Boolean {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return false
        val lower = text.lowercase()
        return unavailableKeywords.any { lower.contains(it.lowercase()) }
    }
}
