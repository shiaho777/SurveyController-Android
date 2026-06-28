package com.surveycontroller.android.core

import com.surveycontroller.android.core.model.AnswerAction
import com.surveycontroller.android.core.model.DisplayCondition
import com.surveycontroller.android.core.model.JumpRule
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.questions.AnswerRule
import com.surveycontroller.android.core.questions.AnsweredTracker
import com.surveycontroller.android.core.questions.ConsistencyEngine
import com.surveycontroller.android.core.questions.HttpLogicPlanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicAndRulesTest {

    @Test
    fun jump_rule_skips_questions_between() = runBlocking {
        // 第1题选项0 跳到第4题 → 第2、3题应被跳过
        val q1 = SurveyQuestionMeta(
            num = 1, typeCode = "3", options = 2, optionTexts = listOf("A", "B"),
            hasJump = true, logicParseStatus = "complete",
            jumpRules = listOf(JumpRule(optionIndex = 0, targetQuestion = 4)),
        )
        val q2 = SurveyQuestionMeta(num = 2, typeCode = "3", options = 2)
        val q3 = SurveyQuestionMeta(num = 3, typeCode = "3", options = 2)
        val q4 = SurveyQuestionMeta(num = 4, typeCode = "3", options = 2)
        val plan = HttpLogicPlanner.build(listOf(q1, q2, q3, q4)) { q ->
            // 第1题选中选项0以触发跳转
            if (q.num == 1) AnswerAction(questionNum = 1, kind = "choice", selectedIndices = listOf(0))
            else AnswerAction(questionNum = q.num, kind = "choice", selectedIndices = listOf(0))
        }
        assertEquals(listOf(1, 4), plan.actions.map { it.questionNum })
        assertTrue(2 in plan.skippedQuestionNums && 3 in plan.skippedQuestionNums)
    }

    @Test
    fun jump_rule_to_after_last_question_terminates_early() = runBlocking {
        val q1 = SurveyQuestionMeta(
            num = 1,
            typeCode = "3",
            options = 2,
            hasJump = true,
            logicParseStatus = "complete",
            jumpRules = listOf(JumpRule(optionIndex = 0, targetQuestion = 4)),
        )
        val q2 = SurveyQuestionMeta(num = 2, typeCode = "3", options = 2)
        val q3 = SurveyQuestionMeta(num = 3, typeCode = "3", options = 2)

        val plan = HttpLogicPlanner.build(listOf(q1, q2, q3)) { q ->
            AnswerAction(questionNum = q.num, kind = "choice", selectedIndices = listOf(0))
        }

        assertEquals(listOf(1), plan.actions.map { it.questionNum })
        assertTrue(plan.terminatedEarly)
        assertEquals(emptyList<Int>(), plan.skippedQuestionNums)
    }

    @Test
    fun unsupported_question_action_fails_instead_of_being_silently_skipped() {
        val q1 = SurveyQuestionMeta(num = 1, typeCode = "3", options = 2)
        val q2 = SurveyQuestionMeta(num = 2, typeCode = "3", options = 2)

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                HttpLogicPlanner.build(listOf(q1, q2)) { q ->
                    if (q.num == 2) null
                    else AnswerAction(questionNum = q.num, kind = "choice", selectedIndices = listOf(0))
                }
            }
        }

        assertEquals("第2题暂不支持纯 HTTP 提交", error.message)
    }

    @Test
    fun display_condition_hides_question_when_unmet() = runBlocking {
        // 第2题仅当第1题选了选项1才显示；第1题选了选项0 → 第2题隐藏
        val q1 = SurveyQuestionMeta(num = 1, typeCode = "3", options = 2, optionTexts = listOf("A", "B"))
        val q2 = SurveyQuestionMeta(
            num = 2, typeCode = "3", options = 2, logicParseStatus = "complete",
            hasDisplayCondition = true,
            displayConditions = listOf(DisplayCondition(conditionQuestionNum = 1, conditionMode = "selected", conditionOptionIndices = listOf(1))),
        )
        val plan = HttpLogicPlanner.build(listOf(q1, q2)) { q ->
            if (q.num == 1) AnswerAction(questionNum = 1, kind = "choice", selectedIndices = listOf(0))
            else AnswerAction(questionNum = 2, kind = "choice", selectedIndices = listOf(0))
        }
        assertEquals(listOf(1), plan.actions.map { it.questionNum })
        assertTrue(2 in plan.skippedQuestionNums)
    }

    @Test
    fun answer_rule_must_select_constrains_target() {
        val tracker = AnsweredTracker()
        // 第1题选中选项0
        tracker.record(AnswerAction(questionNum = 1, kind = "choice", selectedIndices = listOf(0), recordType = "single"))
        val rule = AnswerRule.fromMap(
            mapOf(
                "condition_question_num" to 1,
                "condition_mode" to "selected",
                "condition_option_indices" to listOf(0),
                "target_question_num" to 2,
                "action_mode" to "must_select",
                "target_option_indices" to listOf(2),
            ),
        )!!
        val engine = ConsistencyEngine(listOf(rule), tracker)
        // 目标题第2题：必选选项2 → 权重只保留索引2
        val result = engine.applySingleLike(listOf(1.0, 1.0, 1.0, 1.0), 2)
        assertEquals(0.0, result[0], 1e-9)
        assertEquals(0.0, result[1], 1e-9)
        assertTrue(result[2] > 0)
        assertEquals(0.0, result[3], 1e-9)
    }

    @Test
    fun answer_rule_not_triggered_when_condition_unmet() {
        val tracker = AnsweredTracker()
        tracker.record(AnswerAction(questionNum = 1, kind = "choice", selectedIndices = listOf(1), recordType = "single"))
        val rule = AnswerRule.fromMap(
            mapOf(
                "condition_question_num" to 1, "condition_mode" to "selected",
                "condition_option_indices" to listOf(0),
                "target_question_num" to 2, "action_mode" to "must_select",
                "target_option_indices" to listOf(2),
            ),
        )!!
        val engine = ConsistencyEngine(listOf(rule), tracker)
        val (must, _) = engine.multipleConstraint(2, 4)
        assertFalse(2 in must) // 条件未命中，不约束
    }

    @Test
    fun answer_rule_can_target_specific_matrix_rows() {
        val tracker = AnsweredTracker()
        tracker.record(
            AnswerAction(
                questionNum = 1,
                kind = "matrix",
                matrixIndices = listOf(0, 2),
                recordType = "matrix",
            ),
        )
        val rule = AnswerRule.fromMap(
            mapOf(
                "condition_question_num" to 1,
                "condition_row_index" to "1",
                "condition_mode" to "selected",
                "condition_option_indices" to listOf(2),
                "target_question_num" to 2,
                "target_row_index" to "0",
                "action_mode" to "must_select",
                "target_option_indices" to listOf(1),
            ),
        )!!
        val engine = ConsistencyEngine(listOf(rule), tracker)

        val row0 = engine.applyMatrixRow(listOf(1.0, 1.0, 1.0), 2, 0)
        val row1 = engine.applyMatrixRow(listOf(1.0, 1.0, 1.0), 2, 1)

        assertEquals(listOf(0.0, 1.0, 0.0), row0)
        assertEquals(listOf(1.0, 1.0, 1.0), row1)
    }
}
