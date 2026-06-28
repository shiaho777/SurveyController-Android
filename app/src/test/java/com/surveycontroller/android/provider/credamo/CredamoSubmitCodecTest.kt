package com.surveycontroller.android.provider.credamo

import com.surveycontroller.android.core.model.AnswerAction
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CredamoSubmitCodecTest {

    @Test
    fun single_choice_preserves_option_fill_text_as_choice_content() {
        val raw = choiceQuestion(selector = 1, choiceIds = listOf("c1", "c2", "c3"))
        val action = AnswerAction(
            questionNum = 1,
            kind = "choice",
            selectedIndices = listOf(1),
            scalarValue = 1,
            optionFillTexts = listOf(1 to "补充说明"),
        )

        val encoded = CredamoSubmitCodec.encodeChoice(raw, action, qstId = "q1")
        val choice = encoded.getJSONObject("answerQstChoice")

        assertEquals("q1", encoded.getString("qstId"))
        assertEquals("c2", choice.getString("choiceId"))
        assertEquals("补充说明", choice.getString("choiceContent"))
    }

    @Test
    fun multiple_choice_preserves_fill_text_on_matching_selected_option() {
        val raw = choiceQuestion(selector = 2, choiceIds = listOf("c1", "c2", "c3"))
        val action = AnswerAction(
            questionNum = 1,
            kind = "multiple",
            selectedIndices = listOf(0, 2),
            optionFillTexts = listOf(2 to "其他内容"),
        )

        val encoded = CredamoSubmitCodec.encodeChoice(raw, action, qstId = "q1")
        val choices = encoded.getJSONArray("answerQstChoiceList")

        assertEquals(2, choices.length())
        assertEquals("c1", choices.getJSONObject(0).getString("choiceId"))
        assertEquals("", choices.getJSONObject(0).getString("choiceContent"))
        assertEquals("c3", choices.getJSONObject(1).getString("choiceId"))
        assertEquals("其他内容", choices.getJSONObject(1).getString("choiceContent"))
    }

    @Test
    fun sub_selector_choice_keeps_nested_selector_metadata() {
        val raw = choiceQuestion(selector = 1, subSelector = 3, choiceIds = listOf(101, 102))
        val action = AnswerAction(questionNum = 1, kind = "choice", selectedIndices = listOf(0))

        val encoded = CredamoSubmitCodec.encodeChoice(raw, action, qstId = 7)

        assertEquals(2, encoded.getInt("questionType"))
        assertEquals(3, encoded.getInt("subSelector"))
        assertEquals(101, encoded.getJSONObject("answerQstChoice").getInt("choiceId"))
    }

    @Test
    fun regular_choice_does_not_emit_nested_selector_metadata() {
        val raw = choiceQuestion(selector = 1, choiceIds = listOf("c1"))
        val action = AnswerAction(questionNum = 1, kind = "choice", selectedIndices = listOf(0))

        val encoded = CredamoSubmitCodec.encodeChoice(raw, action, qstId = "q1")

        assertTrue(!encoded.has("questionType"))
        assertTrue(!encoded.has("subSelector"))
    }

    @Test
    fun multiple_choice_rejects_selected_index_out_of_range() {
        val raw = choiceQuestion(selector = 2, choiceIds = listOf("c1"))
        val action = AnswerAction(questionNum = 1, kind = "multiple", selectedIndices = listOf(0, 2))

        val error = assertThrows(IllegalStateException::class.java) {
            CredamoSubmitCodec.encodeChoice(raw, action, qstId = "q1")
        }

        assertTrue(error.message.orEmpty().contains("选项索引越界"))
    }

    @Test
    fun single_choice_rejects_missing_choice_list() {
        val raw = JSONObject().put("questionType", 2).put("selector", 1)
        val action = AnswerAction(questionNum = 1, kind = "choice", selectedIndices = listOf(0))

        val error = assertThrows(IllegalStateException::class.java) {
            CredamoSubmitCodec.encodeChoice(raw, action, qstId = "q1")
        }

        assertTrue(error.message.orEmpty().contains("选项索引越界"))
    }

    @Test
    fun single_choice_rejects_missing_selected_answer_instead_of_defaulting_to_first_option() {
        val raw = choiceQuestion(selector = 1, choiceIds = listOf("c1", "c2"))
        val action = AnswerAction(questionNum = 1, kind = "choice", selectedIndices = emptyList())

        val error = assertThrows(IllegalStateException::class.java) {
            CredamoSubmitCodec.encodeChoice(raw, action, qstId = "q1")
        }

        assertTrue(error.message.orEmpty().contains("没有生成选项答案"))
    }

    @Test
    fun choice_answer_rejects_missing_choice_id_before_submit() {
        val raw = JSONObject()
            .put("questionType", 2)
            .put("selector", 1)
            .put("choices", JSONArray().put(JSONObject().put("choiceContent", "A")))
        val action = AnswerAction(questionNum = 1, kind = "choice", selectedIndices = listOf(0))

        val error = assertThrows(IllegalStateException::class.java) {
            CredamoSubmitCodec.encodeChoice(raw, action, qstId = "q1")
        }

        assertTrue(error.message.orEmpty().contains("选项缺少 id"))
    }

    private fun choiceQuestion(
        selector: Int,
        choiceIds: List<Any>,
        subSelector: Int = 0,
    ): JSONObject {
        val choices = JSONArray()
        choiceIds.forEach { id ->
            choices.put(JSONObject().put("choiceId", id))
        }
        return JSONObject()
            .put("questionType", 2)
            .put("selector", selector)
            .put("subSelector", subSelector)
            .put("choices", choices)
    }
}
