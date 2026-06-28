package com.surveycontroller.android.core.backend

import com.surveycontroller.android.app.AppVersion
import com.surveycontroller.android.core.network.HttpClient
import com.surveycontroller.android.core.network.UserAgents
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 联系开发者消息类型，对齐桌面端 contact_form base_options。 */
enum class ContactMessageType(val label: String) {
    BUG_REPORT("报错反馈"),
    FEATURE_REQUEST("新功能建议"),
    CHAT("纯聊天"),
}

/**
 * 联系开发者客户端。1:1 对齐桌面端 contact_form：
 * 向 https://bot.hungrym0.top 提交 multipart/form-data（message/messageType/timestamp/issueTitle/userId）。
 */
class ContactClient(
    private val http: HttpClient,
    private val sessionStore: RandomIpSessionStore,
) {
    companion object {
        @Volatile var endpoint = "https://bot.hungrym0.top"
    }

    /**
     * 提交反馈消息。
     * @param type 消息类型
     * @param message 用户填写的正文
     * @param email 可选联系邮箱
     * @param issueTitle 可选反馈标题（报错反馈时拼入正文）
     * @param images 可选图片附件（最多 3 张），(文件名, 字节, mime)
     */
    suspend fun submit(
        type: ContactMessageType,
        message: String,
        email: String = "",
        issueTitle: String = "",
        images: List<Triple<String, ByteArray, String>> = emptyList(),
    ): Boolean {
        val userId = sessionStore.load().takeIf { it.authenticated }?.userId ?: 0
        val body = buildMessage(type, message, email, issueTitle, userId)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        val parts = mutableListOf(
            HttpClient.Part("message", body),
            HttpClient.Part("messageType", type.label),
            HttpClient.Part("timestamp", timestamp),
        )
        if (issueTitle.isNotBlank()) parts.add(HttpClient.Part("issueTitle", issueTitle.trim()))
        if (userId > 0) parts.add(HttpClient.Part("userId", userId.toString()))
        images.take(3).forEachIndexed { i, (name, bytes, mime) ->
            parts.add(HttpClient.Part("image$i", "", bytes = bytes, filename = name.ifBlank { "image_$i.jpg" }, mime = mime))
        }

        val headers = UserAgents.DEFAULT_HEADERS
        val resp = http.postMultipart(endpoint, parts, headers = headers, timeoutSeconds = 20)
        return resp.statusCode in 200..299
    }

    /** 拼装反馈正文，对齐 message_builder.build_contact_message。 */
    private fun buildMessage(
        type: ContactMessageType,
        message: String,
        email: String,
        issueTitle: String,
        userId: Int,
    ): String {
        val lines = mutableListOf(
            "来源：SurveyController v${AppVersion.VERSION} (Android)",
            "类型：${type.label}",
        )
        if (email.isNotBlank()) lines.add("联系邮箱： ${email.trim()}")
        if (issueTitle.isNotBlank() && type == ContactMessageType.BUG_REPORT) lines.add("反馈标题： ${issueTitle.trim()}")
        if (userId > 0) lines.add("随机IP用户ID：$userId")
        lines.add("")
        lines.add("消息：$message")
        return lines.joinToString("\n")
    }
}
