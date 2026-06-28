package com.surveycontroller.android.provider.credamo

import com.surveycontroller.android.core.model.AnswerAction
import com.surveycontroller.android.core.model.ExecutionConfig
import com.surveycontroller.android.core.model.JumpRule
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.network.HttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CredamoProviderTest {

    @Test
    fun merge_submit_questions_keeps_config_metadata_and_updates_runtime_fields() {
        val provider = CredamoProvider(HttpClient())
        val configQuestion = SurveyQuestionMeta(
            num = 8,
            title = "请选择 200",
            provider = "credamo",
            providerQuestionId = "old-id",
            providerPageId = "config-page",
            providerType = "single",
            typeCode = "3",
            options = 4,
            forcedOptionIndex = 1,
            forcedOptionText = "200",
            hasJump = true,
            jumpRules = listOf(JumpRule(optionIndex = 1, targetQuestion = 9, optionText = "200")),
            logicParseStatus = "complete",
        )
        val runtimeRaw = JSONObject(
            """
            {
              "qstId": "runtime-id",
              "questionType": 2,
              "selector": 1,
              "choices": [
                {"choiceId": "c1", "choiceContent": "300"},
                {"choiceId": "c2", "choiceContent": "500"},
                {"choiceId": "c3", "choiceContent": "200"}
              ]
            }
            """.trimIndent(),
        )

        val merged = provider.mergeSubmitQuestions(
            ExecutionConfig(questionsMetadata = mapOf(8 to configQuestion)),
            mapOf(8 to runtimeRaw),
        ).single()

        assertEquals(8, merged.num)
        assertEquals("runtime-id", merged.providerQuestionId)
        assertEquals("config-page", merged.providerPageId)
        assertEquals(3, merged.options)
        assertEquals("200", merged.forcedOptionText)
        assertTrue(merged.hasJump)
        assertEquals("complete", merged.logicParseStatus)
        assertEquals(9, merged.jumpRules.single().targetQuestion)
    }

    @Test
    fun merge_submit_questions_fails_when_config_question_is_missing_at_runtime() {
        val provider = CredamoProvider(HttpClient())
        val config = ExecutionConfig(
            questionsMetadata = mapOf(
                8 to SurveyQuestionMeta(num = 8, provider = "credamo", providerType = "single"),
            ),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            provider.mergeSubmitQuestions(config, emptyMap())
        }

        assertTrue(error.message.orEmpty().contains("未在接口详情中找到"))
    }

    @Test
    fun forced_choice_prefers_text_when_api_choice_order_changes() {
        val provider = CredamoProvider(HttpClient())
        val raw = JSONObject(
            """
            {
              "qstId": "108",
              "questionType": 2,
              "selector": 1,
              "choices": [
                {"choiceId": "6787", "display": "300"},
                {"choiceId": "6788", "display": "500"},
                {"choiceId": "6789", "display": "200"},
                {"choiceId": "6790", "display": "600"}
              ]
            }
            """.trimIndent(),
        )
        val config = ExecutionConfig(
            surveyProvider = "credamo",
            questionsMetadata = mapOf(
                8 to SurveyQuestionMeta(
                    num = 8,
                    title = "请选择 200",
                    provider = "credamo",
                    options = 4,
                    forcedOptionIndex = 1,
                    forcedOptionText = "200",
                ),
            ),
        )

        val encoded = provider.encodeAnswer(
            raw,
            AnswerAction(questionNum = 8, kind = "choice", selectedIndices = listOf(1), rootIndex = 8, recordType = "single"),
            config,
        )

        assertEquals(6789, encoded.getJSONObject("answerQstChoice").getInt("choiceId"))
    }

    @Test
    fun scale_answer_rejects_selected_index_out_of_range() {
        val provider = CredamoProvider(HttpClient())
        val raw = JSONObject(
            """
            {
              "qstId": "q-scale",
              "questionType": 11,
              "choices": [{"choiceId": "c1"}, {"choiceId": "c2"}]
            }
            """.trimIndent(),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            provider.encodeAnswer(
                raw,
                AnswerAction(questionNum = 4, kind = "choice", selectedIndices = listOf(5), scalarValue = 5, recordType = "scale"),
            )
        }

        assertTrue(error.message.orEmpty().contains("选项索引越界"))
    }

    @Test
    fun scale_answer_rejects_missing_selected_answer_instead_of_defaulting_to_first_option() {
        val provider = CredamoProvider(HttpClient())
        val raw = JSONObject(
            """
            {
              "qstId": "q-scale",
              "questionType": 11,
              "choices": [{"choiceId": "c1"}, {"choiceId": "c2"}]
            }
            """.trimIndent(),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            provider.encodeAnswer(
                raw,
                AnswerAction(questionNum = 4, kind = "choice", selectedIndices = emptyList(), recordType = "scale"),
            )
        }

        assertTrue(error.message.orEmpty().contains("没有生成量表答案"))
    }

    @Test
    fun text_answer_rejects_blank_answer_before_submit() {
        val provider = CredamoProvider(HttpClient())
        val raw = JSONObject(
            """
            {
              "qstId": "q-text",
              "questionType": 1
            }
            """.trimIndent(),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            provider.encodeAnswer(raw, AnswerAction(questionNum = 2, kind = "text", textValues = listOf(" ", "")))
        }

        assertTrue(error.message.orEmpty().contains("没有生成填空答案"))
    }

    @Test
    fun answer_rejects_missing_question_id_before_submit() {
        val provider = CredamoProvider(HttpClient())
        val raw = JSONObject(
            """
            {
              "questionType": 1
            }
            """.trimIndent(),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            provider.encodeAnswer(raw, AnswerAction(questionNum = 2, kind = "text", textValues = listOf("答案")))
        }

        assertTrue(error.message.orEmpty().contains("题目缺少 id"))
    }

    @Test
    fun matrix_answer_rejects_missing_row_answer() {
        val provider = CredamoProvider(HttpClient())
        val raw = JSONObject(
            """
            {
              "qstId": "q-matrix",
              "questionType": 4,
              "choices": [{"choiceId": "r1"}, {"choiceId": "r2"}],
              "answers": [{"answerId": "a1"}, {"answerId": "a2"}]
            }
            """.trimIndent(),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            provider.encodeAnswer(
                raw,
                AnswerAction(questionNum = 6, kind = "matrix", matrixIndices = listOf(0), recordType = "matrix"),
            )
        }

        assertTrue(error.message.orEmpty().contains("第2行没有生成矩阵答案"))
    }

    @Test
    fun matrix_answer_rejects_selected_column_out_of_range() {
        val provider = CredamoProvider(HttpClient())
        val raw = JSONObject(
            """
            {
              "qstId": "q-matrix",
              "questionType": 4,
              "choices": [{"choiceId": "r1"}],
              "answers": [{"answerId": "a1"}]
            }
            """.trimIndent(),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            provider.encodeAnswer(
                raw,
                AnswerAction(questionNum = 6, kind = "matrix", matrixIndices = listOf(3), recordType = "matrix"),
            )
        }

        assertTrue(error.message.orEmpty().contains("矩阵列索引越界"))
    }

    @Test
    fun matrix_answer_rejects_missing_column_id_before_submit() {
        val provider = CredamoProvider(HttpClient())
        val raw = JSONObject(
            """
            {
              "qstId": "q-matrix",
              "questionType": 4,
              "choices": [{"choiceId": "r1"}],
              "answers": [{"answerContent": "列1"}]
            }
            """.trimIndent(),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            provider.encodeAnswer(
                raw,
                AnswerAction(questionNum = 6, kind = "matrix", matrixIndices = listOf(0), recordType = "matrix"),
            )
        }

        assertTrue(error.message.orEmpty().contains("矩阵列缺少 id"))
    }

    @Test
    fun question_type_25_encodes_as_matrix_even_when_action_kind_is_choice() {
        val provider = CredamoProvider(HttpClient())
        val raw = JSONObject(
            """
            {
              "qstId": "q-matrix-25",
              "questionType": 25,
              "choices": [{"choiceId": "r1"}],
              "answers": [{"answerId": "a1"}, {"answerId": "a2"}]
            }
            """.trimIndent(),
        )

        val encoded = provider.encodeAnswer(
            raw,
            AnswerAction(questionNum = 6, kind = "choice", matrixIndices = listOf(1), recordType = "single"),
        )
        val row = encoded.getJSONArray("answerQstChoiceList").getJSONObject(0)

        assertEquals("r1", row.getString("choiceId"))
        assertEquals("a2", row.getJSONArray("choiceAnswerList").getJSONObject(0).getString("answerId"))
    }

    @Test
    fun order_answer_rejects_empty_selected_indices() {
        val provider = CredamoProvider(HttpClient())
        val raw = JSONObject(
            """
            {
              "qstId": "q-order",
              "questionType": 6,
              "choices": [{"choiceId": "c1"}, {"choiceId": "c2"}]
            }
            """.trimIndent(),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            provider.encodeAnswer(raw, AnswerAction(questionNum = 5, kind = "order", selectedIndices = emptyList()))
        }

        assertTrue(error.message.orEmpty().contains("没有生成排序答案"))
    }

    @Test
    fun order_answer_rejects_missing_choice_id_before_submit() {
        val provider = CredamoProvider(HttpClient())
        val raw = JSONObject(
            """
            {
              "qstId": "q-order",
              "questionType": 6,
              "choices": [{"choiceContent": "A"}]
            }
            """.trimIndent(),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            provider.encodeAnswer(raw, AnswerAction(questionNum = 5, kind = "order", selectedIndices = listOf(0)))
        }

        assertTrue(error.message.orEmpty().contains("排序选项缺少 id"))
    }

    @Test
    fun order_answer_keeps_rank_payload_like_desktop() {
        val provider = CredamoProvider(HttpClient())
        val raw = JSONObject(
            """
            {
              "qstId": "q-order",
              "questionType": 6,
              "choices": [{"choiceId": "c1"}, {"choiceId": "c2"}]
            }
            """.trimIndent(),
        )

        val encoded = provider.encodeAnswer(
            raw,
            AnswerAction(questionNum = 5, kind = "order", selectedIndices = listOf(1, 0), recordType = "order"),
        )
        val ranked = encoded.getJSONArray("answerChoiceContent")

        assertEquals("c2", ranked.getJSONObject(0).getString("choiceId"))
        assertEquals(1, ranked.getJSONObject(0).getInt("choiceContent"))
        assertEquals("c1", ranked.getJSONObject(1).getString("choiceId"))
        assertEquals(2, ranked.getJSONObject(1).getInt("choiceContent"))
    }
}
