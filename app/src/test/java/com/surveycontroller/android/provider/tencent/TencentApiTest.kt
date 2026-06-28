package com.surveycontroller.android.provider.tencent

import com.surveycontroller.android.data.ConfigPreflight
import com.surveycontroller.android.data.SurveyConfigDraft
import com.surveycontroller.android.core.model.QuestionEntryType
import com.surveycontroller.android.provider.SurveyDefinition
import com.surveycontroller.android.provider.SurveyProviderType
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TencentApiTest {

    @Test
    fun standardize_marks_unknown_question_type_unsupported_instead_of_skipping_it() {
        val questions = JSONArray(
            """
            [
              {
                "id": "q-1",
                "page_id": "p-1",
                "type": "radio",
                "title": "可支持题",
                "options": [{"id": "o-1", "text": "A"}]
              },
              {
                "id": "q-2",
                "page_id": "p-1",
                "type": "upload",
                "title": "上传文件"
              }
            ]
            """.trimIndent(),
        )

        val metas = TencentApi.standardize(questions)
        val unsupported = metas.single { it.providerQuestionId == "q-2" }

        assertEquals(2, metas.size)
        assertEquals(2, unsupported.num)
        assertEquals("0", unsupported.typeCode)
        assertEquals("upload", unsupported.providerType)
        assertTrue(unsupported.unsupported)
        assertTrue(unsupported.unsupportedReason.contains("upload"))

        val draft = SurveyConfigDraft.fromDefinition(
            SurveyDefinition(SurveyProviderType.QQ, "https://wj.qq.com/s2/123/abc/", "腾讯", metas),
        )
        val result = ConfigPreflight.validate(draft)
        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("暂不支持"))
    }

    @Test
    fun standardize_keeps_description_out_of_numbered_runtime_questions() {
        val questions = JSONArray(
            """
            [
              {"id": "intro", "page_id": "p-1", "type": "description", "title": "说明", "description": "请仔细阅读"},
              {
                "id": "q-1",
                "page_id": "p-1",
                "type": "radio",
                "title": "选择",
                "options": [{"id": "o-1", "text": "A"}]
              }
            ]
            """.trimIndent(),
        )

        val metas = TencentApi.standardize(questions)

        assertEquals(1, metas.size)
        assertEquals(1, metas.single().num)
        assertEquals("说明 选择", metas.single().title)
        assertEquals("请仔细阅读", metas.single().description)
        assertFalse(metas.single().unsupported)
    }

    @Test
    fun standardize_does_not_merge_description_across_pages() {
        val questions = JSONArray(
            """
            [
              {"id": "intro", "page_id": "p-1", "page": 1, "type": "description", "title": "第一页说明"},
              {
                "id": "q-1",
                "page_id": "p-2",
                "page": 2,
                "type": "radio",
                "title": "第二页题",
                "options": [{"id": "o-1", "text": "A"}]
              }
            ]
            """.trimIndent(),
        )

        val meta = TencentApi.standardize(questions).single()

        assertEquals("第二页题", meta.title)
        assertEquals(2, meta.page)
    }

    @Test
    fun standardize_preserves_checkbox_min_and_max_limits() {
        val questions = JSONArray(
            """
            [
              {
                "id": "q-1",
                "page_id": "p-1",
                "type": "checkbox",
                "title": "最多选两项",
                "min_length": "2",
                "max_length": 3,
                "options": [
                  {"id": "o-1", "text": "A"},
                  {"id": "o-2", "text": "B"},
                  {"id": "o-3", "text": "C"},
                  {"id": "o-4", "text": "D"}
                ]
              }
            ]
            """.trimIndent(),
        )

        val meta = TencentApi.standardize(questions).single()

        assertEquals("4", meta.typeCode)
        assertEquals(2, meta.multiMinLimit)
        assertEquals(3, meta.multiMaxLimit)
    }

    @Test
    fun standardize_cleans_fillblank_tokens_and_marks_fillable_options() {
        val questions = JSONArray(
            """
            [
              {
                "id": "q-1",
                "page_id": "p-1",
                "type": "checkbox",
                "title": "其他说明",
                "options": [
                  {"id": "o-1", "text": "固定选项"},
                  {"id": "o-2", "text": "其他___{fillblank-abc}"},
                  {"id": "o-3", "text": "补充{fillblank-def}", "extra": {"fillblank": true}}
                ]
              }
            ]
            """.trimIndent(),
        )

        val meta = TencentApi.standardize(questions).single()
        val draft = SurveyConfigDraft.fromDefinition(
            SurveyDefinition(SurveyProviderType.QQ, "https://wj.qq.com/s2/123/abc/", "腾讯", listOf(meta)),
        )

        assertEquals(listOf("固定选项", "其他", "补充"), meta.optionTexts)
        assertEquals(listOf(1, 2), meta.fillableOptions)
        assertEquals(listOf(1, 2), draft.questions.single().fillableOptionIndices)
    }

    @Test
    fun standardize_extracts_question_media_and_merges_description_media_on_same_page() {
        val questions = JSONArray(
            """
            [
              {
                "id": "intro",
                "page_id": "p-1",
                "type": "description",
                "title": "说明 ![intro](//cdn.example.com/intro.png)",
                "question_media": [
                  {"kind": "image", "scope": "title", "source_url": "https://cdn.example.com/raw-intro.webp", "label": "说明图"}
                ]
              },
              {
                "id": "q-1",
                "page_id": "p-1",
                "type": "matrix_radio",
                "title": "矩阵题 <img src='https://cdn.example.com/title.jpg'>",
                "options": [
                  {"id": "o-1", "text": "A", "image_url": "https://cdn.example.com/option-a.png"},
                  {"id": "o-2", "text": "B"}
                ],
                "sub_titles": [
                  {"id": "r-1", "text": "行1", "pic_url": "//cdn.example.com/row-1.gif"}
                ]
              }
            ]
            """.trimIndent(),
        )

        val meta = TencentApi.standardize(questions).single()

        assertEquals("说明 矩阵题", meta.title)
        assertEquals(5, meta.questionMedia.size)
        assertTrue(meta.questionMedia.any { it.scope == "title" && it.sourceUrl == "https://cdn.example.com/intro.png" })
        assertTrue(meta.questionMedia.any { it.scope == "title" && it.sourceUrl == "https://cdn.example.com/raw-intro.webp" && it.label == "说明图" })
        assertTrue(meta.questionMedia.any { it.scope == "title" && it.sourceUrl == "https://cdn.example.com/title.jpg" })
        assertTrue(meta.questionMedia.any { it.scope == "option" && it.index == 0 && it.sourceUrl == "https://cdn.example.com/option-a.png" })
        assertTrue(meta.questionMedia.any { it.scope == "row" && it.index == 0 && it.sourceUrl == "https://cdn.example.com/row-1.gif" })
    }

    @Test
    fun standardize_supports_tencent_rating_types_and_keeps_metadata() {
        val questions = JSONArray(
            """
            [
              {
                "id": "q-1",
                "page_id": "p-1",
                "type": "star",
                "title": "评分题",
                "star_begin_num": 1,
                "star_num": 5,
                "required": "1"
              },
              {
                "id": "q-2",
                "page_id": "p-1",
                "type": "matrix_star",
                "title": "矩阵评分",
                "star_num": 7,
                "sub_titles": [{"id": "r1", "text": "服务"}]
              }
            ]
            """.trimIndent(),
        )

        val metas = TencentApi.standardize(questions)
        val star = metas[0]
        val matrixStar = metas[1]

        assertEquals(listOf("1", "2", "3", "4", "5"), star.optionTexts)
        assertTrue(star.required)
        assertTrue(star.isRating)
        assertEquals(5, star.ratingMax)
        assertFalse(star.unsupported)
        assertEquals("", star.unsupportedReason)
        assertEquals(7, matrixStar.options)
        assertEquals(1, matrixStar.rows)
        assertFalse(matrixStar.unsupported)
        assertEquals("", matrixStar.unsupportedReason)

        val draft = SurveyConfigDraft.fromDefinition(
            SurveyDefinition(SurveyProviderType.QQ, "https://wj.qq.com/s2/123/abc/", "腾讯", metas),
        )
        val result = ConfigPreflight.validate(draft)
        assertTrue(result.canStart)
        assertEquals(QuestionEntryType.SCORE, draft.questions[0].entryType)
        assertEquals(QuestionEntryType.MATRIX, draft.questions[1].entryType)
    }

    @Test
    fun standardize_supports_number_as_text_like_question() {
        val questions = JSONArray(
            """
            [
              {
                "id": "q-age",
                "page_id": "p-1",
                "type": "number",
                "title": "年龄",
                "required": true
              }
            ]
            """.trimIndent(),
        )

        val meta = TencentApi.standardize(questions).single()
        val draft = SurveyConfigDraft.fromDefinition(
            SurveyDefinition(SurveyProviderType.QQ, "https://wj.qq.com/s2/123/abc/", "腾讯", listOf(meta)),
        )
        val result = ConfigPreflight.validate(draft)

        assertEquals("1", meta.typeCode)
        assertEquals("number", meta.providerType)
        assertTrue(meta.isTextLike)
        assertEquals(1, meta.textInputs)
        assertTrue(meta.required)
        assertFalse(meta.unsupported)
        assertTrue(result.canStart)
        assertEquals(QuestionEntryType.TEXT, draft.questions.single().entryType)
    }

    @Test
    fun description_merged_from_tencent_parser_participates_in_text_preflight() {
        val questions = JSONArray(
            """
            [
              {
                "id": "intro",
                "page_id": "p-1",
                "type": "description",
                "title": "填写要求",
                "description": "每项不少于 5 个字"
              },
              {
                "id": "q-1",
                "page_id": "p-1",
                "type": "text",
                "title": "意见"
              }
            ]
            """.trimIndent(),
        )
        val metas = TencentApi.standardize(questions)
        val base = SurveyConfigDraft.fromDefinition(
            SurveyDefinition(SurveyProviderType.QQ, "https://wj.qq.com/s2/123/abc/", "腾讯", metas),
        )
        val draft = base.copy(questions = base.questions.map { it.copy(textCandidates = mutableListOf("短")) })

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("最少 5 字"))
    }
}
