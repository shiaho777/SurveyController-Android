package com.surveycontroller.android.core.model

/**
 * 题目的语义类型（entry type）。
 * 对应 Python 端 software/core/questions/meta_helpers.py:infer_question_entry_type。
 */
enum class QuestionEntryType {
    TEXT,        // 单/多行填空
    SINGLE,      // 单选
    MULTIPLE,    // 多选
    DROPDOWN,    // 下拉
    SCALE,       // 量表（type 5 非评价）
    SCORE,       // 评价/星级（type 5 评价）
    MATRIX,      // 矩阵 / 滑块矩阵
    SLIDER,      // 滑块
    ORDER,       // 排序
    MULTI_TEXT,  // 多项填空
    LOCATION,    // 地区/定位题
    ;

    companion object {
        /**
         * 1:1 复刻 infer_question_entry_type 的判定优先级。
         */
        fun infer(meta: SurveyQuestionMeta): QuestionEntryType {
            val typeCode = normalizeTypeCode(meta.typeCode)
            if (meta.isLocation) return LOCATION
            if (meta.isSliderMatrix) return MATRIX
            if (meta.isMultiText || (meta.isTextLike && meta.textInputs > 1)) return MULTI_TEXT
            if (meta.isTextLike || typeCode == "1" || typeCode == "2") return TEXT
            return when (typeCode) {
                "3" -> SINGLE
                "4" -> MULTIPLE
                "5" -> if (meta.isRating) SCORE else SCALE
                "6", "9" -> MATRIX
                "7" -> DROPDOWN
                "8" -> SLIDER
                "11" -> ORDER
                else -> SINGLE
            }
        }

        fun normalizeTypeCode(raw: String?): String =
            raw?.trim()?.takeIf { it.isNotEmpty() } ?: "0"
    }
}
