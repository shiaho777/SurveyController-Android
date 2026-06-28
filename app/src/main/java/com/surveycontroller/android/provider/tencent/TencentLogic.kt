package com.surveycontroller.android.provider.tencent

import com.surveycontroller.android.core.model.DisplayCondition
import com.surveycontroller.android.core.model.DisplayTarget
import com.surveycontroller.android.core.model.JumpRule
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import org.json.JSONArray
import org.json.JSONObject

/**
 * 腾讯问卷跳题/显隐逻辑解析。1:1 复刻 tencent/provider/parser.py:_attach_qq_logic_metadata。
 * 从原始题目的 goto/display/refer 字段解析出跳转与显隐条件，并回填到标准化题目。
 */
object TencentLogic {
    private val questionIdRe = Regex("\\bq-[A-Za-z0-9_-]+\\b", RegexOption.IGNORE_CASE)
    private val pageIdRe = Regex("\\bp-[A-Za-z0-9_-]+\\b", RegexOption.IGNORE_CASE)
    private val endTokens = listOf("submit", "finish", "complete", "end", "结束", "提交", "完成")

    private fun collectRefs(value: Any?, pattern: Regex, depth: Int = 0): List<String> {
        if (depth > 5 || value == null) return emptyList()
        return when (value) {
            is JSONObject -> value.keys().asSequence().flatMap { k ->
                collectRefs(k, pattern, depth + 1) + collectRefs(value.opt(k), pattern, depth + 1)
            }.toList()
            is JSONArray -> (0 until value.length()).flatMap { collectRefs(value.opt(it), pattern, depth + 1) }
            else -> pattern.findAll(value.toString()).map { it.value.trim() }.toList()
        }.filter { it.isNotEmpty() }.distinct()
    }

    private fun isEmptyish(v: Any?): Boolean =
        v == null || v == JSONObject.NULL || v.toString().isBlank() || v.toString() == "[]" || v.toString() == "{}" || v == false

    private fun resolveJumpTarget(
        rawTarget: Any?,
        numByProviderId: Map<String, Int>,
        firstNumByPageId: Map<String, Int>,
        maxNum: Int,
    ): Int? {
        if (isEmptyish(rawTarget)) return null
        (rawTarget.toString().toIntOrNull())?.let { if (it > 0) return it }
        for (qid in collectRefs(rawTarget, questionIdRe)) numByProviderId[qid]?.let { return it }
        for (pid in collectRefs(rawTarget, pageIdRe)) firstNumByPageId[pid]?.let { return it }
        val lowered = rawTarget.toString().trim().lowercase()
        if (lowered.isNotEmpty() && endTokens.any { it in lowered }) return maxNum + 1
        return null
    }

    fun attach(metas: List<SurveyQuestionMeta>, rawQuestions: JSONArray): List<SurveyQuestionMeta> {
        if (metas.isEmpty()) return metas
        val numByProviderId = HashMap<String, Int>()
        val firstNumByPageId = HashMap<String, Int>()
        var maxNum = 0
        for (m in metas) {
            if (m.providerQuestionId.isEmpty() || m.num <= 0) continue
            numByProviderId[m.providerQuestionId] = m.num
            maxNum = maxOf(maxNum, m.num)
            if (m.providerPageId.isNotEmpty()) firstNumByPageId.putIfAbsent(m.providerPageId, m.num)
        }

        val jumpByNum = HashMap<Int, MutableList<JumpRule>>()
        val displayByNum = HashMap<Int, MutableList<DisplayCondition>>()
        val targetsByNum = HashMap<Int, MutableList<DisplayTarget>>()
        val hasJumpByNum = HashSet<Int>()
        val hasDisplayByNum = HashSet<Int>()
        val hasDependentDisplayByNum = HashSet<Int>()
        val exactParsedByNum = HashSet<Int>()

        for (i in 0 until rawQuestions.length()) {
            val rawQ = rawQuestions.optJSONObject(i) ?: continue
            val qid = rawQ.optString("id").trim()
            val num = numByProviderId[qid] ?: continue
            val options = rawQ.optJSONArray("options") ?: JSONArray()

            // 题级跳转
            val rawGoto = rawQ.opt("goto")
            val questionJumpTarget = resolveJumpTarget(rawGoto, numByProviderId, firstNumByPageId, maxNum)
            if (questionJumpTarget != null) {
                jumpByNum.getOrPut(num) { mutableListOf() }.add(JumpRule(optionIndex = -1, targetQuestion = questionJumpTarget))
                hasJumpByNum.add(num)
                exactParsedByNum.add(num)
            } else if (!isEmptyish(rawGoto)) {
                hasJumpByNum.add(num)
            }

            for (oi in 0 until options.length()) {
                val opt = options.optJSONObject(oi) ?: continue
                val optionGoto = opt.opt("goto")
                val optionJumpTarget = resolveJumpTarget(optionGoto, numByProviderId, firstNumByPageId, maxNum)
                if (optionJumpTarget != null) {
                    jumpByNum.getOrPut(num) { mutableListOf() }.add(
                        JumpRule(optionIndex = oi, targetQuestion = optionJumpTarget, optionText = TencentApi.normalizeText(opt.optString("text"))),
                    )
                    hasJumpByNum.add(num)
                    exactParsedByNum.add(num)
                } else if (!isEmptyish(optionGoto)) {
                    hasJumpByNum.add(num)
                }
                val display = opt.opt("display")
                if (!isEmptyish(display)) {
                    hasDependentDisplayByNum.add(num)
                    for (targetQid in collectRefs(display, questionIdRe)) {
                        val targetNum = numByProviderId[targetQid] ?: continue
                        displayByNum.getOrPut(targetNum) { mutableListOf() }.add(
                            DisplayCondition(conditionQuestionNum = num, conditionMode = "selected", conditionOptionIndices = listOf(oi)),
                        )
                        hasDisplayByNum.add(targetNum)
                        targetsByNum.getOrPut(num) { mutableListOf() }.add(
                            DisplayTarget(targetQuestionNum = targetNum, conditionMode = "selected", conditionOptionIndices = listOf(oi)),
                        )
                        exactParsedByNum.add(num)
                        exactParsedByNum.add(targetNum)
                    }
                }
            }

            // refer 兜底：本题依赖来源题显示
            val referIds = collectRefs(rawQ.opt("refer"), questionIdRe)
            if (referIds.isNotEmpty()) {
                hasDisplayByNum.add(num)
            }
            if (referIds.isNotEmpty() && !displayByNum.containsKey(num)) {
                for (referId in referIds) {
                    val srcNum = numByProviderId[referId] ?: continue
                    displayByNum.getOrPut(num) { mutableListOf() }.add(
                        DisplayCondition(conditionQuestionNum = srcNum, conditionMode = "selected", conditionOptionIndices = emptyList()),
                    )
                    targetsByNum.getOrPut(srcNum) { mutableListOf() }.add(
                        DisplayTarget(targetQuestionNum = num, conditionMode = "selected", conditionOptionIndices = emptyList()),
                    )
                    hasDependentDisplayByNum.add(srcNum)
                }
            }

            if (!isEmptyish(rawQ.opt("hidden"))) {
                hasDisplayByNum.add(num)
            }
        }

        return metas.map { m ->
            val jumps = jumpByNum[m.num]?.distinct().orEmpty()
            val displays = displayByNum[m.num]?.distinct().orEmpty()
            val targets = targetsByNum[m.num]?.distinct().orEmpty()
            val hasJump = m.num in hasJumpByNum || jumps.isNotEmpty()
            val hasDisplay = m.num in hasDisplayByNum || displays.isNotEmpty()
            val hasTargets = m.num in hasDependentDisplayByNum || targets.isNotEmpty()
            val hasAnyLogic = hasJump || hasDisplay || hasTargets
            val status = when {
                !hasAnyLogic -> m.logicParseStatus
                m.num in exactParsedByNum -> "complete"
                else -> "unknown"
            }
            m.copy(
                hasJump = hasJump,
                jumpRules = jumps,
                hasDisplayCondition = hasDisplay,
                displayConditions = displays,
                hasDependentDisplayLogic = hasTargets,
                controlsDisplayTargets = targets,
                logicParseStatus = status,
            )
        }
    }
}
