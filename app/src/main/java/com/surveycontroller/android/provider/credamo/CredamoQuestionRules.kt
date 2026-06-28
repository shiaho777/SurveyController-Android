package com.surveycontroller.android.provider.credamo

/**
 * 见数题目的强制项/算术题/多选限制解析。1:1 复刻 credamo/provider/parser.py 的对应函数。
 */
object CredamoQuestionRules {
    data class ForcedChoice(val index: Int, val text: String)

    private val cleanRe = Regex("[\\s`'\"“”‘’【】\\[\\]()（）<>《》,，、。；;:：!?！？]")
    private val commandRe = Regex("请(?:务必|一定|必须|直接)?\\s*选(?:择)?")
    private val indexRe = Regex("^第?\\s*(\\d{1,3})\\s*(?:个|项|选项|分|星)?$")
    private val sentenceSplitRe = Regex("[。；;！？!\\n\\r]")
    private val labelTargetRe = Regex("^([A-Za-z])(?:项|选项|答案)?$")
    private val optionLabelRe = Regex("^(?:第\\s*)?[(（【\\[]?\\s*([A-Za-z])\\s*[)）】\\]]?(?=\$|[.．、:：\\-\\s]|[\\u4e00-\\u9fff])")
    private val arithmeticRe = Regex("(?<!\\d)(\\d+(?:\\.\\d+)?(?:\\s*[+\\-*/×xX÷]\\s*\\d+(?:\\.\\d+)?)+)(?!\\d)")
    private val optionNumberRe = Regex("-?\\d+(?:\\.\\d+)?")
    private val forceTextRe = Regex("请(?:务必|一定|必须|直接)?\\s*(?:输入|填写|填入|写入)\\s*[：:\\s]*[\"“'‘]?([^\"”'’\\s，,。；;！!？?）)]+)")
    private val multiLimitRe = Regex("(?:[\\[【（(]\\s*)?(至多|最多|不超过|至多可|最多可|至少|最少|不少于)\\s*(?:可)?(?:选择|选)?\\s*(\\d{1,3}|[零〇一二两三四五六七八九十百]{1,4})\\s*(?:个)?(?:选项|项)?(?:\\s*[\\]】）)])?")
    private val multiRangeRe = Regex("(?:[\\[【（(]\\s*)?(?:请)?(?:选择|选)\\s*(\\d{1,3}|[零〇一二两三四五六七八九十百]{1,4})\\s*(?:-|~|～|至|到)\\s*(\\d{1,3}|[零〇一二两三四五六七八九十百]{1,4})\\s*(?:个)?(?:选项|项)(?:\\s*[\\]】）)])?")

    private fun normalize(value: String?): String =
        value?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

    private fun normalizeForceSelect(value: String?): String {
        val t = normalize(value)
        return if (t.isEmpty()) "" else cleanRe.replace(t, "").lowercase()
    }

    private fun optionLabel(optionText: String?): String? {
        val m = optionLabelRe.find(normalize(optionText)) ?: return null
        return m.groupValues[1].trim().uppercase().ifEmpty { null }
    }

    /** 解析"请选第X项/某文本"强制选项，返回选项索引与选项文本。 */
    fun extractForceSelectOption(title: String, optionTexts: List<String>, extraFragments: List<String> = emptyList()): ForcedChoice? {
        if (optionTexts.isEmpty()) return null
        val normalizedOptions = optionTexts.mapIndexedNotNull { idx, opt ->
            val raw = normalize(opt)
            val norm = normalizeForceSelect(raw)
            if (norm.isNotEmpty()) Triple(idx, raw, norm) else null
        }
        if (normalizedOptions.isEmpty()) return null
        val fragments = (listOf(title) + extraFragments).map { normalize(it) }.filter { it.isNotEmpty() }.distinct()

        for (fragment in fragments) {
            for (cmd in commandRe.findAll(fragment)) {
                val rest = fragment.substring(cmd.range.last + 1)
                val sentence = sentenceSplitRe.split(rest).firstOrNull().orEmpty().trim(' ', '：', ':', '，', ',', '、')
                val compact = normalizeForceSelect(sentence)
                if (compact.isEmpty()) continue

                var bestIndex: Int? = null
                var bestLen = -1
                for ((idx, _, norm) in normalizedOptions) {
                    if (norm.all { it.isDigit() }) continue
                    if (norm in compact && norm.length > bestLen) { bestIndex = idx; bestLen = norm.length }
                }
                if (bestIndex != null) {
                    return ForcedChoice(bestIndex, normalize(optionTexts.getOrNull(bestIndex)))
                }

                labelTargetRe.matchEntire(compact)?.let { lm ->
                    val target = lm.groupValues[1].uppercase()
                    for ((idx, raw, _) in normalizedOptions) if (optionLabel(raw) == target) return ForcedChoice(idx, raw)
                }
                indexRe.matchEntire(sentence)?.let { im ->
                    val targetIdx = (im.groupValues[1].toIntOrNull() ?: 0) - 1
                    if (targetIdx in optionTexts.indices) {
                        return ForcedChoice(targetIdx, normalize(optionTexts[targetIdx]))
                    }
                }
            }
        }
        return null
    }

    private fun safeEvalArithmetic(expr: String): Double? {
        val text = expr.trim().replace("×", "*").replace("x", "*").replace("X", "*").replace("÷", "/")
        if (text.isEmpty() || !Regex("[\\d\\s+\\-*/.]+").matches(text)) return null
        return try {
            ArithmeticEval(text).parse()
        } catch (e: Exception) {
            null
        }
    }

    private fun optionNumericValue(optionText: String): Double? =
        optionNumberRe.find(normalize(optionText))?.value?.toDoubleOrNull()

    /** 解析算术题(如"3+5=?")并匹配数值选项。 */
    fun extractArithmeticOption(title: String, optionTexts: List<String>, extraFragments: List<String> = emptyList()): ForcedChoice? {
        if (optionTexts.isEmpty()) return null
        for (fragment in (listOf(title) + extraFragments).map { normalize(it) }.filter { it.isNotEmpty() }) {
            for (m in arithmeticRe.findAll(fragment)) {
                val result = safeEvalArithmetic(m.groupValues[1]) ?: continue
                optionTexts.forEachIndexed { idx, opt ->
                    val v = optionNumericValue(opt)
                    if (v != null && kotlin.math.abs(v - result) < 1e-9) return ForcedChoice(idx, normalize(opt))
                }
            }
        }
        return null
    }

    private fun parseCountToken(raw: String): Int? {
        val text = normalize(raw)
        if (text.isEmpty()) return null
        if (text.all { it.isDigit() }) return text.toIntOrNull()
        val digitMap = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        var total = 0; var current = 0
        for (ch in text) {
            when {
                ch in digitMap -> current = digitMap[ch]!!
                ch == '十' -> { total += (if (current == 0) 1 else current) * 10; current = 0 }
                ch == '百' -> { total += (if (current == 0) 1 else current) * 100; current = 0 }
                else -> return null
            }
        }
        val result = total + current
        return if (result > 0) result else null
    }

    /** 解析多选限制：返回 (min, max)。 */
    fun extractMultiSelectLimits(title: String, optionCount: Int, extraFragments: List<String> = emptyList()): Pair<Int?, Int?> {
        var minLimit: Int? = null
        var maxLimit: Int? = null
        val upper = maxOf(0, optionCount)
        val fragments = (listOf(title) + extraFragments).map { normalize(it) }.filter { it.isNotEmpty() }.distinct()
        for (fragment in fragments) {
            for (m in multiLimitRe.findAll(fragment)) {
                var count = parseCountToken(m.groupValues[2]) ?: continue
                count = maxOf(1, count)
                if (upper > 0) count = minOf(count, upper)
                when (m.groupValues[1]) {
                    "至少", "最少", "不少于" -> minLimit = if (minLimit == null) count else maxOf(minLimit!!, count)
                    "至多", "最多", "不超过", "至多可", "最多可" -> maxLimit = if (maxLimit == null) count else minOf(maxLimit!!, count)
                }
            }
            for (m in multiRangeRe.findAll(fragment)) {
                val pmin = parseCountToken(m.groupValues[1]) ?: continue
                val pmax = parseCountToken(m.groupValues[2]) ?: continue
                var rMin = maxOf(1, pmin); var rMax = maxOf(rMin, pmax)
                if (upper > 0) { rMin = minOf(rMin, upper); rMax = minOf(rMax, upper) }
                minLimit = if (minLimit == null) rMin else maxOf(minLimit!!, rMin)
                maxLimit = if (maxLimit == null) rMax else minOf(maxLimit!!, rMax)
            }
        }
        if (minLimit != null && maxLimit != null && minLimit!! > maxLimit!!) minLimit = maxLimit
        return minLimit to maxLimit
    }

    /** 解析"请输入XX"强制文本。 */
    fun extractForcedTexts(title: String, extraFragments: List<String> = emptyList()): List<String> {
        val result = LinkedHashSet<String>()
        for (fragment in (listOf(title) + extraFragments).map { normalize(it) }.filter { it.isNotEmpty() }) {
            for (m in forceTextRe.findAll(fragment)) {
                val t = normalize(m.groupValues[1])
                if (t.isNotEmpty()) result.add(t)
            }
        }
        return result.toList()
    }
}

/** 极简四则运算求值（+ - * /，左结合，支持小数与一元正负），复刻 _safe_eval_arithmetic_expression。 */
private class ArithmeticEval(private val s: String) {
    private var pos = 0
    fun parse(): Double {
        val v = parseExpr()
        skipSpaces()
        if (pos != s.length) throw IllegalArgumentException("trailing")
        return v
    }
    private fun skipSpaces() { while (pos < s.length && s[pos] == ' ') pos++ }
    private fun parseExpr(): Double {
        var v = parseTerm()
        while (true) {
            skipSpaces()
            val c = peek() ?: break
            if (c == '+') { pos++; v += parseTerm() } else if (c == '-') { pos++; v -= parseTerm() } else break
        }
        return v
    }
    private fun parseTerm(): Double {
        var v = parseFactor()
        while (true) {
            skipSpaces()
            val c = peek() ?: break
            if (c == '*') { pos++; v *= parseFactor() } else if (c == '/') { pos++; val d = parseFactor(); if (kotlin.math.abs(d) < 1e-12) throw ArithmeticException("div0"); v /= d } else break
        }
        return v
    }
    private fun parseFactor(): Double {
        skipSpaces()
        val c = peek() ?: throw IllegalArgumentException("eof")
        if (c == '+') { pos++; return parseFactor() }
        if (c == '-') { pos++; return -parseFactor() }
        val start = pos
        while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
        if (pos == start) throw IllegalArgumentException("num")
        return s.substring(start, pos).toDouble()
    }
    private fun peek(): Char? = if (pos < s.length) s[pos] else null
}
