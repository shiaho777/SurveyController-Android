package com.surveycontroller.android.provider

import com.surveycontroller.android.core.model.SurveyQuestionMeta

/** 解析后的标准化问卷定义。对应 Python 端 SurveyDefinition。 */
data class SurveyDefinition(
    val provider: SurveyProviderType,
    val url: String,
    val title: String = "",
    val questions: List<SurveyQuestionMeta> = emptyList(),
)
