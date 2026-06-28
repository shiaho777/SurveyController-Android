package com.surveycontroller.android.core.model

data class AttachedSelectChoice(
    val optionIndex: Int,
    val selectedIndex: Int,
    val value: String = "",
)

/**
 * 单题作答结果的标准化表示。对应 Python 端 software/providers/answering/actions.py:AnswerAction。
 * 由答案生成算法产出，再由各 Provider 编码为平台特定的提交格式。
 */
data class AnswerAction(
    val questionNum: Int = 0,
    val kind: String = "",                 // choice / select / text / matrix / slider / order
    val questionId: String = "",
    val rootIndex: Int = -1,
    val inputType: String = "",
    val selectedIndices: List<Int> = emptyList(),
    val matrixIndices: List<Int> = emptyList(),
    val scalarValue: Int? = null,
    val textValues: List<String> = emptyList(),
    val sliderValue: Double? = null,
    /** 选项内嵌填空：(选项索引, 文本) */
    val optionFillTexts: List<Pair<Int, String>> = emptyList(),
    /** 单选主选项内嵌下拉：(主选项索引, 子下拉选中索引, 子选项 value/text) */
    val attachedSelectChoices: List<AttachedSelectChoice> = emptyList(),
    val selectedTexts: List<String> = emptyList(),
    val recordType: String = "",
    /** 待记录的分布选择：(configIndex, optionIndex, slot) */
    val pendingDistributionChoices: List<Triple<Int, Int, Int?>> = emptyList(),
) {
    val configQuestionNum: Int get() = if (rootIndex > 0) rootIndex else questionNum
}

/** 答案生成的整体计划：动作列表 + 被跳过的题号。 */
data class AnswerPlan(
    val actions: List<AnswerAction> = emptyList(),
    val skippedQuestionNums: List<Int> = emptyList(),
)
