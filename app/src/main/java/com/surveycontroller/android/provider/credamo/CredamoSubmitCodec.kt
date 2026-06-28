package com.surveycontroller.android.provider.credamo

import com.surveycontroller.android.core.model.AnswerAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * 见数提交答案编码的纯函数部分。
 */
object CredamoSubmitCodec {
    fun choices(raw: JSONObject): List<JSONObject> {
        val arr = raw.optJSONArray("choices") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    fun answers(raw: JSONObject): List<JSONObject> {
        val arr = raw.optJSONArray("answers") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    fun choicePayload(choice: JSONObject, optionIndex: Int, action: AnswerAction): JSONObject {
        val fill = action.optionFillTexts
            .firstOrNull { it.first == optionIndex }
            ?.second
            ?.trim()
            .orEmpty()
        return JSONObject()
            .put("choiceId", requiredIdFrom(choice, action.questionNum, "选项", "choiceId", "id"))
            .put("choiceContent", fill)
    }

    fun requiredIdFrom(item: JSONObject, questionNum: Int, label: String, vararg keys: String): Any {
        val value = CredamoApi.idFrom(item, *keys)
        if (value.toString().trim().isEmpty()) {
            error("见数第${questionNum}题${label}缺少 id")
        }
        return value
    }

    fun selectedItem(items: List<JSONObject>, index: Int, questionNum: Int, label: String): JSONObject {
        if (index < 0 || index >= items.size) error("见数第${questionNum}题${label}索引越界")
        return items[index]
    }

    fun choiceIndexByText(choices: List<JSONObject>, targetText: String?): Int? {
        val normalizedTarget = normalizeMatchText(targetText)
        if (normalizedTarget.isEmpty()) return null
        choices.forEachIndexed { index, choice ->
            for (key in listOf("display", "choiceContent", "choiceTitle", "content", "text")) {
                if (normalizeMatchText(choice.opt(key)) == normalizedTarget) return index
            }
        }
        return null
    }

    fun encodeChoice(raw: JSONObject, action: AnswerAction, qstId: Any, forcedOptionText: String? = null): JSONObject {
        val choices = choices(raw)
        val selector = CredamoApi.rawSelector(raw)
        val item = JSONObject().put("qstId", qstId).put("answerTime", 0).put("answerContent", "")
        if (selector == 2 || action.kind == "multiple") {
            val list = JSONArray()
            for (idx in action.selectedIndices) {
                val choice = selectedItem(choices, idx, action.questionNum, "选项")
                list.put(choicePayload(choice, idx, action))
            }
            if (list.length() == 0) error("见数第${action.questionNum}题没有生成选项答案")
            item.put("answerQstChoiceList", list)
        } else {
            val idx = choiceIndexByText(choices, forcedOptionText)
                ?: selectedChoiceIndex(action, "选项")
            val choice = selectedItem(choices, idx, action.questionNum, "选项")
            item.put("answerQstChoice", choicePayload(choice, idx, action))
        }
        val subSelector = raw.optInt("subSelector", 0)
        if (CredamoApi.rawQuestionType(raw) == 2 && subSelector > 0) {
            item.put("questionType", 2).put("subSelector", subSelector)
        }
        return item
    }

    fun selectedChoiceIndex(action: AnswerAction, label: String): Int =
        action.scalarValue ?: action.selectedIndices.firstOrNull()
            ?: error("见数第${action.questionNum}题没有生成${label}答案")

    private fun normalizeMatchText(value: Any?): String =
        value?.toString()?.filterNot { it.isWhitespace() }?.trim().orEmpty()
}
