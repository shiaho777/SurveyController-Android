package com.surveycontroller.android.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "survey_settings")

/**
 * 应用设置持久化。对齐桌面端 settings 页：外观 / 行为 / 更新 / AI。
 */
class SettingsStore(private val context: Context) {

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    data class Settings(
        // 外观
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val dynamicColor: Boolean = false,
        val navigationLabelVisible: Boolean = true,
        // 行为
        val preventSleepDuringRun: Boolean = true,
        val taskResultNotification: Boolean = true,
        val submissionReportTelemetry: Boolean = true,
        // 更新
        val autoCheckUpdate: Boolean = true,
        // AI
        val aiEnabled: Boolean = false,
        val aiBaseUrl: String = "",
        val aiApiKey: String = "",
        val aiModel: String = "",
        val aiApiProtocol: String = AI_API_PROTOCOL_AUTO,
        val aiSystemPrompt: String = "",
        // 默认运行参数
        val defaultThreads: Int = 1,
        val defaultTarget: Int = 5,
        val termsAccepted: Boolean = false,
        // 日志
        val autoSaveLogs: Boolean = true,
        val logRetentionCount: Int = 10,
    )

    companion object {
        /** 保留最近日志文件数候选项，对齐桌面端 AUTO_SAVE_LOG_RETENTION_OPTIONS。 */
        val LOG_RETENTION_OPTIONS = listOf(3, 5, 10, 20, 30, 50)
        const val DEFAULT_LOG_RETENTION_COUNT = 10
        const val AI_API_PROTOCOL_AUTO = "auto"
        const val AI_API_PROTOCOL_CHAT_COMPLETIONS = "chat_completions"
        const val AI_API_PROTOCOL_RESPONSES = "responses"
        val AI_API_PROTOCOL_OPTIONS = listOf(
            AI_API_PROTOCOL_AUTO,
            AI_API_PROTOCOL_CHAT_COMPLETIONS,
            AI_API_PROTOCOL_RESPONSES,
        )

        fun normalizeAiApiProtocol(value: String?): String =
            when (value?.trim()?.lowercase()) {
                AI_API_PROTOCOL_RESPONSES -> AI_API_PROTOCOL_RESPONSES
                AI_API_PROTOCOL_CHAT_COMPLETIONS -> AI_API_PROTOCOL_CHAT_COMPLETIONS
                else -> AI_API_PROTOCOL_AUTO
            }
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p -> p.toSettings() }

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.themeMode] = next.themeMode.name
            prefs[Keys.dynamicColor] = next.dynamicColor
            prefs[Keys.navigationLabelVisible] = next.navigationLabelVisible
            prefs[Keys.preventSleep] = next.preventSleepDuringRun
            prefs[Keys.taskResultNotification] = next.taskResultNotification
            prefs[Keys.submissionReportTelemetry] = next.submissionReportTelemetry
            prefs[Keys.autoCheckUpdate] = next.autoCheckUpdate
            prefs[Keys.aiEnabled] = next.aiEnabled
            prefs[Keys.aiBaseUrl] = next.aiBaseUrl
            prefs[Keys.aiApiKey] = next.aiApiKey
            prefs[Keys.aiModel] = next.aiModel
            prefs[Keys.aiApiProtocol] = normalizeAiApiProtocol(next.aiApiProtocol)
            prefs[Keys.aiSystemPrompt] = next.aiSystemPrompt
            prefs[Keys.defaultThreads] = next.defaultThreads
            prefs[Keys.defaultTarget] = next.defaultTarget
            prefs[Keys.termsAccepted] = next.termsAccepted
            prefs[Keys.autoSaveLogs] = next.autoSaveLogs
            prefs[Keys.logRetentionCount] = next.logRetentionCount
        }
    }

    /** 恢复全部设置为默认值（对齐桌面端“恢复默认设置”）。 */
    suspend fun resetToDefaults() {
        context.dataStore.edit { it.clear() }
    }

    private fun Preferences.toSettings() = Settings(
        themeMode = runCatching { ThemeMode.valueOf(this[Keys.themeMode] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
        dynamicColor = this[Keys.dynamicColor] ?: false,
        navigationLabelVisible = this[Keys.navigationLabelVisible] ?: true,
        preventSleepDuringRun = this[Keys.preventSleep] ?: true,
        taskResultNotification = this[Keys.taskResultNotification] ?: true,
        submissionReportTelemetry = this[Keys.submissionReportTelemetry] ?: true,
        autoCheckUpdate = this[Keys.autoCheckUpdate] ?: true,
        aiEnabled = this[Keys.aiEnabled] ?: false,
        aiBaseUrl = this[Keys.aiBaseUrl] ?: "",
        aiApiKey = this[Keys.aiApiKey] ?: "",
        aiModel = this[Keys.aiModel] ?: "",
        aiApiProtocol = normalizeAiApiProtocol(this[Keys.aiApiProtocol]),
        aiSystemPrompt = this[Keys.aiSystemPrompt] ?: "",
        defaultThreads = this[Keys.defaultThreads] ?: 1,
        defaultTarget = this[Keys.defaultTarget] ?: 5,
        termsAccepted = this[Keys.termsAccepted] ?: false,
        autoSaveLogs = this[Keys.autoSaveLogs] ?: true,
        logRetentionCount = this[Keys.logRetentionCount] ?: DEFAULT_LOG_RETENTION_COUNT,
    )

    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val navigationLabelVisible = booleanPreferencesKey("nav_label_visible")
        val preventSleep = booleanPreferencesKey("prevent_sleep")
        val taskResultNotification = booleanPreferencesKey("task_result_notification")
        val submissionReportTelemetry = booleanPreferencesKey("submission_report_telemetry")
        val autoCheckUpdate = booleanPreferencesKey("auto_check_update")
        val aiEnabled = booleanPreferencesKey("ai_enabled")
        val aiBaseUrl = stringPreferencesKey("ai_base_url")
        val aiApiKey = stringPreferencesKey("ai_api_key")
        val aiModel = stringPreferencesKey("ai_model")
        val aiApiProtocol = stringPreferencesKey("ai_api_protocol")
        val aiSystemPrompt = stringPreferencesKey("ai_system_prompt")
        val defaultThreads = intPreferencesKey("default_threads")
        val defaultTarget = intPreferencesKey("default_target")
        val termsAccepted = booleanPreferencesKey("terms_accepted")
        val autoSaveLogs = booleanPreferencesKey("auto_save_logs")
        val logRetentionCount = intPreferencesKey("log_retention_count")
    }
}
