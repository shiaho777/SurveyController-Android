package com.surveycontroller.android.provider.credamo

import com.surveycontroller.android.data.ConfigPreflight
import com.surveycontroller.android.data.SurveyConfigDraft
import com.surveycontroller.android.provider.SurveyDefinition
import com.surveycontroller.android.provider.SurveyProviderType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 Credamo 双重 SHA1 签名与 Python 端一致（参考值由 Python hashlib 计算）。
 */
class CredamoApiTest {

    @Test
    fun signature_matches_python_reference() {
        val sig = CredamoApi.computeSignature(
            token = "abc123",
            union = "UNION12345",
            nonce = "NONCE1234567890A",
            timestamp = "1700000000000",
        )
        assertEquals("1C0ECC8A7588923BB8B7000C6E91209E4DFB6568", sig)
    }

    @Test
    fun noauth_short_url_converts_trailing_underscore_to_ano() {
        assertEquals("abcdano", CredamoApi.noauthShortUrl("abcd_"))
        assertEquals("abcdano", CredamoApi.noauthShortUrl("abcdano"))
    }

    @Test
    fun standardize_marks_unknown_question_type_unsupported_instead_of_treating_as_text() {
        val raw = JSONObject()
            .put("qstId", "q-unsupported")
            .put("questionType", 99)
            .put("qstTitle", "上传文件")

        val (metas, rawByNum) = CredamoApi.standardize(listOf(raw))
        val meta = metas.single()

        assertEquals("0", meta.typeCode)
        assertEquals("99", meta.providerType)
        assertTrue(meta.unsupported)
        assertTrue(meta.unsupportedReason.contains("99"))
        assertTrue(rawByNum.containsKey(1))

        val draft = SurveyConfigDraft.fromDefinition(
            SurveyDefinition(SurveyProviderType.CREDAMO, "https://www.credamo.com/answer.html#/s/demoano", "见数", metas),
        )
        val result = ConfigPreflight.validate(draft)
        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("暂不支持"))
    }

    @Test
    fun standardize_treats_question_type_25_as_matrix_like_desktop_submitter() {
        val raw = JSONObject(
            """
            {
              "qstId": "q-matrix",
              "questionType": 25,
              "qstTitle": "矩阵",
              "choices": [{"choiceId": "r1", "choiceContent": "行1"}],
              "answers": [{"answerId": "c1", "answerContent": "列1"}, {"answerId": "c2", "answerContent": "列2"}]
            }
            """.trimIndent(),
        )

        val (metas, _) = CredamoApi.standardize(listOf(raw))
        val meta = metas.single()

        assertEquals("matrix", meta.providerType)
        assertEquals("6", meta.typeCode)
        assertEquals(1, meta.rows)
        assertEquals(2, meta.options)
        assertFalse(meta.unsupported)
    }

    @Test
    fun standardize_preserves_required_description_page_rating_and_media_metadata() {
        val raw = JSONObject(
            """
            {
              "qstId": "q-scale",
              "questionType": 11,
              "qstNo": "Q5",
              "qstTitle": "满意度 <img src='https://cdn.example.com/title.jpg'>",
              "description": "请选择最符合的一项",
              "pageNo": "3",
              "mustAnswer": "1",
              "choices": [
                {"choiceId": "c1", "choiceContent": "1", "image_url": "//cdn.example.com/choice-1.png"},
                {"choiceId": "c2", "choiceContent": "2"}
              ],
              "question_media": [
                {"kind": "image", "scope": "title", "source_url": "https://cdn.example.com/raw.webp", "label": "原始题图"}
              ]
            }
            """.trimIndent(),
        )

        val (metas, _) = CredamoApi.standardize(listOf(raw))
        val meta = metas.single()

        assertEquals(5, meta.num)
        assertEquals(3, meta.page)
        assertEquals("3", meta.providerPageId)
        assertEquals("请选择最符合的一项", meta.description)
        assertTrue(meta.required)
        assertTrue(meta.isRating)
        assertEquals(2, meta.ratingMax)
        assertEquals(3, meta.questionMedia.size)
        assertTrue(meta.questionMedia.any { it.scope == "title" && it.sourceUrl == "https://cdn.example.com/title.jpg" })
        assertTrue(meta.questionMedia.any { it.scope == "title" && it.sourceUrl == "https://cdn.example.com/raw.webp" && it.label == "原始题图" })
        assertTrue(meta.questionMedia.any { it.scope == "option" && it.index == 0 && it.sourceUrl == "https://cdn.example.com/choice-1.png" })
    }

    @Test
    fun standardize_preserves_forced_choice_text_and_forced_text_answers() {
        val choiceRaw = JSONObject(
            """
            {
              "qstId": "q-choice",
              "questionType": 2,
              "selector": 1,
              "qstNo": "Q8",
              "qstTitle": "请问100+100等于多少",
              "choices": [
                {"choiceId": "c1", "choiceContent": "300"},
                {"choiceId": "c2", "choiceContent": "200"},
                {"choiceId": "c3", "choiceContent": "500"}
              ]
            }
            """.trimIndent(),
        )
        val textRaw = JSONObject(
            """
            {
              "qstId": "q-text",
              "questionType": 1,
              "qstNo": "Q9",
              "qstTitle": "本题检测是否认真作答，请输入：“你好”（仅输入引号内文字）"
            }
            """.trimIndent(),
        )

        val metas = CredamoApi.standardize(listOf(choiceRaw, textRaw)).first.associateBy { it.num }

        assertEquals(1, metas[8]?.forcedOptionIndex)
        assertEquals("200", metas[8]?.forcedOptionText)
        assertEquals(listOf("你好"), metas[9]?.forcedTexts)
    }

    @Test
    fun standardize_preserves_matrix_row_and_column_media() {
        val raw = JSONObject(
            """
            {
              "qstId": "q-matrix",
              "questionType": 25,
              "qstTitle": "矩阵",
              "choices": [
                {"choiceId": "r1", "choiceContent": "行1", "pic_url": "//cdn.example.com/row-1.gif"}
              ],
              "answers": [
                {"answerId": "c1", "answerContent": "列1", "src": "https://cdn.example.com/col-1.png"},
                {"answerId": "c2", "answerContent": "列2"}
              ]
            }
            """.trimIndent(),
        )

        val meta = CredamoApi.standardize(listOf(raw)).first.single()

        assertTrue(meta.questionMedia.any { it.scope == "row" && it.index == 0 && it.sourceUrl == "https://cdn.example.com/row-1.gif" })
        assertTrue(meta.questionMedia.any { it.scope == "option" && it.index == 0 && it.sourceUrl == "https://cdn.example.com/col-1.png" })
    }

    @Test
    fun standardize_detects_fillable_options_for_question_type_2_via_payload_heuristic() {
        // v4.0.3 credamo：questionType==2（单选）通过 fill/blank/input/other marker 启发式检测含填空的选项
        val raw = JSONObject(
            """
            {
              "qstId": "q-single",
              "questionType": 2,
              "selector": 1,
              "qstTitle": "来源",
              "choices": [
                {"choiceId": "c1", "choiceContent": "朋友推荐"},
                {"choiceId": "c2", "choiceContent": "其他", "fillBlank": "请填写"},
                {"choiceId": "c3", "choiceContent": "网络", "input": ""}
              ]
            }
            """.trimIndent(),
        )

        val meta = CredamoApi.standardize(listOf(raw)).first.single()

        // c2 的 fillBlank 非空 → fillable；c3 的 input 为空字符串 → 不算 fillable
        assertEquals(listOf(1), meta.fillableOptions)
    }

    @Test
    fun standardize_detects_fillable_options_with_other_marker_for_multiple_choice() {
        // v4.0.3 credamo：多选题（questionType==2, selector==2）的 other 标记
        val raw = JSONObject(
            """
            {
              "qstId": "q-multi",
              "questionType": 2,
              "selector": 2,
              "qstTitle": "渠道",
              "choices": [
                {"choiceId": "c1", "choiceContent": "A"},
                {"choiceId": "c2", "choiceContent": "B"},
                {"choiceId": "c3", "choiceContent": "其他", "other": true}
              ]
            }
            """.trimIndent(),
        )

        val meta = CredamoApi.standardize(listOf(raw)).first.single()

        assertEquals("multiple", meta.providerType)
        assertEquals(listOf(2), meta.fillableOptions)
    }

    @Test
    fun standardize_leaves_fillable_options_empty_for_matrix_and_text() {
        val matrixRaw = JSONObject(
            """
            {
              "qstId": "q-matrix",
              "questionType": 4,
              "qstTitle": "矩阵",
              "choices": [{"choiceContent": "行1", "fillBlank": "x"}],
              "answers": [{"answerContent": "列1"}]
            }
            """.trimIndent(),
        )
        val textRaw = JSONObject(
            """
            {
              "qstId": "q-text",
              "questionType": 1,
              "qstTitle": "填空"
            }
            """.trimIndent(),
        )

        val metas = CredamoApi.standardize(listOf(matrixRaw, textRaw)).first

        assertTrue(metas[0].fillableOptions.isEmpty())
        assertTrue(metas[1].fillableOptions.isEmpty())
    }
}
