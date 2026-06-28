package com.surveycontroller.android.provider.tencent

import com.surveycontroller.android.data.ConfigPreflight
import com.surveycontroller.android.data.SurveyConfigDraft
import com.surveycontroller.android.provider.SurveyDefinition
import com.surveycontroller.android.provider.SurveyProviderType
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TencentLogicTest {

    @Test
    fun attach_keeps_exact_jump_and_display_logic_complete() {
        val raw = JSONArray(
            """
            [
              {
                "id": "q-1",
                "page_id": "p-1",
                "type": "radio",
                "title": "入口",
                "options": [
                  {"id": "o-1", "text": "显示后续", "display": {"target": "q-2"}},
                  {"id": "o-2", "text": "跳到末题", "goto": "q-3"}
                ]
              },
              {
                "id": "q-2",
                "page_id": "p-1",
                "type": "radio",
                "title": "条件题",
                "options": [{"id": "o-3", "text": "A"}]
              },
              {
                "id": "q-3",
                "page_id": "p-1",
                "type": "radio",
                "title": "末题",
                "options": [{"id": "o-4", "text": "B"}]
              }
            ]
            """.trimIndent(),
        )

        val metas = TencentLogic.attach(TencentApi.standardize(raw), raw)
        val q1 = metas.first { it.providerQuestionId == "q-1" }
        val q2 = metas.first { it.providerQuestionId == "q-2" }

        assertTrue(q1.hasJump)
        assertEquals(3, q1.jumpRules.single().targetQuestion)
        assertTrue(q1.hasDependentDisplayLogic)
        assertEquals(2, q1.controlsDisplayTargets.single().targetQuestionNum)
        assertEquals("complete", q1.logicParseStatus)
        assertTrue(q2.hasDisplayCondition)
        assertEquals(1, q2.displayConditions.single().conditionQuestionNum)
        assertEquals(listOf(0), q2.displayConditions.single().conditionOptionIndices)
        assertEquals("complete", q2.logicParseStatus)
    }

    @Test
    fun attach_marks_unresolved_logic_unknown_so_preflight_blocks_http_run() {
        val raw = JSONArray(
            """
            [
              {
                "id": "q-1",
                "page_id": "p-1",
                "type": "radio",
                "title": "入口",
                "goto": {"custom": "dynamic-page"},
                "options": [
                  {"id": "o-1", "text": "A", "display": {"custom": "runtime-only"}}
                ]
              },
              {
                "id": "q-2",
                "page_id": "p-1",
                "type": "radio",
                "title": "隐藏题",
                "hidden": {"expr": "q-1-o-1"},
                "options": [{"id": "o-2", "text": "B"}]
              }
            ]
            """.trimIndent(),
        )

        val metas = TencentLogic.attach(TencentApi.standardize(raw), raw)
        val q1 = metas.first { it.providerQuestionId == "q-1" }
        val q2 = metas.first { it.providerQuestionId == "q-2" }
        val draft = SurveyConfigDraft.fromDefinition(
            SurveyDefinition(SurveyProviderType.QQ, "https://wj.qq.com/s2/123/abc/", "腾讯", metas),
        )

        val result = ConfigPreflight.validate(draft)

        assertTrue(q1.hasJump)
        assertTrue(q1.hasDependentDisplayLogic)
        assertEquals("unknown", q1.logicParseStatus)
        assertTrue(q2.hasDisplayCondition)
        assertEquals("unknown", q2.logicParseStatus)
        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("逻辑"))
        assertTrue(result.blockingMessage().contains("未完整解析"))
    }

    @Test
    fun attach_refer_fallback_preserves_source_targets_for_configuration_visibility() {
        val raw = JSONArray(
            """
            [
              {
                "id": "q-1",
                "page_id": "p-1",
                "type": "radio",
                "title": "来源题",
                "options": [{"id": "o-1", "text": "A"}]
              },
              {
                "id": "q-2",
                "page_id": "p-1",
                "type": "radio",
                "title": "依赖题",
                "refer": {"question": "q-1"},
                "options": [{"id": "o-2", "text": "B"}]
              }
            ]
            """.trimIndent(),
        )

        val metas = TencentLogic.attach(TencentApi.standardize(raw), raw)
        val source = metas.first { it.providerQuestionId == "q-1" }
        val target = metas.first { it.providerQuestionId == "q-2" }

        assertTrue(source.hasDependentDisplayLogic)
        assertEquals(2, source.controlsDisplayTargets.single().targetQuestionNum)
        assertTrue(target.hasDisplayCondition)
        assertEquals(1, target.displayConditions.single().conditionQuestionNum)
        assertTrue(target.displayConditions.single().conditionOptionIndices.isEmpty())
        assertEquals("unknown", target.logicParseStatus)
    }
}
