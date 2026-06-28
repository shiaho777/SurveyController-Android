package com.surveycontroller.android.core

import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.data.ConfigCodec
import com.surveycontroller.android.data.ConfigCompiler
import com.surveycontroller.android.data.SurveyConfigDraft
import com.surveycontroller.android.provider.SurveyDefinition
import com.surveycontroller.android.provider.SurveyProviderType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerRulesCodecTest {

    @Test
    fun answer_rules_round_trip_through_codec() {
        val q1 = SurveyQuestionMeta(num = 1, title = "性别", typeCode = "3", options = 2, optionTexts = listOf("男", "女"))
        val q2 = SurveyQuestionMeta(num = 2, title = "是否怀孕", typeCode = "3", options = 2, optionTexts = listOf("是", "否"))
        val def = SurveyDefinition(SurveyProviderType.WJX, "https://www.wjx.cn/vm/x.aspx", "T", listOf(q1, q2))
        val rule = mapOf<String, Any?>(
            "condition_question_num" to 1,
            "condition_mode" to "selected",
            "condition_option_indices" to listOf(0),
            "condition_row_index" to 1,
            "target_question_num" to 2,
            "action_mode" to "must_not_select",
            "target_option_indices" to listOf(0),
            "target_row_index" to 0,
        )
        val draft = SurveyConfigDraft.fromDefinition(def).copy(answerRules = listOf(rule))

        val restored = ConfigCodec.deserialize(ConfigCodec.serialize(draft))
        assertEquals(1, restored.answerRules.size)
        val r = restored.answerRules.first()
        assertEquals("must_not_select", r["action_mode"])
        assertEquals(listOf(0), (r["condition_option_indices"] as List<*>).map { (it as Number).toInt() })
        assertEquals(1, (r["condition_row_index"] as Number).toInt())
        assertEquals(0, (r["target_row_index"] as Number).toInt())
    }

    @Test
    fun answer_rules_import_export_and_compile_are_sanitized_like_desktop() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/rules.aspx",
              "survey_title": "规则清洗",
              "survey_provider": "wjx",
              "questions_info": [
                {"num": 1, "title": "单选", "type_code": "3", "options": 2, "option_texts": ["A", "B"]},
                {"num": 2, "title": "填空", "type_code": "1", "is_text_like": true, "text_inputs": 1},
                {"num": 3, "title": "多选", "type_code": "4", "options": 3, "option_texts": ["A", "B", "C"]}
              ],
              "question_entries": [
                {"question_num": 1, "question_title": "单选", "question_type": "single", "option_count": 2, "probabilities": -1},
                {"question_num": 2, "question_title": "填空", "question_type": "text", "texts": ["已填写"]},
                {"question_num": 3, "question_title": "多选", "question_type": "multiple", "option_count": 3, "probabilities": [50, 50, 50]}
              ],
              "answer_rules": [
                {"condition_question_num": "1", "condition_mode": "selected", "condition_option_indices": ["0", "0", "2"], "target_question_num": "3", "action_mode": "must_not_select", "target_option_indices": ["1"]},
                {"condition_question_num": 2, "condition_mode": "selected", "condition_option_indices": [0], "target_question_num": 3, "action_mode": "must_select", "target_option_indices": [0]},
                {"condition_question_num": 1, "condition_mode": "bad", "condition_option_indices": [0], "target_question_num": 3, "action_mode": "must_select", "target_option_indices": [0]},
                {"condition_question_num": 1, "condition_mode": "selected", "condition_option_indices": [0], "target_question_num": 99, "action_mode": "must_select", "target_option_indices": [0]}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)

        assertEquals(1, restored.answerRules.size)
        val rule = restored.answerRules.single()
        assertEquals("selected", rule["condition_mode"])
        assertEquals(listOf(0, 2), rule["condition_option_indices"])
        assertEquals("must_not_select", rule["action_mode"])
        assertEquals(listOf(1), rule["target_option_indices"])

        val compiled = ConfigCompiler.compile(restored)
        assertEquals(restored.answerRules, compiled.answerRules)

        val exported = JSONObject(ConfigCodec.serialize(restored)).getJSONArray("answer_rules")
        assertEquals(1, exported.length())
        assertEquals(1, exported.getJSONObject(0).getInt("condition_question_num"))
        assertEquals(3, exported.getJSONObject(0).getInt("target_question_num"))
    }
}
