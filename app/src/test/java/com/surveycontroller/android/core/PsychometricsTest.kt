package com.surveycontroller.android.core

import com.surveycontroller.android.core.model.ExecutionConfig
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.psychometrics.JointOptimizer
import com.surveycontroller.android.core.psychometrics.OrdinalOptions
import com.surveycontroller.android.core.psychometrics.Orientation
import com.surveycontroller.android.core.psychometrics.PsychoMath
import com.surveycontroller.android.data.ConfigCompiler
import com.surveycontroller.android.data.SurveyConfigDraft
import com.surveycontroller.android.provider.SurveyDefinition
import com.surveycontroller.android.provider.SurveyProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PsychometricsTest {

    @Test
    fun cronbach_alpha_high_for_correlated_items() {
        // 三道高度相关的题：每个样本各题得分相同 → α 应接近 1
        val matrix = listOf(
            listOf(1.0, 1.0, 1.0),
            listOf(3.0, 3.0, 3.0),
            listOf(5.0, 5.0, 5.0),
            listOf(2.0, 2.0, 2.0),
        )
        assertTrue("alpha=${PsychoMath.cronbachAlpha(matrix)}", PsychoMath.cronbachAlpha(matrix) > 0.95)
    }

    @Test
    fun bias_target_probabilities_sum_to_one() {
        val probs = Orientation.buildBiasTargetProbabilities(5, "right")
        assertEquals(1.0, probs.sum(), 1e-9)
        // 右偏：最后一项权重最大
        assertTrue(probs.last() > probs.first())
    }

    @Test
    fun joint_optimizer_locks_choices_for_dimension() {
        // 构造两道同维度量表题，开启信度模式，应生成锁定计划
        val q1 = SurveyQuestionMeta(num = 1, typeCode = "5", options = 5, optionTexts = listOf("1", "2", "3", "4", "5"))
        val q2 = SurveyQuestionMeta(num = 2, typeCode = "5", options = 5, optionTexts = listOf("1", "2", "3", "4", "5"))
        val config = ExecutionConfig(
            targetNum = 30,
            scaleProb = listOf(-1, -1),
            questionConfigIndexMap = mapOf(1 to ("scale" to 0), 2 to ("scale" to 1)),
            questionDimensionMap = mapOf(1 to "满意度", 2 to "满意度"),
            questionsMetadata = mapOf(1 to q1, 2 to q2),
            reliabilityModeEnabled = true,
        )
        val plan = JointOptimizer.build(config)
        assertTrue("应生成联合计划", plan != null)
        val sample = plan!!.samplePlan(0)
        assertTrue("第1题应被锁定", sample.isLocked(1, null))
        assertTrue("第2题应被锁定", sample.isLocked(2, null))
        val c = sample.getChoice(1, null)
        assertTrue("选择应在范围内", c != null && c in 0..4)
    }

    @Test
    fun ordinal_options_infer_numeric_and_reversed_text_scores() {
        assertEquals(listOf(0, 1, 2, 3, 4), OrdinalOptions.infer(listOf("1分", "2分", "3分", "4分", "5分"))?.scoreByChoiceIndex)
        assertEquals(listOf(4, 3, 2, 1, 0), OrdinalOptions.infer(listOf("非常满意", "满意", "一般", "不满意", "非常不满意"))?.scoreByChoiceIndex)
    }

    @Test
    fun ordinal_options_infer_attitude_scale_v404() {
        assertEquals(
            listOf(0, 1, 2, 3, 4),
            OrdinalOptions.infer(listOf("非常不同意", "比较不同意", "没意见", "比较同意", "非常同意"))?.scoreByChoiceIndex
        )
        assertEquals(
            listOf(4, 3, 2, 1, 0),
            OrdinalOptions.infer(listOf("非常同意", "比较同意", "没意见", "比较不同意", "非常不同意"))?.scoreByChoiceIndex
        )
    }

    @Test
    fun compiler_and_joint_optimizer_include_ordinal_single_questions() {
        val q1 = SurveyQuestionMeta(num = 1, title = "满意度A", typeCode = "3", options = 5, optionTexts = listOf("非常不满意", "不满意", "一般", "满意", "非常满意"))
        val q2 = SurveyQuestionMeta(num = 2, title = "满意度B", typeCode = "3", options = 5, optionTexts = listOf("非常满意", "满意", "一般", "不满意", "非常不满意"))
        val base = SurveyConfigDraft.fromDefinition(
            SurveyDefinition(SurveyProviderType.WJX, "https://www.wjx.cn/vm/ordinal.aspx", "有序单选", listOf(q1, q2)),
        )
        val draft = base.copy(
            params = base.params.copy(targetNum = 24),
            questions = base.questions.map { it.copy(dimension = "满意度") },
        )

        val config = ConfigCompiler.compile(draft)
        val blueprint = JointOptimizer.buildBlueprint(config)

        assertEquals(listOf(0, 1, 2, 3, 4), config.questionOrdinalScoreMap[1])
        assertEquals(listOf(4, 3, 2, 1, 0), config.questionOrdinalScoreMap[2])
        assertEquals(2, blueprint["满意度"]?.size)
        val plan = JointOptimizer.build(config)
        assertTrue("有序单选应参与联合计划", plan != null)
        assertTrue(plan!!.samplePlan(0).isLocked(1, null))
        assertTrue(plan.samplePlan(0).isLocked(2, null))
    }

    @Test
    fun compiler_assigns_global_reliability_dimension_when_no_explicit_groups_exist() {
        val q1 = SurveyQuestionMeta(num = 1, title = "满意度A", typeCode = "5", options = 5, optionTexts = listOf("1", "2", "3", "4", "5"))
        val q2 = SurveyQuestionMeta(num = 2, title = "满意度B", typeCode = "5", options = 5, optionTexts = listOf("1", "2", "3", "4", "5"))
        val draft = SurveyConfigDraft.fromDefinition(
            SurveyDefinition(SurveyProviderType.WJX, "https://www.wjx.cn/vm/global.aspx", "全局信度", listOf(q1, q2)),
        )

        val config = ConfigCompiler.compile(draft)
        val blueprint = JointOptimizer.buildBlueprint(config)

        assertEquals("__global_reliability__", config.questionDimensionMap[1])
        assertEquals("__global_reliability__", config.questionDimensionMap[2])
        assertEquals(2, blueprint["__global_reliability__"]?.size)
        assertTrue("未手动分组时也应生成全局联合计划", JointOptimizer.build(config) != null)
    }

    @Test
    fun compiler_does_not_assign_reliability_dimensions_when_mode_is_disabled() {
        val q1 = SurveyQuestionMeta(num = 1, title = "满意度A", typeCode = "5", options = 5, optionTexts = listOf("1", "2", "3", "4", "5"))
        val q2 = SurveyQuestionMeta(num = 2, title = "满意度B", typeCode = "5", options = 5, optionTexts = listOf("1", "2", "3", "4", "5"))
        val base = SurveyConfigDraft.fromDefinition(
            SurveyDefinition(SurveyProviderType.WJX, "https://www.wjx.cn/vm/no-reliability.aspx", "关闭信度", listOf(q1, q2)),
        )
        val draft = base.copy(params = base.params.copy(reliabilityModeEnabled = false))

        val config = ConfigCompiler.compile(draft)

        assertTrue(config.questionDimensionMap.values.none { !it.isNullOrBlank() })
        assertTrue(JointOptimizer.buildBlueprint(config).isEmpty())
    }

    @Test
    fun compiler_keeps_text_titles_for_runtime_config_fidelity() {
        val q1 = SurveyQuestionMeta(num = 1, title = "姓名", typeCode = "1", isTextLike = true, textInputs = 1)
        val q2 = SurveyQuestionMeta(num = 2, title = "年龄", typeCode = "1", isTextLike = true, textInputs = 1)
        val draft = SurveyConfigDraft.fromDefinition(
            SurveyDefinition(SurveyProviderType.WJX, "https://www.wjx.cn/vm/texts.aspx", "填空标题", listOf(q1, q2)),
        )

        val config = ConfigCompiler.compile(draft)

        assertEquals(listOf("姓名", "年龄"), config.textTitles)
    }

    @Test
    fun compiler_normalizes_single_like_weights_to_full_option_count() {
        val q1 = SurveyQuestionMeta(num = 1, title = "满意度A", typeCode = "5", options = 4, optionTexts = listOf("1", "2", "3", "4"))
        val q2 = SurveyQuestionMeta(num = 2, title = "满意度B", typeCode = "5", options = 4, optionTexts = listOf("1", "2", "3", "4"))
        val base = SurveyConfigDraft.fromDefinition(
            SurveyDefinition(SurveyProviderType.WJX, "https://www.wjx.cn/vm/short-weights.aspx", "短权重", listOf(q1, q2)),
        )
        val draft = base.copy(
            questions = base.questions.map {
                it.copy(distributionMode = "custom", optionWeights = mutableListOf(2.0, 1.0), dimension = "满意度")
            },
        )

        val config = ConfigCompiler.compile(draft)
        val firstProb = config.scaleProb.first() as List<*>
        val blueprint = JointOptimizer.buildBlueprint(config)

        assertEquals(listOf(2.0 / 3.0, 1.0 / 3.0, 0.0, 0.0), firstProb)
        assertEquals(4, blueprint["满意度"]?.first()?.optionCount)
        assertEquals(listOf(2.0 / 3.0, 1.0 / 3.0, 0.0, 0.0), blueprint["满意度"]?.first()?.targetProbabilities)
    }

    @Test
    fun compiler_normalizes_matrix_rows_to_full_option_count() {
        val q = SurveyQuestionMeta(
            num = 1,
            title = "矩阵",
            typeCode = "6",
            rows = 2,
            options = 3,
            rowTexts = listOf("R1", "R2"),
            optionTexts = listOf("A", "B", "C"),
        )
        val base = SurveyConfigDraft.fromDefinition(
            SurveyDefinition(SurveyProviderType.WJX, "https://www.wjx.cn/vm/matrix-weights.aspx", "矩阵权重", listOf(q)),
        )
        val draft = base.copy(
            questions = base.questions.map {
                it.copy(
                    distributionMode = "custom",
                    dimension = "态度",
                    matrixRowWeights = mutableListOf(
                        mutableListOf(2.0),
                        mutableListOf(0.0, 3.0, 3.0, 9.0),
                    ),
                )
            },
        )

        val config = ConfigCompiler.compile(draft)

        assertEquals(listOf(1.0, 0.0, 0.0), config.matrixProb[0])
        assertEquals(listOf(0.0, 0.5, 0.5), config.matrixProb[1])
    }

    @Test
    fun joint_optimizer_uses_saved_psycho_bias_for_random_ratio_items() {
        val q1 = SurveyQuestionMeta(num = 1, title = "量表A", typeCode = "5", options = 5, optionTexts = listOf("1", "2", "3", "4", "5"))
        val q2 = SurveyQuestionMeta(num = 2, title = "量表B", typeCode = "5", options = 5, optionTexts = listOf("1", "2", "3", "4", "5"))
        val config = ExecutionConfig(
            targetNum = 20,
            scaleProb = listOf(listOf(-1.0), listOf(-1.0)),
            questionConfigIndexMap = mapOf(1 to ("scale" to 0), 2 to ("scale" to 1)),
            questionDimensionMap = mapOf(1 to "满意度", 2 to "满意度"),
            questionPsychoBiasMap = mapOf(1 to "left", 2 to "right"),
            questionsMetadata = mapOf(1 to q1, 2 to q2),
            reliabilityModeEnabled = true,
        )

        val items = JointOptimizer.buildBlueprint(config)["满意度"].orEmpty()

        assertEquals(listOf("left", "right"), items.map { it.bias })
        assertTrue(items[0].targetProbabilities.first() > items[0].targetProbabilities.last())
        assertTrue(items[1].targetProbabilities.last() > items[1].targetProbabilities.first())
    }
}
