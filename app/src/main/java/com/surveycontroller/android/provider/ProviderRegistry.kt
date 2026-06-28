package com.surveycontroller.android.provider

import com.surveycontroller.android.core.ai.FreeAiTextProvider
import com.surveycontroller.android.core.ai.OpenAiTextProvider
import com.surveycontroller.android.core.backend.BackendClient
import com.surveycontroller.android.core.model.ExecutionConfig
import com.surveycontroller.android.core.model.ExecutionState
import com.surveycontroller.android.core.network.HttpClient
import com.surveycontroller.android.core.questions.AnswerContext
import com.surveycontroller.android.provider.credamo.CredamoProvider
import com.surveycontroller.android.provider.tencent.TencentProvider
import com.surveycontroller.android.provider.wjx.WjxProvider

/**
 * 平台适配器注册与分发。复刻 software/providers/registry.py。
 */
class ProviderRegistry(
    http: HttpClient,
    backend: BackendClient,
    answerContextFactory: (ExecutionConfig, ExecutionState, String) -> AnswerContext = defaultFactory(http, backend),
) {
    companion object {
        /** 默认上下文工厂：注入 AI 填空（免费/自定义）、人设加权、心理测量计划、反向填充。 */
        fun defaultFactory(http: HttpClient, backend: BackendClient): (ExecutionConfig, ExecutionState, String) -> AnswerContext =
            { config, state, thread ->
                val aiProvider = when {
                    config.aiMode == "free" -> FreeAiTextProvider(backend, config.aiSystemPrompt)
                    config.aiEnabled && config.aiBaseUrl.isNotBlank() ->
                        OpenAiTextProvider(http, config.aiBaseUrl, config.aiApiKey, config.aiModel, config.aiSystemPrompt, config.aiApiProtocol)
                    else -> null
                }
                val persona = com.surveycontroller.android.core.persona.PersonaBoostImpl(
                    com.surveycontroller.android.core.persona.Persona.generate(),
                )
                val psycho = state.samplePlanProvider?.planFor(thread)
                AnswerContext(
                    config = config,
                    state = state,
                    threadName = thread,
                    reverseFill = state.reverseFillResolver,
                    aiText = aiProvider,
                    persona = persona,
                    psychoPlan = psycho,
                    allowAiPlaceholder = false,
                )
            }
    }

    private val providers: Map<SurveyProviderType, SurveyProvider> = mapOf(
        SurveyProviderType.WJX to WjxProvider(http, answerContextFactory),
        SurveyProviderType.QQ to TencentProvider(http, answerContextFactory),
        SurveyProviderType.CREDAMO to CredamoProvider(http, answerContextFactory),
    )

    fun forType(type: SurveyProviderType): SurveyProvider =
        providers[type] ?: error("不支持的问卷 provider: $type")

    fun forUrl(url: String): SurveyProvider = forType(SurveyProviderType.detect(url))

    /** 解析问卷：按 URL 自动识别平台。 */
    suspend fun parseSurvey(url: String): SurveyDefinition {
        val normalized = SurveyProviderType.normalizeParseUrl(url)
        return forUrl(normalized).parseSurvey(normalized)
    }

    /** 提交一份问卷。 */
    suspend fun fillSurveyHttp(
        config: ExecutionConfig,
        state: ExecutionState,
        threadName: String = "",
        proxyAddress: String? = null,
        userAgent: String? = null,
    ): SubmitResult {
        val provider = forType(SurveyProviderType.fromId(config.surveyProvider))
        return provider.fillSurveyHttp(config, state, threadName, proxyAddress, userAgent)
    }
}
