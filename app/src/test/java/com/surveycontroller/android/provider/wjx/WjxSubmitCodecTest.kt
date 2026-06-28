package com.surveycontroller.android.provider.wjx

import com.surveycontroller.android.core.model.AnswerAction
import com.surveycontroller.android.core.model.AttachedSelectChoice
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.provider.SubmitResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 wjx 协议编码与 Python 端 1:1 一致。
 */
class WjxSubmitCodecTest {

    @Test
    fun jqsign_xor_with_t_when_ktimes_multiple_of_10_uses_1() {
        // ktimes=90 → t=0 → 取 1；逐字符 XOR 1
        // '0'(0x30)^1=0x31'1'  'a'(0x61)^1=0x60'`'  '-'(0x2d)^1=0x2c','  '1'(0x31)^1=0x30'0'
        assertEquals("1`,0", WjxSubmitCodec.buildJqsign("0a-1", 90))
    }

    @Test
    fun jqsign_uses_ktimes_mod_10() {
        // ktimes=93 → t=3
        // '0'(0x30)^3=0x33'3'
        assertEquals("3", WjxSubmitCodec.buildJqsign("0", 93))
    }

    @Test
    fun scene_id_extraction_accepts_desktop_value_shapes_and_html_entities() {
        assertEquals(
            "scene-real-123",
            WjxSubmitCodec.extractSceneId("""<script>window.initAlicom({ sceneId: "scene-real-123" });</script>"""),
        )
        assertEquals(
            "scene-json-key",
            WjxSubmitCodec.extractSceneId("""<script>window.captchaConfig={"sceneId":"scene-json-key"}</script>"""),
        )
        assertEquals(
            "scene-from-entities",
            WjxSubmitCodec.extractSceneId("""<script>captchaConfig={sceneId:&quot;scene-from-entities&quot;}</script>"""),
        )
        assertEquals("q0hcfsca", WjxSubmitCodec.extractSceneId("<html></html>"))
    }

    @Test
    fun shortid_extraction_rejects_url_without_question_id() {
        assertEquals("abc123", WjxSubmitCodec.shortidFromUrl("https://www.wjx.cn/vm/abc123.aspx"))
        assertEquals("abc123", WjxSubmitCodec.shortidFromUrl("https://www.wjx.cn/vm/abc123.ASPX"))

        val error = assertThrows(IllegalStateException::class.java) {
            WjxSubmitCodec.shortidFromUrl("https://www.wjx.cn/")
        }

        assertTrue(error.message.orEmpty().contains("缺少 shortid"))
    }

    @Test
    fun single_choice_index_is_one_based() {
        val a = AnswerAction(questionNum = 1, kind = "choice", selectedIndices = listOf(1))
        assertEquals("2", WjxSubmitCodec.submitdataAnswer(a))
    }

    @Test
    fun multiple_choice_joined_with_pipe_and_fill_text() {
        val a = AnswerAction(
            questionNum = 2,
            kind = "choice",
            selectedIndices = listOf(0, 2),
            optionFillTexts = listOf(2 to "其他内容"),
        )
        // v4.0.5+：选项填充分隔符 ! → ^（! 留给矩阵 row!col）
        assertEquals("1|3^其他内容", WjxSubmitCodec.submitdataAnswer(a))
    }

    @Test
    fun multiple_choice_with_attached_select_appends_child_value_to_selected_option() {
        val a = AnswerAction(
            questionNum = 2,
            kind = "choice",
            selectedIndices = listOf(0, 2),
            attachedSelectChoices = listOf(AttachedSelectChoice(optionIndex = 2, selectedIndex = 1, value = "gz")),
        )

        assertEquals("1|3^gz", WjxSubmitCodec.submitdataAnswer(a))
    }

    @Test
    fun choice_with_attached_select_appends_child_value() {
        val a = AnswerAction(
            questionNum = 2,
            kind = "choice",
            selectedIndices = listOf(0),
            optionFillTexts = listOf(0 to "其他内容"),
            attachedSelectChoices = listOf(AttachedSelectChoice(optionIndex = 0, selectedIndex = 1, value = "2")),
        )

        assertEquals("1^其他内容^2", WjxSubmitCodec.submitdataAnswer(a))
    }

    @Test
    fun escape_submit_text_replaces_special_chars_like_desktop() {
        // v4.0.5+：$ } ^ | ! < 全部替换为全角/特殊字符
        assertEquals("ξ｝ˆ¦！＜", WjxSubmitCodec.escapeSubmitText("\$}^|!<"))
        assertEquals("", WjxSubmitCodec.escapeSubmitText(null))
        assertEquals("", WjxSubmitCodec.escapeSubmitText("  "))
        assertEquals("普通文本", WjxSubmitCodec.escapeSubmitText("普通文本"))
    }

    @Test
    fun choice_fill_with_special_chars_is_escaped_and_uses_caret_separator() {
        val a = AnswerAction(
            questionNum = 1,
            kind = "choice",
            selectedIndices = listOf(0),
            optionFillTexts = listOf(0 to "a\$b|c"),
        )
        // $ → ξ，| → ¦，分隔符用 ^
        assertEquals("1^aξb¦c", WjxSubmitCodec.submitdataAnswer(a))
    }

    @Test
    fun text_answer_with_special_chars_is_escaped() {
        val a = AnswerAction(questionNum = 1, kind = "text", textValues = listOf("a|b", "c!d"))
        // 多空用 ^ 分隔，| → ¦，! → ！
        assertEquals("a¦b^c！d", WjxSubmitCodec.submitdataAnswer(a))
    }

    @Test
    fun text_multi_blank_joined_with_caret() {
        val single = AnswerAction(questionNum = 1, kind = "text", textValues = listOf("hello"))
        assertEquals("hello", WjxSubmitCodec.submitdataAnswer(single))
        val multi = AnswerAction(questionNum = 1, kind = "text", textValues = listOf("a", "b"))
        assertEquals("a^b", WjxSubmitCodec.submitdataAnswer(multi))
    }

    @Test
    fun matrix_rows_encoded_as_row_bang_col() {
        val a = AnswerAction(questionNum = 3, kind = "matrix", matrixIndices = listOf(1, 2, 0))
        assertEquals("1!2,2!3,3!1", WjxSubmitCodec.submitdataAnswer(a))
    }

    @Test
    fun order_encoded_one_based_comma() {
        val a = AnswerAction(questionNum = 4, kind = "order", selectedIndices = listOf(2, 0, 1, 3))
        assertEquals("3,1,2,4", WjxSubmitCodec.submitdataAnswer(a))
    }

    @Test
    fun build_submitdata_formats_common_actions_like_desktop() {
        val data = WjxSubmitCodec.buildSubmitData(
            actions = listOf(
                AnswerAction(questionNum = 1, kind = "choice", selectedIndices = listOf(0), recordType = "single"),
                AnswerAction(questionNum = 2, kind = "choice", selectedIndices = listOf(0, 2), recordType = "multiple"),
                AnswerAction(questionNum = 3, kind = "text", textValues = listOf("甲", "乙"), recordType = "text"),
                AnswerAction(questionNum = 4, kind = "matrix", matrixIndices = listOf(1, 2), recordType = "matrix"),
                AnswerAction(questionNum = 5, kind = "slider", sliderValue = 66.0, recordType = "slider"),
            ),
            questions = emptyList(),
        )

        assertEquals("1\$1}2\$1|3}3\$甲^乙}4\$1!2,2!3}5\$66.0", data)
    }

    @Test
    fun build_submitdata_joins_questions_with_brace_and_replaces_chinese_comma() {
        val q1 = SurveyQuestionMeta(num = 1, typeCode = "3", options = 3)
        val q2 = SurveyQuestionMeta(num = 2, typeCode = "1")
        val a1 = AnswerAction(questionNum = 1, kind = "choice", selectedIndices = listOf(0))
        val a2 = AnswerAction(questionNum = 2, kind = "text", textValues = listOf("你好，世界"))
        val data = WjxSubmitCodec.buildSubmitData(listOf(a1, a2), listOf(q1, q2))
        assertEquals("1\$1}2\$你好,世界", data)
    }

    @Test
    fun build_submitdata_inserts_skipped_placeholder() {
        val q1 = SurveyQuestionMeta(num = 1, typeCode = "3", options = 3)
        val q2 = SurveyQuestionMeta(num = 2, typeCode = "6", rows = 2, options = 3)
        val a1 = AnswerAction(questionNum = 1, kind = "choice", selectedIndices = listOf(0))
        val data = WjxSubmitCodec.buildSubmitData(
            listOf(a1),
            listOf(q1, q2),
            skippedQuestionNums = listOf(2),
        )
        assertEquals("1\$1}2\$1!-3,2!-3", data)
    }

    @Test
    fun build_submitdata_keeps_frontend_skip_placeholders_like_desktop() {
        val q1 = SurveyQuestionMeta(num = 1, title = "单选", typeCode = "3", optionTexts = listOf("A", "B"), options = 2)
        val q2 = SurveyQuestionMeta(num = 2, title = "排序", typeCode = "11", optionTexts = listOf("A", "B", "C"), options = 3)
        val q3 = SurveyQuestionMeta(num = 3, title = "量表", typeCode = "5", optionTexts = listOf("1", "2"), options = 2)
        val q4 = SurveyQuestionMeta(num = 4, title = "填空", typeCode = "1", options = 1)
        val q5 = SurveyQuestionMeta(num = 5, title = "多选", typeCode = "4", optionTexts = listOf("A", "B"), options = 2)
        val data = WjxSubmitCodec.buildSubmitData(
            actions = listOf(
                AnswerAction(questionNum = 1, kind = "choice", selectedIndices = listOf(1), recordType = "single"),
                AnswerAction(questionNum = 5, kind = "choice", selectedIndices = listOf(0, 1), recordType = "multiple"),
            ),
            questions = listOf(q1, q2, q3, q4, q5),
            skippedQuestionNums = listOf(2, 3, 4),
        )

        assertEquals("1\$2}2\$-3,-3,-3}3\$-3}4\$(跳过)}5\$1|2", data)
    }

    @Test
    fun build_submitdata_rejects_empty_choice_before_submit() {
        val q = SurveyQuestionMeta(num = 1, title = "单选", typeCode = "3", optionTexts = listOf("A", "B"), options = 2)

        val error = assertThrows(IllegalStateException::class.java) {
            WjxSubmitCodec.buildSubmitData(
                actions = listOf(AnswerAction(questionNum = 1, kind = "choice", selectedIndices = emptyList())),
                questions = listOf(q),
            )
        }

        assertTrue(error.message.orEmpty().contains("没有生成选项答案"))
    }

    @Test
    fun build_submitdata_rejects_choice_index_out_of_range() {
        val q = SurveyQuestionMeta(num = 1, title = "单选", typeCode = "3", optionTexts = listOf("A", "B"), options = 2)

        val error = assertThrows(IllegalStateException::class.java) {
            WjxSubmitCodec.buildSubmitData(
                actions = listOf(AnswerAction(questionNum = 1, kind = "choice", selectedIndices = listOf(2))),
                questions = listOf(q),
            )
        }

        assertTrue(error.message.orEmpty().contains("选项越界"))
    }

    @Test
    fun build_submitdata_rejects_matrix_answers_with_missing_rows_or_bad_column() {
        val q = SurveyQuestionMeta(num = 2, title = "矩阵", typeCode = "6", rows = 2, options = 3, optionTexts = listOf("A", "B", "C"))

        val missingRow = assertThrows(IllegalStateException::class.java) {
            WjxSubmitCodec.buildSubmitData(
                actions = listOf(AnswerAction(questionNum = 2, kind = "matrix", matrixIndices = listOf(1))),
                questions = listOf(q),
            )
        }
        val badColumn = assertThrows(IllegalStateException::class.java) {
            WjxSubmitCodec.buildSubmitData(
                actions = listOf(AnswerAction(questionNum = 2, kind = "matrix", matrixIndices = listOf(1, 3))),
                questions = listOf(q),
            )
        }

        assertTrue(missingRow.message.orEmpty().contains("行数不足"))
        assertTrue(badColumn.message.orEmpty().contains("矩阵列越界"))
    }

    @Test
    fun build_submitdata_rejects_duplicate_order_answer() {
        val q = SurveyQuestionMeta(num = 3, title = "排序", typeCode = "11", options = 3, optionTexts = listOf("A", "B", "C"))

        val error = assertThrows(IllegalStateException::class.java) {
            WjxSubmitCodec.buildSubmitData(
                actions = listOf(AnswerAction(questionNum = 3, kind = "order", selectedIndices = listOf(0, 0, 2))),
                questions = listOf(q),
            )
        }

        assertTrue(error.message.orEmpty().contains("重复选项"))
    }

    @Test
    fun build_submitdata_rejects_slider_value_outside_declared_range() {
        val q = SurveyQuestionMeta(num = 4, title = "滑块", typeCode = "8", sliderMin = 0.0, sliderMax = 10.0)

        val error = assertThrows(IllegalStateException::class.java) {
            WjxSubmitCodec.buildSubmitData(
                actions = listOf(AnswerAction(questionNum = 4, kind = "slider", sliderValue = 12.0)),
                questions = listOf(q),
            )
        }

        assertTrue(error.message.orEmpty().contains("高于最大值"))
    }

    @Test
    fun classify_detects_verification() {
        assertTrue(WjxSubmitCodec.classifyResponse("需要安全校验，请重新提交") is SubmitResult.Verification)
    }

    @Test
    fun classify_detects_rejected_with_fullwidth_separator() {
        val r = WjxSubmitCodec.classifyResponse("0〒5〒该题为必答题") as SubmitResult.Rejected
        assertEquals(5, r.questionNum)
        assertEquals("该题为必答题", r.reason)
    }

    @Test
    fun classify_detects_success() {
        assertTrue(WjxSubmitCodec.classifyResponse("10") is SubmitResult.Success)
    }
}
