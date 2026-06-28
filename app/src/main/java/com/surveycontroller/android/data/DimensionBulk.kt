package com.surveycontroller.android.data

import com.surveycontroller.android.core.model.QuestionEntryType
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.psychometrics.OrdinalOptions

private val BULK_DIMENSION_TYPES = setOf(
    QuestionEntryType.SCALE,
    QuestionEntryType.SCORE,
    QuestionEntryType.MATRIX,
)

fun SurveyConfigDraft.withBulkDimension(dimension: String?): SurveyConfigDraft {
    val normalized = dimension?.trim()?.takeIf { it.isNotEmpty() && it != "未分组" }
    val metaByNum = definition.questions.associateBy { it.num }
    val updated = questions.map { q ->
        if (q.supportsBulkDimension(metaByNum[q.num])) q.copy(dimension = normalized) else q
    }
    val groups = (preserved.dimensionGroups + listOfNotNull(normalized))
        .mapNotNull { it.trim().takeIf { value -> value.isNotEmpty() && value != "未分组" } }
        .distinct()
    return copy(
        questions = updated,
        preserved = preserved.copy(dimensionGroups = groups),
    )
}

fun QuestionConfigDraft.supportsBulkDimension(meta: SurveyQuestionMeta? = null): Boolean {
    if (entryType in BULK_DIMENSION_TYPES) return true
    if (entryType != QuestionEntryType.SINGLE) return false
    val options = meta?.optionTexts?.takeIf { it.isNotEmpty() } ?: optionTexts
    return OrdinalOptions.infer(options) != null
}
