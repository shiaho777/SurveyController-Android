package com.surveycontroller.android.core.psychometrics

data class OrdinalOptionMapping(val scoreByChoiceIndex: List<Int>) {
    val optionCount: Int get() = scoreByChoiceIndex.size
}

object OrdinalOptions {
    private val numericPattern = Regex("""^\s*(\d+)(?:\s*(?:分|点|级|星))?\s*$""")
    private val chineseNumbers = mapOf(
        "一" to 1,
        "二" to 2,
        "三" to 3,
        "四" to 4,
        "五" to 5,
        "六" to 6,
        "七" to 7,
        "八" to 8,
        "九" to 9,
        "十" to 10,
    )
    private val ordinalGroups = listOf(
        listOf("非常不满意", "不满意", "一般", "满意", "非常满意"),
        listOf("很不满意", "不满意", "一般", "满意", "很满意"),
        listOf("非常不同意", "不同意", "一般", "同意", "非常同意"),
        listOf("很不同意", "不同意", "一般", "同意", "很同意"),
        listOf("很差", "较差", "一般", "较好", "很好"),
        listOf("非常差", "差", "一般", "好", "非常好"),
        listOf("从不", "偶尔", "有时", "经常", "总是"),
        listOf("完全没有", "较少", "一般", "较多", "非常多"),
    )
    private val attitudeNeutralTexts = setOf("一般", "中立", "没意见", "无意见", "普通", "不好说", "说不清", "不确定")
    private val attitudeExtremeMarkers = listOf("非常", "很", "极其", "十分", "完全", "特别", "强烈")
    private val attitudeMildMarkers = listOf("比较", "较", "不太", "有点", "稍微", "略", "有些")
    private val attitudeNegativeCores = listOf(
        "不同意",
        "不满意",
        "不认可",
        "不支持",
        "不愿意",
        "不赞成",
        "不太同意",
        "不太满意",
        "不太认可",
        "不太支持",
        "不太愿意",
        "不太赞成",
        "不太好",
        "反对",
        "不好",
        "不佳",
        "差",
        "没有",
        "少",
        "较少",
        "很少",
        "从不",
    )
    private val attitudePositiveCores = listOf(
        "同意",
        "满意",
        "认可",
        "支持",
        "愿意",
        "赞成",
        "好",
        "多",
        "经常",
        "总是",
    )

    fun infer(optionTexts: Iterable<Any?>): OrdinalOptionMapping? {
        val texts = optionTexts.map { normalize(it) }.filter { it.isNotEmpty() }
        if (texts.size < 2) return null
        val scores = parseNumeric(texts)
            ?: parseChineseNumeric(texts)
            ?: matchTextGroup(texts)
            ?: matchAttitudeScale(texts)
            ?: return null
        if (scores.size != texts.size) return null
        if (scores.sorted() != texts.indices.toList()) return null
        return OrdinalOptionMapping(scores)
    }

    private fun normalize(value: Any?): String =
        value?.toString()?.trim()?.replace(Regex("\\s+"), "") ?: ""

    private fun parseNumeric(texts: List<String>): List<Int>? {
        val values = mutableListOf<Int>()
        for (text in texts) {
            val value = numericPattern.matchEntire(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
            values.add(value)
        }
        return scoreContinuousValues(values)
    }

    private fun parseChineseNumeric(texts: List<String>): List<Int>? {
        val values = mutableListOf<Int>()
        for (text in texts) {
            val key = text.removeSuffix("分").removeSuffix("点").removeSuffix("级").removeSuffix("星")
            values.add(chineseNumbers[key] ?: return null)
        }
        return scoreContinuousValues(values)
    }

    private fun scoreContinuousValues(values: List<Int>): List<Int>? {
        if (values.size < 2) return null
        val asc = List(values.size) { values.first() + it }
        if (values == asc) return values.map { it - values.minOrNull()!! }
        val desc = List(values.size) { values.first() - it }
        if (values == desc) {
            val max = values.maxOrNull() ?: return null
            return values.map { max - it }
        }
        return null
    }

    private fun matchTextGroup(texts: List<String>): List<Int>? {
        if (texts.size < 2) return null
        for (group in ordinalGroups) {
            val normalized = group.map { normalize(it) }
            if (texts == normalized.take(texts.size)) return texts.indices.toList()
            val tail = normalized.takeLast(texts.size)
            if (texts == tail.reversed()) return texts.indices.reversed().toList()
            if (texts.size == normalized.size && texts == normalized.reversed()) return texts.indices.reversed().toList()
        }
        return null
    }

    private fun matchAttitudeScale(texts: List<String>): List<Int>? {
        if (texts.size != 5) return null
        val scores = texts.map { scoreAttitudeOption(it) ?: return null }
        if (scores.sorted() != listOf(0, 1, 2, 3, 4)) return null
        return scores
    }

    private fun scoreAttitudeOption(text: String): Int? {
        if (text in attitudeNeutralTexts) return 2
        val isNegative = attitudeNegativeCores.any { it in text }
        val isPositive = !isNegative && attitudePositiveCores.any { it in text }
        if (!isNegative && !isPositive) return null
        val isExtreme = attitudeExtremeMarkers.any { it in text }
        val isMild = attitudeMildMarkers.any { it in text }
        return if (isNegative) (if (isExtreme && !isMild) 0 else 1)
        else (if (isExtreme && !isMild) 4 else 3)
    }
}
