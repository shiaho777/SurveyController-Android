package com.surveycontroller.android.provider.tencent

import com.surveycontroller.android.core.model.QuestionMedia
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.network.HttpClient
import com.surveycontroller.android.core.network.UserAgents
import com.surveycontroller.android.provider.ProviderAvailability
import org.json.JSONArray
import org.json.JSONObject

/**
 * 腾讯问卷 API 封装与题目标准化。复刻 tencent/provider/parser.py。
 */
object TencentApi {
    private val urlRe = Regex("""/s\d+/(\d+)/([A-Za-z0-9_-]+)/?$""", RegexOption.IGNORE_CASE)
    private val fillBlankTokenRe = Regex("""\{fillblank-[^{}]+\}""", RegexOption.IGNORE_CASE)
    private val fillBlankSuffixRe = Regex("""\s*[_＿]*\s*\{fillblank-[^{}]+\}""", RegexOption.IGNORE_CASE)
    private val markdownImageRe = Regex("""!\[[^]]*]\(([^)\s]+)\)(?:\{[^}]*\})?""", RegexOption.IGNORE_CASE)
    private val htmlImageSrcRe = Regex("""(?i)<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""")
    private val directImageUrlRe = Regex("""(?i)(?:https?:)?//[^\s"'<>)]*\.(?:png|jpe?g|webp|gif|bmp)(?:\?[^\s"'<>)]*)?""")
    private val textLikeTypes = setOf("text", "textarea", "number")
    val LOCALES = listOf("zhs", "zht", "zh", "en")

    val providerTypeToInternal = mapOf(
        "radio" to "3", "checkbox" to "4", "select" to "7",
        "text" to "1", "textarea" to "1", "number" to "1", "nps" to "5", "star" to "5",
        "matrix_radio" to "6", "matrix_star" to "6",
    )
    val supportedTypes = setOf(
        "radio", "checkbox", "select", "text", "textarea", "number",
        "nps", "star", "matrix_radio", "matrix_star",
    )
    fun extractIdentifiers(url: String): Pair<String, String> {
        val m = urlRe.find(url.trim()) ?: error("腾讯问卷链接格式无效，请确认链接完整且公开可访问")
        return m.groupValues[1] to m.groupValues[2]
    }

    fun pageUrl(surveyId: String, hash: String) = "https://wj.qq.com/s2/$surveyId/$hash/"

    fun apiHeaders(pageUrl: String, userAgent: String? = null): Map<String, String> =
        UserAgents.DEFAULT_HEADERS.toMutableMap().apply {
            this["Accept"] = "application/json, text/plain, */*"
            this["Origin"] = "https://wj.qq.com"
            this["Referer"] = pageUrl
            this["User-Agent"] = UserAgents.resolve(userAgent)
        }

    suspend fun request(
        http: HttpClient,
        surveyId: String,
        endpoint: String,
        hash: String,
        headers: Map<String, String>,
        extraParams: Map<String, String> = emptyMap(),
        proxyAddress: String? = null,
    ): JSONObject {
        val url = "https://wj.qq.com/api/v2/respondent/surveys/$surveyId/$endpoint"
        val params = mutableMapOf(
            "_" to System.currentTimeMillis().toString(),
            "hash" to hash,
        ).apply { putAll(extraParams) }
        val resp = http.get(buildUrl(url, params), headers = headers, timeoutSeconds = 15, proxyAddress = proxyAddress)
        val payload = JSONObject(resp.body.ifBlank { "{}" })
        return payload
    }

    private fun buildUrl(base: String, params: Map<String, String>): String {
        val query = params.entries.joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        return "$base?$query"
    }

    fun ensureOk(payload: JSONObject, endpoint: String): JSONObject {
        val code = payload.optString("code").uppercase()
        if (code != "OK" && code != "0") {
            ProviderAvailability.unavailableMessage("腾讯问卷", payload)?.let { throw TencentUnavailableException(it) }
            error("腾讯问卷接口返回异常（$endpoint）：${payload.opt("code") ?: "unknown"}")
        }
        return payload.optJSONObject("data") ?: error("腾讯问卷接口缺少 data 对象：$endpoint")
    }

    /** 标准化题目列表（保留暂不支持题型，避免静默漏题），分配 1-based 题号。 */
    fun standardize(questionsJson: org.json.JSONArray): List<SurveyQuestionMeta> {
        val all = mutableListOf<SurveyQuestionMeta>()
        val pageMap = buildPageNumberMap(questionsJson)
        var num = 0
        for (i in 0 until questionsJson.length()) {
            val q = questionsJson.optJSONObject(i) ?: continue
            val providerType = q.optString("type").trim().lowercase()
            val isDescription = providerType == "description"
            if (!isDescription) num++
            val supported = providerType in supportedTypes
            val internal = if (isDescription) "0" else providerTypeToInternal[providerType] ?: "0"
            val optionTexts = buildOptionTexts(q, providerType)
            val rowTexts = buildRowTexts(q)
            val fillableOptions = buildFillableOptionIndices(q, providerType)
            val multiMin = if (providerType == "checkbox") optPositiveInt(q, "min_length") else null
            val multiMax = if (providerType == "checkbox") optPositiveInt(q, "max_length") else null
            val isRating = providerType in listOf("nps", "star")
            all.add(
                SurveyQuestionMeta(
                    num = if (isDescription) 0 else num,
                    displayNum = if (isDescription) null else num,
                    title = normalizeText(q.optString("title")),
                    description = normalizeText(q.optString("description")),
                    typeCode = internal,
                    page = pageMap[pageKey(q)] ?: 1,
                    provider = "qq",
                    providerQuestionId = q.optString("id").trim(),
                    providerPageId = q.optString("page_id").trim(),
                    providerType = providerType,
                    options = optionTexts.size.coerceAtLeast(if (rowTexts.isNotEmpty()) optionTexts.size else 0),
                    rows = rowTexts.size.coerceAtLeast(1),
                    optionTexts = optionTexts,
                    rowTexts = rowTexts,
                    fillableOptions = fillableOptions,
                    isRating = isRating,
                    textInputs = if (providerType in textLikeTypes) 1 else 0,
                    isTextLike = providerType in textLikeTypes,
                    isDescription = isDescription,
                    multiMinLimit = multiMin,
                    multiMaxLimit = multiMax,
                    required = truthy(q.opt("required")),
                    ratingMax = if (isRating) optionTexts.size else 0,
                    questionMedia = buildQuestionMedia(q, providerType),
                    unsupported = !isDescription && !supported,
                    unsupportedReason = when {
                        isDescription -> ""
                        supported -> ""
                        else -> "暂不支持腾讯题型：${providerType.ifBlank { "unknown" }}"
                    },
                ),
            )
        }
        return mergeSamePageDescriptions(all)
    }

    private fun buildOptionTexts(q: JSONObject, providerType: String): List<String> {
        if (providerType in listOf("nps", "star")) {
            val start = q.optInt("star_begin_num", 0)
            val count = q.optInt("star_num", 0).coerceAtLeast(0)
            return (0 until count).map { (start + it).toString() }
        }
        if (providerType == "matrix_star") {
            val count = q.optInt("star_num", 0).coerceAtLeast(0)
            return (0 until count).map { (it + 1).toString() }
        }
        val options = q.optJSONArray("options") ?: return emptyList()
        return (0 until options.length()).map {
            normalizeOptionText(options.optJSONObject(it)?.optString("text") ?: "")
        }
    }

    private fun buildRowTexts(q: JSONObject): List<String> {
        val rows = q.optJSONArray("sub_titles") ?: return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until rows.length()) {
            val t = normalizeText(rows.optJSONObject(i)?.optString("text") ?: "")
            if (t.isNotEmpty()) result.add(t)
        }
        return result
    }

    fun normalizeText(value: String?): String =
        value
            ?.let { markdownImageRe.replace(it, " ") }
            ?.replace(Regex("<[^>]+>"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()

    private fun normalizeOptionText(value: String?): String {
        val text = normalizeText(value)
        if (text.isEmpty()) return ""
        return normalizeText(fillBlankTokenRe.replace(fillBlankSuffixRe.replace(text, ""), ""))
    }

    private fun buildFillableOptionIndices(q: JSONObject, providerType: String): List<Int> {
        if (providerType !in setOf("radio", "checkbox", "select")) return emptyList()
        val options = q.optJSONArray("options") ?: return emptyList()
        val result = mutableListOf<Int>()
        for (idx in 0 until options.length()) {
            if (payloadContainsFillBlank(options.opt(idx))) result.add(idx)
        }
        return result
    }

    private fun payloadContainsFillBlank(value: Any?, depth: Int = 0): Boolean {
        if (depth > 4 || value == null || value == JSONObject.NULL) return false
        return when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key.contains("fillblank", ignoreCase = true)) return true
                    if (payloadContainsFillBlank(value.opt(key), depth + 1)) return true
                }
                false
            }
            is org.json.JSONArray -> {
                for (idx in 0 until value.length()) {
                    if (payloadContainsFillBlank(value.opt(idx), depth + 1)) return true
                }
                false
            }
            else -> fillBlankTokenRe.containsMatchIn(value.toString())
        }
    }

    private fun optPositiveInt(q: JSONObject, key: String): Int? {
        val raw = q.opt(key) ?: return null
        val value = when (raw) {
            is Number -> raw.toInt()
            else -> raw.toString().trim().toIntOrNull() ?: return null
        }
        return value.takeIf { it > 0 }
    }

    private fun buildQuestionMedia(q: JSONObject, providerType: String): List<QuestionMedia> {
        val media = mutableListOf<QuestionMedia>()
        val seen = HashSet<String>()

        fun append(item: QuestionMedia) {
            val url = normalizeMediaUrl(item.sourceUrl)
            if (url.isEmpty()) return
            val key = "${item.scope}\u0000${item.index ?: -1}\u0000$url"
            if (!seen.add(key)) return
            media.add(item.copy(sourceUrl = url))
        }

        fun add(scope: String, index: Int?, label: String, urls: List<String>) {
            for (rawUrl in urls) {
                val url = normalizeMediaUrl(rawUrl)
                if (url.isEmpty()) continue
                append(QuestionMedia(scope = scope, index = index, sourceUrl = url, label = label.trim()))
            }
        }

        rawQuestionMedia(q.opt("question_media")).forEach { append(it) }

        add(
            scope = "title",
            index = null,
            label = "题干图",
            urls = collectImageUrls(q.opt("title")) + collectImageUrls(q.opt("description")),
        )

        val options = q.optJSONArray("options")
        if (options != null) {
            val optionTexts = buildOptionTexts(q, providerType)
            for (idx in 0 until options.length()) {
                val label = optionTexts.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: "选项 ${idx + 1}"
                add("option", idx, label, collectImageUrls(options.opt(idx)))
            }
        }

        val rows = q.optJSONArray("sub_titles")
        if (rows != null) {
            val rowTexts = buildRowTexts(q)
            for (idx in 0 until rows.length()) {
                val label = rowTexts.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: "第 ${idx + 1} 行"
                add("row", idx, label, collectImageUrls(rows.opt(idx)))
            }
        }

        return media
    }

    private fun collectImageUrls(value: Any?, depth: Int = 0): List<String> {
        if (depth > 5 || value == null || value == JSONObject.NULL) return emptyList()
        return when (value) {
            is JSONObject -> {
                val result = mutableListOf<String>()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val item = value.opt(key)
                    if (key.trim().lowercase() in setOf("img", "image", "image_url", "img_url", "pic", "pic_url", "url", "src")) {
                        normalizeMediaUrl(item).takeIf { it.isNotEmpty() }?.let { result.add(it) }
                    }
                    result.addAll(collectImageUrls(item, depth + 1))
                }
                result
            }
            is JSONArray -> {
                val result = mutableListOf<String>()
                for (idx in 0 until value.length()) result.addAll(collectImageUrls(value.opt(idx), depth + 1))
                result
            }
            else -> collectImageUrlsFromText(value.toString())
        }
    }

    private fun collectImageUrlsFromText(value: String): List<String> {
        val text = value.trim()
        if (text.isEmpty()) return emptyList()
        val urls = mutableListOf<String>()
        markdownImageRe.findAll(text).mapNotNull { it.groupValues.getOrNull(1)?.trim() }.filter { it.isNotEmpty() }.forEach { urls.add(it) }
        htmlImageSrcRe.findAll(text).mapNotNull { it.groupValues.getOrNull(1)?.trim() }.filter { it.isNotEmpty() }.forEach { urls.add(it) }
        directImageUrlRe.findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }.forEach { urls.add(it) }
        if (urls.isEmpty()) {
            val normalized = normalizeMediaUrl(text)
            if (looksLikeImageUrl(normalized)) urls.add(normalized)
        }
        return urls.distinct()
    }

    private fun normalizeMediaUrl(raw: Any?): String {
        var text = raw?.toString()?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return ""
        markdownImageRe.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { text = it }
        htmlImageSrcRe.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { text = it }
        return if (text.startsWith("//")) "https:$text" else text
    }

    private fun looksLikeImageUrl(value: String): Boolean =
        Regex("""\.(png|jpe?g|webp|gif|bmp)(?:\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(value)

    private fun rawQuestionMedia(value: Any?): List<QuestionMedia> {
        if (value == null || value == JSONObject.NULL) return emptyList()
        return when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { rawQuestionMediaItem(value.opt(it)) }
            is JSONObject -> listOfNotNull(rawQuestionMediaItem(value))
            is String -> normalizeMediaUrl(value).takeIf { it.isNotEmpty() }?.let {
                listOf(QuestionMedia(scope = "title", sourceUrl = it, label = "题干图"))
            } ?: emptyList()
            else -> emptyList()
        }
    }

    private fun rawQuestionMediaItem(value: Any?): QuestionMedia? {
        if (value == null || value == JSONObject.NULL) return null
        if (value !is JSONObject) return rawQuestionMedia(value).firstOrNull()
        val kind = value.optString("kind", "image").trim().lowercase().ifBlank { "image" }
        if (kind != "image") return null
        val scope = value.optString("scope", "title").trim().lowercase().ifBlank { "title" }
            .takeIf { it in setOf("title", "option", "row") } ?: "title"
        val source = firstMediaText(value, "source_url", "sourceUrl", "url", "src", "image_url", "img_url", "pic_url")
        if (source.isEmpty()) return null
        val index = if (scope == "title" || !value.has("index") || value.isNull("index")) null else value.optInt("index").takeIf { it >= 0 }
        return QuestionMedia(scope = scope, index = index, sourceUrl = source, label = value.optString("label", "").trim())
    }

    private fun firstMediaText(value: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val text = value.opt(key)?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && text != "null") return text
        }
        return ""
    }

    private fun truthy(value: Any?): Boolean {
        if (value == null || value == JSONObject.NULL) return false
        return when (value) {
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            else -> value.toString().trim().lowercase().let { it in setOf("true", "1", "yes", "y", "是") }
        }
    }

    private fun mergeSamePageDescriptions(items: List<SurveyQuestionMeta>): List<SurveyQuestionMeta> {
        val result = mutableListOf<SurveyQuestionMeta>()
        val pending = mutableListOf<SurveyQuestionMeta>()
        for (item in items) {
            if (item.isDescription) {
                pending.add(item)
                continue
            }
            val samePage = pending.filter { it.page == item.page }
            val merged = if (samePage.isEmpty()) {
                item
            } else {
                val titlePrefix = samePage.map { it.title.trim() }.filter { it.isNotEmpty() }
                val descriptionParts = samePage.map { it.description.trim() }.filter { it.isNotEmpty() } +
                    listOf(item.description.trim()).filter { it.isNotEmpty() }
                item.copy(
                    title = (titlePrefix + item.title.trim()).filter { it.isNotEmpty() }.joinToString(" "),
                    description = descriptionParts.joinToString("\n"),
                    questionMedia = mergeQuestionMedia(
                        samePage.flatMap { it.questionMedia },
                        item.questionMedia,
                    ),
                )
            }
            result.add(merged.copy(displayNum = result.size + 1))
            pending.clear()
        }
        return result
    }

    private fun buildPageNumberMap(questionsJson: org.json.JSONArray): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        for (i in 0 until questionsJson.length()) {
            val q = questionsJson.optJSONObject(i) ?: continue
            val key = pageKey(q)
            if (!result.containsKey(key)) result[key] = result.size + 1
        }
        return result
    }

    private fun pageKey(q: JSONObject): String =
        "${q.optString("page_id").trim()}\u0000${q.optString("page").trim()}"

    private fun mergeQuestionMedia(vararg groups: List<QuestionMedia>): List<QuestionMedia> {
        val merged = mutableListOf<QuestionMedia>()
        val seen = HashSet<String>()
        for (group in groups) {
            for (item in group) {
                val key = "${item.scope}\u0000${item.index ?: -1}\u0000${item.sourceUrl}"
                if (seen.add(key)) merged.add(item)
            }
        }
        return merged
    }
}

class TencentUnavailableException(message: String) : RuntimeException(message)
