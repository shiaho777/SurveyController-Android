package com.surveycontroller.android.core

import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.data.ConfigPreservedFields
import com.surveycontroller.android.data.SurveyConfigDraft
import com.surveycontroller.android.data.withBulkDimension
import com.surveycontroller.android.provider.SurveyDefinition
import com.surveycontroller.android.provider.SurveyProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DimensionBulkTest {

    @Test
    fun applies_dimension_only_to_reliability_supported_question_types() {
        val def = SurveyDefinition(
            SurveyProviderType.WJX,
            "https://www.wjx.cn/vm/dim-bulk.aspx",
            "维度批量",
            listOf(
                SurveyQuestionMeta(num = 1, title = "普通单选", typeCode = "3", options = 2, optionTexts = listOf("A", "B")),
                SurveyQuestionMeta(num = 2, title = "有序单选", typeCode = "3", options = 5, optionTexts = listOf("非常不满意", "不满意", "一般", "满意", "非常满意")),
                SurveyQuestionMeta(num = 3, title = "量表", typeCode = "5", options = 5, optionTexts = listOf("1", "2", "3", "4", "5")),
                SurveyQuestionMeta(num = 4, title = "矩阵", typeCode = "6", rows = 2, options = 5, rowTexts = listOf("R1", "R2"), optionTexts = listOf("1", "2", "3", "4", "5")),
            ),
        )
        val draft = SurveyConfigDraft.fromDefinition(def)
            .copy(preserved = ConfigPreservedFields(dimensionGroups = listOf("满意度")))

        val updated = draft.withBulkDimension("态度")

        assertNull(updated.questions.first { it.num == 1 }.dimension)
        assertEquals("态度", updated.questions.first { it.num == 2 }.dimension)
        assertEquals("态度", updated.questions.first { it.num == 3 }.dimension)
        assertEquals("态度", updated.questions.first { it.num == 4 }.dimension)
        assertEquals(listOf("满意度", "态度"), updated.preserved.dimensionGroups)
    }

    @Test
    fun clears_bulk_dimension_but_keeps_existing_group_catalog() {
        val def = SurveyDefinition(
            SurveyProviderType.WJX,
            "https://www.wjx.cn/vm/dim-clear.aspx",
            "维度清空",
            listOf(SurveyQuestionMeta(num = 1, title = "量表", typeCode = "5", options = 5, optionTexts = listOf("1", "2", "3", "4", "5"))),
        )
        val base = SurveyConfigDraft.fromDefinition(def)
        val draft = base.copy(
            preserved = ConfigPreservedFields(dimensionGroups = listOf("满意度")),
            questions = base.questions.map { it.copy(dimension = "满意度") },
        )

        val updated = draft.withBulkDimension(null)

        assertNull(updated.questions.single().dimension)
        assertEquals(listOf("满意度"), updated.preserved.dimensionGroups)
    }
}
