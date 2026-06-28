package com.surveycontroller.android.provider.credamo

import android.net.Uri
import com.surveycontroller.android.core.model.QuestionMedia
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.network.UserAgents
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random

/**
 * Credamo 见数 API、签名与题目标准化。复刻 credamo/provider/{http_runtime,parser}.py。
 */
object CredamoApi {
    private const val CIPHER = "P96D0A7D0M8C3R2D0M1"
    private const val RANDOM_CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
    private val htmlImageSrcRe = Regex("""(?i)<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""")
    private val directImageUrlRe = Regex("""(?i)(?:https?:)?//[^\s"'<>)]*\.(?:png|jpe?g|webp|gif|bmp)(?:\?[^\s"'<>)]*)?""")
    const val DEFAULT_ORIGIN = "https://www.credamo.com"
    const val RESOLUTION = "1920px*1080px"

    fun originFromUrl(url: String): String {
        val uri = try { Uri.parse(url.trim()) } catch (e: Exception) { null }
        val scheme = uri?.scheme
        val host = uri?.host
        return if (!scheme.isNullOrEmpty() && !host.isNullOrEmpty()) "$scheme://$host" else DEFAULT_ORIGIN
    }

    fun shortUrlFromUrl(url: String): String {
        val text = url.trim()
        val uri = try { Uri.parse(text) } catch (e: Exception) { null }
        val candidates = listOf(uri?.path.orEmpty(), uri?.fragment.orEmpty(), text)
        for (candidate in candidates) {
            val clean = candidate.trim().trimStart('#').substringBefore("?").trimEnd('/')
            if (clean.isEmpty()) continue
            val parts = clean.split("/").filter { it.isNotEmpty() }
            val sIndex = parts.indexOf("s")
            if (sIndex >= 0 && sIndex + 1 < parts.size) return parts[sIndex + 1].trim()
            if (Regex("[A-Za-z0-9_]+(?:ano)?").matches(clean)) return clean
        }
        error("见数链接缺少短链接编号")
    }

    /** 转换为免登录短链：以 _ 结尾→改 ano；已是 ano→保留；否则报错。 */
    fun noauthShortUrl(shortUrl: String): String {
        val s = shortUrl.trim().trimEnd('/')
        return when {
            s.endsWith("_") -> s.dropLast(1) + "ano"
            s.endsWith("ano") -> s
            else -> error("见数 HTTP 目前只支持免登录短链接")
        }
    }

    fun answerPageUrl(origin: String, shortUrl: String) = "${origin.trimEnd('/')}/answer.html#/s/$shortUrl"

    private fun sha1Upper(value: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun randomToken(length: Int): String =
        buildString { repeat(maxOf(1, length)) { append(RANDOM_CHARS.random()) } }

    fun newTimeCode(): String = UUID.randomUUID().toString().replace("-", "")

    /** 构造签名头：inner=SHA1(token+nonce+ts+union+cipher)，signature=SHA1(token+nonce+ts+inner+union+cipher)。 */
    fun signatureHeaders(answerToken: String = ""): Map<String, String> {
        val union = randomToken(10)
        val nonce = randomToken(16)
        val timestamp = System.currentTimeMillis().toString()
        val signature = computeSignature(answerToken, union, nonce, timestamp)
        return mapOf(
            "unionId" to union,
            "nonce" to nonce,
            "timestamp" to timestamp,
            "signature" to signature,
            "Accept-Language" to "zh-CN,zh;q=0.9",
        )
    }

    /** 纯函数：给定全部输入计算最终 signature，便于单测与 Python 交叉验证。 */
    fun computeSignature(token: String, union: String, nonce: String, timestamp: String): String {
        val inner = sha1Upper("$token$nonce$timestamp$union$CIPHER")
        return sha1Upper("$token$nonce$timestamp$inner$union$CIPHER")
    }

    fun requestHeaders(
        origin: String,
        shortUrl: String,
        userAgent: String? = null,
        answerToken: String = "",
        jsonBody: Boolean = false,
    ): Map<String, String> {
        val headers = UserAgents.DEFAULT_HEADERS.toMutableMap()
        headers["User-Agent"] = UserAgents.resolve(userAgent)
        headers["Accept"] = "application/json, text/plain, */*"
        headers["Referer"] = answerPageUrl(origin, shortUrl)
        headers.putAll(signatureHeaders(answerToken))
        if (jsonBody) {
            headers["Origin"] = origin.trimEnd('/')
            headers["Content-Type"] = "application/json"
        }
        return headers
    }

    fun classifyOk(payload: JSONObject): Boolean = payload.opt("success") != false

    // ===== 题目抽取与标准化 =====
    private fun asMappingList(value: Any?): List<JSONObject> {
        val arr = value as? JSONArray ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    fun iterRawQuestions(detail: JSONObject): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        asMappingList(detail.opt("questions")).let { if (it.isNotEmpty()) result.addAll(it) }
        for (block in asMappingList(detail.opt("blocks"))) {
            val elements = asMappingList(block.opt("blockElements")).ifEmpty { asMappingList(block.opt("elements")) }
            for (element in elements) {
                val candidates = listOf(
                    element.optJSONObject("question"),
                    element.optJSONObject("qst"),
                    element.optJSONObject("surveyQuestion"),
                    element,
                )
                for (c in candidates) {
                    if (c == null) continue
                    if (c.has("qstId") || c.has("questionId") || c.has("questionType")) {
                        result.add(c); break
                    }
                }
            }
        }
        return result
    }

    fun rawQuestionType(q: JSONObject): Int = q.optInt("questionType", 0)
    fun rawSelector(q: JSONObject): Int = q.optInt("selector", 0)

    fun rawProviderType(q: JSONObject): String {
        val t = rawQuestionType(q)
        val sel = rawSelector(q)
        return when {
            t == 2 && sel == 2 -> "multiple"
            t == 2 && sel == 3 -> "dropdown"
            t == 2 -> "single"
            t == 4 || t == 25 -> "matrix"
            t == 6 -> "order"
            t == 11 -> "scale"
            t == 1 -> "text"
            else -> t.toString()
        }
    }

    private fun providerTypeToCode(providerType: String): String = when (providerType) {
        "multiple" -> "4"; "dropdown" -> "7"; "single" -> "3"
        "matrix" -> "6"; "order" -> "11"; "scale" -> "5"; "text" -> "1"
        else -> "0"
    }

    fun rawOptionCount(q: JSONObject): Int = when (rawQuestionType(q)) {
        4, 25 -> asMappingList(q.opt("answers")).size
        1 -> 1
        else -> asMappingList(q.opt("choices")).size
    }

    fun rawRowCount(q: JSONObject): Int =
        if (rawQuestionType(q) == 4 || rawQuestionType(q) == 25) maxOf(1, asMappingList(q.opt("choices")).size) else 1

    private fun firstText(q: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val t = normalizeText(q.optString(k))
            if (t.isNotEmpty()) return t
        }
        return ""
    }

    private fun itemTexts(items: List<JSONObject>, vararg keys: String): List<String> =
        items.mapNotNull { item -> firstText(item, *keys).takeIf { it.isNotEmpty() } }

    /**
     * v4.0.3 credamo：复刻 parser.py:_payload_contains_choice_fill。
     * 递归检测载荷中是否存在含填空的选项。marker 集合：fill / blank / input / other
     * （宽集合，与腾讯的仅 fillblank 不同）。递归深度上限 4。
     */
    private fun payloadContainsChoiceFill(value: Any?, depth: Int = 0): Boolean {
        if (depth > 4 || value == null || value == JSONObject.NULL) return false
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val keyText = key.trim().lowercase()
                    val item = value.opt(key)
                    if ((keyText.contains("fill") || keyText.contains("blank") ||
                        keyText.contains("input") || keyText.contains("other")) &&
                        isFillMarkerValue(item)
                    ) {
                        return true
                    }
                    if (payloadContainsChoiceFill(item, depth + 1)) return true
                }
                return false
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    if (payloadContainsChoiceFill(value.opt(i), depth + 1)) return true
                }
                return false
            }
            else -> return false
        }
    }

    /** 对齐 Python `item not in (None, "", [], {}, False)` 的真值判定。 */
    private fun isFillMarkerValue(item: Any?): Boolean {
        if (item == null || item == JSONObject.NULL) return false
        return when (item) {
            is Boolean -> item
            is String -> item.trim().isNotEmpty()
            is JSONArray -> item.length() > 0
            is JSONObject -> item.length() > 0
            else -> true
        }
    }

    /**
     * v4.0.3 credamo：复刻 parser.py:_fillable_choice_indices。
     * 遍历 choices，返回含填空标记的选项索引（已按 optionCount 截断）。
     */
    private fun buildFillableOptionIndices(choices: List<JSONObject>, optionCount: Int): List<Int> {
        val fillable = mutableListOf<Int>()
        choices.forEachIndexed { index, choice ->
            if (optionCount > 0 && index >= optionCount) return@forEachIndexed
            if (payloadContainsChoiceFill(choice)) fillable.add(index)
        }
        return fillable
    }

    private fun rawQuestionNum(q: JSONObject, fallback: Int): Int {
        for (k in listOf("qstNo", "questionNo", "qstNum", "sortNo")) {
            Regex("\\d+").find(q.optString(k))?.let { return maxOf(1, it.value.toInt()) }
        }
        return maxOf(1, fallback)
    }

    private fun rawPage(q: JSONObject): Int {
        for (k in listOf("page", "pageNo")) {
            val value = q.opt(k) ?: continue
            val text = value.toString().trim()
            if (text.isEmpty() || text == "null") continue
            text.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
            Regex("\\d+").find(text)?.value?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        return 1
    }

    private fun rawPageId(q: JSONObject, page: Int): String {
        for (k in listOf("page", "pageNo")) {
            val text = q.opt(k)?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && text != "null") return text
        }
        return page.toString()
    }

    /** 标准化为 SurveyQuestionMeta，并返回 题号→原始JSON 映射。 */
    fun standardize(rawQuestions: List<JSONObject>): Pair<List<SurveyQuestionMeta>, Map<Int, JSONObject>> {
        val metas = mutableListOf<SurveyQuestionMeta>()
        val rawByNum = HashMap<Int, JSONObject>()
        rawQuestions.forEachIndexed { index, q ->
            val num = rawQuestionNum(q, index + 1)
            val providerType = rawProviderType(q)
            val questionType = rawQuestionType(q)
            val choices = asMappingList(q.opt("choices"))
            val answers = asMappingList(q.opt("answers"))
            val isMatrix = questionType == 4 || questionType == 25
            val supported = providerType in setOf("single", "multiple", "dropdown", "matrix", "order", "scale", "text")
            val optionItems = if (isMatrix) answers else choices
            var optionTexts = itemTexts(optionItems, "display", "answerContent", "choiceContent", "choiceTitle", "answerTitle", "content", "text", "title", "name")
            var rowTexts = if (isMatrix) itemTexts(choices, "display", "choiceContent", "choiceTitle", "content", "text", "title", "name") else emptyList()
            if (optionTexts.isEmpty()) optionTexts = (0 until rawOptionCount(q)).map { "选项 ${it + 1}" }
            if (isMatrix && rowTexts.isEmpty()) rowTexts = (0 until rawRowCount(q)).map { "第 ${it + 1} 行" }
            val title = firstText(q, "qstTitle", "qstName", "questionTitle", "questionName", "title", "name", "content", "display")
            val questionId = firstText(q, "questionId", "qstId", "id").ifEmpty { num.toString() }
            val textInputs = if (questionType == 1) 1 else 0
            val page = rawPage(q)
            val tip = firstText(q, "tip", "tips", "remark", "description")

            // 强制项 / 算术题 / 多选限制（1:1 复刻 parser.py）
            val extraFragments = listOf(tip)
            val forcedChoice = CredamoQuestionRules.extractForceSelectOption(title, optionTexts, extraFragments)
                ?: CredamoQuestionRules.extractArithmeticOption(title, optionTexts, extraFragments)
            val forcedTexts = CredamoQuestionRules.extractForcedTexts(title, extraFragments)
            val (multiMin, multiMax) = if (providerType == "multiple")
                CredamoQuestionRules.extractMultiSelectLimits(title, optionTexts.size, extraFragments) else (null to null)
            // v4.0.3 credamo：questionType==2（单选/多选/下拉）检测含填空的选项
            val fillableOptions = if (questionType == 2)
                buildFillableOptionIndices(choices, optionTexts.size) else emptyList()

            metas.add(
                SurveyQuestionMeta(
                    num = num,
                    displayNum = num,
                    title = title.ifEmpty { "Q$num" },
                    description = tip,
                    typeCode = providerTypeToCode(providerType),
                    page = page,
                    provider = "credamo",
                    providerQuestionId = questionId,
                    providerPageId = rawPageId(q, page),
                    providerType = providerType,
                    options = optionTexts.size,
                    rows = maxOf(1, rowTexts.size),
                    optionTexts = optionTexts,
                    rowTexts = rowTexts,
                    textInputs = textInputs,
                    isTextLike = providerType == "text",
                    fillableOptions = fillableOptions,
                    forcedOptionIndex = forcedChoice?.index,
                    forcedOptionText = forcedChoice?.text,
                    forcedTexts = forcedTexts,
                    multiMinLimit = multiMin,
                    multiMaxLimit = multiMax,
                    required = truthy(q.opt("required")) || truthy(q.opt("mustAnswer")),
                    isRating = providerType == "scale",
                    ratingMax = if (providerType == "scale") optionTexts.size.coerceAtLeast(1) else 0,
                    questionMedia = buildQuestionMedia(q, isMatrix, optionTexts, rowTexts),
                    unsupported = !supported,
                    unsupportedReason = if (supported) "" else "见数题型暂不支持纯 HTTP 提交：${questionType.takeIf { it > 0 } ?: "unknown"}",
                ),
            )
            rawByNum[num] = q
        }
        return metas to rawByNum
    }

    fun surveyTitle(detail: JSONObject): String {
        firstText(detail, "surveyTitle", "title", "name", "projectName").let { if (it.isNotEmpty()) return it }
        detail.optJSONObject("survey")?.let { s ->
            firstText(s, "surveyTitle", "title", "name").let { if (it.isNotEmpty()) return it }
        }
        return "Credamo 见数问卷"
    }

    fun normalizeText(value: String?): String =
        value?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

    private fun buildQuestionMedia(
        q: JSONObject,
        isMatrix: Boolean,
        optionTexts: List<String>,
        rowTexts: List<String>,
    ): List<QuestionMedia> {
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
            urls = collectImageUrls(q.opt("qstTitle")) +
                collectImageUrls(q.opt("qstName")) +
                collectImageUrls(q.opt("questionTitle")) +
                collectImageUrls(q.opt("questionName")) +
                collectImageUrls(q.opt("title")) +
                collectImageUrls(q.opt("description")) +
                collectImageUrls(q.opt("tip")) +
                collectImageUrls(q.opt("tips")) +
                collectImageUrls(q.opt("remark")),
        )

        val choices = asMappingList(q.opt("choices"))
        val answers = asMappingList(q.opt("answers"))
        val optionItems = if (isMatrix) answers else choices
        optionItems.forEachIndexed { idx, item ->
            val label = optionTexts.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: "选项 ${idx + 1}"
            add("option", idx, label, collectImageUrls(item))
        }
        if (isMatrix) {
            choices.forEachIndexed { idx, item ->
                val label = rowTexts.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: "第 ${idx + 1} 行"
                add("row", idx, label, collectImageUrls(item))
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

    /** id 归一化：纯数字转 Long，否则字符串。 */
    fun normalizeId(value: Any?): Any {
        val text = value?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return ""
        return text.toLongOrNull() ?: text
    }

    fun idFrom(item: JSONObject, vararg keys: String): Any {
        for (k in keys) {
            val v = item.opt(k)
            if (v != null && v.toString().trim().isNotEmpty()) return normalizeId(v)
        }
        return ""
    }
}
