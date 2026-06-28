package com.surveycontroller.android.ui

import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.data.ConfigPreservedFields
import com.surveycontroller.android.data.SurveyConfigDraft
import com.surveycontroller.android.provider.SurveyDefinition
import com.surveycontroller.android.provider.SurveyProviderType
import com.surveycontroller.android.ui.screens.dimensionSuggestions
import com.surveycontroller.android.ui.screens.normalizeDimensionName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DimensionEditorTest {

    @Test
    fun dimension_suggestions_merge_preserved_groups_and_question_values() {
        val def = SurveyDefinition(
            SurveyProviderType.WJX,
            "https://www.wjx.cn/vm/dim.aspx",
            "维度",
            listOf(
                SurveyQuestionMeta(num = 1, title = "满意度", typeCode = "5", options = 5, optionTexts = listOf("1", "2", "3", "4", "5")),
                SurveyQuestionMeta(num = 2, title = "态度", typeCode = "5", options = 5, optionTexts = listOf("1", "2", "3", "4", "5")),
            ),
        )
        val base = SurveyConfigDraft.fromDefinition(def)
        val draft = base.copy(
            preserved = ConfigPreservedFields(dimensionGroups = listOf("未分组", "满意度", "态度")),
            questions = base.questions.map {
                if (it.num == 2) it.copy(dimension = "忠诚度") else it.copy(dimension = "满意度")
            },
        )

        assertEquals(listOf("满意度", "态度", "忠诚度"), dimensionSuggestions(draft))
    }

    @Test
    fun normalize_dimension_name_filters_blank_and_ungrouped() {
        assertNull(normalizeDimensionName(""))
        assertNull(normalizeDimensionName(" 未分组 "))
        assertEquals("满意度", normalizeDimensionName(" 满意度 "))
    }
}
