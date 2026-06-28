package com.surveycontroller.android.provider.wjx

import com.surveycontroller.android.core.model.AnswerAction
import com.surveycontroller.android.core.model.AttachedSelectChoice
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.provider.SubmitResult
import org.jsoup.parser.Parser
import java.util.Calendar

/**
 * 问卷星提交编码 / 签名 / 响应分类。
 * 1:1 复刻 wjx/provider/http_runtime.py 的纯算法部分，独立成无副作用函数以便单测。
 */
object WjxSubmitCodec {

    private const val DEFAULT_SCENE_ID = "q0hcfsca"
    private val SCENE_ID_PATTERNS = listOf(
        Regex("""["']?\bsceneId\b["']?\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""["']?\bscene_id\b["']?\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""\bdata-scene-id\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
    )

    // v4.0.5+ wjx 提交链路稳定性：转义提交文本中的特殊字符（复刻 _WJX_SPECIAL_CHAR_REPLACEMENTS）
    private val SPECIAL_CHAR_REPLACEMENTS = listOf(
        "$" to "ξ",
        "}" to "｝",
        "^" to "ˆ",
        "|" to "¦",
        "!" to "！",
        "<" to "＜",
    )

    /** v4.0.5+：复刻 _escape_wjx_submit_text。空白返回空串，否则按映射表逐字符替换。 */
    fun escapeSubmitText(value: Any?): String {
        var text = value?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return ""
        for ((source, target) in SPECIAL_CHAR_REPLACEMENTS) {
            text = text.replace(source, target)
        }
        return text
    }

    // ===== jqsign：唯一的"加密" =====
    /** jqsign = jqnonce 每字符与 t 做 XOR；t = ktimes%10，为 0 则取 1。 */
    fun buildJqsign(jqnonce: String, ktimes: Int): String {
        val t = if (ktimes % 10 == 0) 1 else ktimes % 10
        return buildString(jqnonce.length) {
            for (ch in jqnonce) append((ch.code xor t).toChar())
        }
    }

    // ===== 时间参数 =====
    /** start_seconds = max(1, now_seconds - ktimes)。 */
    fun resolveStartSeconds(currentMs: Long, ktimes: Int): Long {
        val currentSeconds = maxOf(1L, currentMs / 1000)
        val duration = maxOf(1, ktimes)
        return maxOf(1L, currentSeconds - duration)
    }

    /** starttime 格式 `yyyy/M/d H:m:s`，本地时区，无前导零。 */
    fun formatStartTime(startSeconds: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = startSeconds * 1000
        val y = cal.get(Calendar.YEAR)
        val mo = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val mi = cal.get(Calendar.MINUTE)
        val s = cal.get(Calendar.SECOND)
        return "$y/$mo/$d $h:$mi:$s"
    }

    fun extractSceneId(pageHtml: String?): String {
        val text = Parser.unescapeEntities(pageHtml.orEmpty(), false)
        if (text.isEmpty()) return DEFAULT_SCENE_ID
        for (pattern in SCENE_ID_PATTERNS) {
            val value = pattern.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (value.isNotEmpty()) return value
        }
        return DEFAULT_SCENE_ID
    }

    // ===== submitdata 编码 =====
    /**
     * 选中项格式化：index+1，多个用 `|`。
     * v4.0.5+：选项填充分隔符 ! → ^（! 留给矩阵 row!col），并对 fill/attached 调用 escapeSubmitText。
     */
    fun formatSelectedIndices(
        indices: List<Int>,
        optionFillTexts: List<Pair<Int, String>> = emptyList(),
        attachedSelectChoices: List<AttachedSelectChoice> = emptyList(),
    ): String {
        val fills = optionFillTexts
            .mapNotNull { (i, v) -> escapeSubmitText(v).takeIf { it.isNotEmpty() }?.let { i to it } }
            .toMap()
        val attached = attachedSelectChoices
            .mapNotNull { choice ->
                val raw = choice.value.trim().ifEmpty { (choice.selectedIndex + 1).toString() }
                escapeSubmitText(raw).takeIf { it.isNotEmpty() }?.let { choice.optionIndex to it }
            }
            .toMap()
        return indices.joinToString("|") { index ->
            val parts = mutableListOf((index + 1).toString())
            fills[index]?.let { parts.add(it) }
            attached[index]?.let { parts.add(it) }
            parts.joinToString("^")
        }
    }

    /** 单题 answer 编码（不含题号前缀）。复刻 _submitdata_answer。 */
    fun submitdataAnswer(action: AnswerAction): String = when (action.kind) {
        "choice", "select" -> formatSelectedIndices(action.selectedIndices, action.optionFillTexts, action.attachedSelectChoices)
        "text" -> {
            val sep = if (action.textValues.size > 1) "^" else ""
            action.textValues.joinToString(sep) { escapeSubmitText(it) }
        }
        "matrix" -> action.matrixIndices.mapIndexed { row, col -> "${row + 1}!${col + 1}" }.joinToString(",")
        "slider" -> action.sliderValue?.let { formatSliderValue(it) } ?: ""
        "order" -> action.selectedIndices.joinToString(",") { (it + 1).toString() }
        else -> ""
    }

    private fun formatSliderValue(value: Double): String = value.toString()

    /** 跳过题占位。复刻 _skipped_submitdata_answer。 */
    fun skippedAnswer(question: SurveyQuestionMeta): String {
        val typeCode = question.typeCode.trim()
        val optionCount = maxOf(1, question.options)
        val rows = maxOf(1, question.rows)
        return when (typeCode) {
            "3", "4", "5", "7" -> "-3"
            "11" -> List(optionCount) { "-3" }.joinToString(",")
            "6" -> (0 until rows).joinToString(",") { "${it + 1}!-3" }
            "1", "2", "8", "9", "33", "34" -> "(跳过)"
            else -> "-3"
        }
    }

    /**
     * 组装完整 submitdata。题间 `}`，每题 `{num}${answer}`。
     * 提交前中文逗号 → 英文逗号。无任何题抛错。
     */
    fun buildSubmitData(
        actions: List<AnswerAction>,
        questions: List<SurveyQuestionMeta>,
        skippedQuestionNums: List<Int> = emptyList(),
    ): String {
        val actionByNum = actions.filter { it.questionNum > 0 }.associateBy { it.questionNum }
        val questionByNum = questions.filter { it.num > 0 }.associateBy { it.num }
        val skipped = skippedQuestionNums.filter { it > 0 }.toSet()
        val orderedNums = (actionByNum.keys + skipped).sorted()
        val parts = mutableListOf<String>()
        for (num in orderedNums) {
            val action = actionByNum[num]
            var answer = if (action != null) {
                validateAction(action, questionByNum[num])
                submitdataAnswer(action)
            } else {
                questionByNum[num]?.let { skippedAnswer(it) } ?: "-3"
            }
            if (num <= 0 || answer.isEmpty()) continue
            answer = answer.replace('，', ',')
            parts.add("$num\$$answer")
        }
        if (parts.isEmpty()) error("问卷星没有生成可提交答案")
        return parts.joinToString("}")
    }

    private fun validateAction(action: AnswerAction, question: SurveyQuestionMeta?) {
        when (action.kind) {
            "choice", "select" -> {
                if (action.selectedIndices.isEmpty()) error("问卷星第${action.questionNum}题没有生成选项答案")
                val optionCount = resolveOptionCount(question)
                action.selectedIndices.forEach { index ->
                    if (index < 0) error("问卷星第${action.questionNum}题选项索引越界")
                    if (optionCount != null && index >= optionCount) {
                        error("问卷星第${action.questionNum}题第${index + 1}个选项越界")
                    }
                }
                if (question?.typeCode?.trim() in setOf("3", "5", "7") && action.selectedIndices.distinct().size != 1) {
                    error("问卷星第${action.questionNum}题单选/量表/下拉只能提交一个选项")
                }
            }
            "matrix" -> {
                if (action.matrixIndices.isEmpty()) error("问卷星第${action.questionNum}题没有生成矩阵答案")
                val expectedRows = question?.rows?.takeIf { it > 0 }
                if (expectedRows != null && action.matrixIndices.size < expectedRows) {
                    error("问卷星第${action.questionNum}题矩阵答案行数不足")
                }
                val optionCount = resolveOptionCount(question)
                action.matrixIndices.forEachIndexed { row, col ->
                    if (col < 0) error("问卷星第${action.questionNum}题第${row + 1}行没有生成矩阵答案")
                    if (optionCount != null && col >= optionCount) {
                        error("问卷星第${action.questionNum}题第${row + 1}行矩阵列越界")
                    }
                }
            }
            "slider" -> {
                val value = action.sliderValue ?: error("问卷星第${action.questionNum}题没有生成滑块答案")
                question?.sliderMin?.let { min ->
                    if (value < min) error("问卷星第${action.questionNum}题滑块值低于最小值")
                }
                question?.sliderMax?.let { max ->
                    if (value > max) error("问卷星第${action.questionNum}题滑块值高于最大值")
                }
            }
            "order" -> {
                if (action.selectedIndices.isEmpty()) error("问卷星第${action.questionNum}题没有生成排序答案")
                val optionCount = resolveOptionCount(question)
                action.selectedIndices.forEach { index ->
                    if (index < 0) error("问卷星第${action.questionNum}题排序选项越界")
                    if (optionCount != null && index >= optionCount) {
                        error("问卷星第${action.questionNum}题第${index + 1}个排序选项越界")
                    }
                }
                if (action.selectedIndices.distinct().size != action.selectedIndices.size) {
                    error("问卷星第${action.questionNum}题排序答案包含重复选项")
                }
            }
            "text" -> {
                if (action.textValues.all { it.isBlank() }) error("问卷星第${action.questionNum}题没有生成填空答案")
            }
        }
    }

    private fun resolveOptionCount(question: SurveyQuestionMeta?): Int? {
        val count = maxOf(question?.optionTexts?.size ?: 0, question?.options ?: 0)
        return count.takeIf { it > 0 }
    }

    // ===== 提交域名 =====
    fun submitDomain(url: String): String {
        val host = parseUri(url)?.host?.lowercase().orEmpty()
        return if (host.contains("ks.wjx.com")) "ks.wjx.com" else "v.wjx.cn"
    }

    /** shortid：path 末段去掉 .aspx。 */
    fun shortidFromUrl(url: String): String {
        val path = parseUri(url)?.path.orEmpty().trimEnd('/')
        val lastSegment = path.substringAfterLast("/", missingDelimiterValue = path)
        val shortid = lastSegment.replace(Regex("""(?i)\.aspx$"""), "").trim()
        if (shortid.isEmpty()) error("问卷星链接缺少 shortid")
        return shortid
    }

    private fun parseUri(url: String): java.net.URI? {
        val text = url.trim()
        if (text.isEmpty()) return null
        val candidate = if (text.contains("://")) text else "https://$text"
        return try {
            java.net.URI(candidate)
        } catch (e: Exception) {
            null
        }
    }

    // ===== 响应分类 =====
    private val ERROR_RE = Regex("""^\s*(\d+)〒(\d+)〒(.+)$""")

    fun classifyResponse(body: String): SubmitResult {
        val text = body.trim()
        if (text.contains("需要安全校验，请重新提交") || text.contains("请输入验证码")) {
            return SubmitResult.Verification
        }
        ERROR_RE.find(text)?.let { m ->
            val questionNum = m.groupValues[2].toIntOrNull()
            val reason = m.groupValues[3].trim()
            return SubmitResult.Rejected(questionNum, reason)
        }
        val lower = text.lowercase()
        val hasNegative = listOf("抱歉", "不符合", "错误", "重新提交").any { text.contains(it) }
        val looksSuccess = (lower.contains("complete.aspx") || lower.contains("success") ||
            text.startsWith("10") || text == "1" || lower == "ok")
        return if (looksSuccess && !hasNegative) {
            SubmitResult.Success
        } else {
            SubmitResult.Failure(text.take(200))
        }
    }
}
