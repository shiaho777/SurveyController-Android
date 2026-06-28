package com.surveycontroller.android.provider

import java.net.URI

/** 支持的问卷平台。对应 Python 端 SURVEY_PROVIDER_*。 */
enum class SurveyProviderType(val id: String) {
    WJX("wjx"),
    QQ("qq"),
    CREDAMO("credamo"),
    ;

    companion object {
        private val WJX_ALLOWED_HOSTS = listOf("wjx.top", "wjx.cn", "wjx.com")
        private val QQ_HOST = "wj.qq.com"
        private val QQ_PATH = Regex("^/s\\d+/\\d+/[A-Za-z0-9_-]+/?$", RegexOption.IGNORE_CASE)
        private val CREDAMO_HOSTS = listOf("credamo.com", "credamo.cn")
        private val CREDAMO_ANSWER_PATH = Regex("^/answer\\.html", RegexOption.IGNORE_CASE)
        private val CREDAMO_SHORT_PATH = Regex("^/s/[A-Za-z0-9_-]+/?$", RegexOption.IGNORE_CASE)

        fun fromId(value: String?, default: SurveyProviderType = WJX): SurveyProviderType =
            entries.firstOrNull { it.id == value?.trim()?.lowercase() } ?: default

        private data class ParsedUrl(
            val scheme: String,
            val host: String,
            val port: Int,
            val path: String,
            val query: String?,
            val fragment: String?,
        )

        private fun parseUrl(url: String?): ParsedUrl? {
            val text = url?.trim().orEmpty()
            if (text.isEmpty()) return null
            val candidate = if (text.contains("://")) text else "https://$text"
            return try {
                val uri = URI(candidate)
                ParsedUrl(
                    scheme = (uri.scheme ?: "https").lowercase(),
                    host = uri.host?.lowercase().orEmpty(),
                    port = uri.port,
                    path = uri.path.orEmpty(),
                    query = uri.rawQuery,
                    fragment = uri.rawFragment,
                )
            } catch (e: Exception) {
                null
            }
        }

        private fun hostMatches(host: String, domains: List<String>): Boolean =
            domains.any { host == it || host.endsWith(".$it") }

        fun isWjxDomain(url: String?): Boolean {
            val host = parseUrl(url)?.host.orEmpty()
            return host.isNotEmpty() && hostMatches(host, WJX_ALLOWED_HOSTS)
        }

        fun isQqSurveyUrl(url: String?): Boolean {
            val parsed = parseUrl(url) ?: return false
            val (host, path) = parsed.host to parsed.path
            return host == QQ_HOST && QQ_PATH.containsMatchIn(path)
        }

        fun isCredamoSurveyUrl(url: String?): Boolean {
            val parsed = parseUrl(url) ?: return false
            val (host, path) = parsed.host to parsed.path
            if (host.isEmpty() || !hostMatches(host, CREDAMO_HOSTS)) return false
            return CREDAMO_ANSWER_PATH.containsMatchIn(path) || CREDAMO_SHORT_PATH.containsMatchIn(path)
        }

        /** 1:1 复刻 detect_survey_provider 的优先级：credamo → qq → wjx。 */
        fun detect(url: String?, default: SurveyProviderType = WJX): SurveyProviderType = when {
            isCredamoSurveyUrl(url) -> CREDAMO
            isQqSurveyUrl(url) -> QQ
            isWjxDomain(url) -> WJX
            else -> default
        }

        fun isSupportedUrl(url: String?): Boolean =
            isCredamoSurveyUrl(url) || isQqSurveyUrl(url) || isWjxDomain(url)

        /** 复刻 normalize_survey_parse_url：归一化 + credamo 短链 /s/ → /answer.html。 */
        fun normalizeParseUrl(url: String?): String {
            val text = url?.trim().orEmpty()
            if (text.isEmpty()) return ""
            val candidate = if (text.contains("://")) text else "https://$text"
            val parsed = parseUrl(candidate) ?: return text
            val port = if (parsed.port >= 0) ":${parsed.port}" else ""
            val path = parsed.path
            val query = parsed.query
            val fragment = parsed.fragment
            val builder = StringBuilder("${parsed.scheme}://${parsed.host}$port")
            if (detect(candidate, default = QQ) != CREDAMO) {
                builder.append(path)
                if (!query.isNullOrEmpty()) builder.append("?").append(query)
                if (!fragment.isNullOrEmpty()) builder.append("#").append(fragment)
                return builder.toString()
            }
            // credamo 短链：/s/xxx 无 fragment → 改写为 /answer.html 并把原 path 作为 fragment
            if (path.lowercase().startsWith("/s/") && fragment.isNullOrEmpty()) {
                builder.append("/answer.html")
                if (!query.isNullOrEmpty()) builder.append("?").append(query)
                builder.append("#").append(path)
                return builder.toString()
            }
            builder.append(path)
            if (!query.isNullOrEmpty()) builder.append("?").append(query)
            if (!fragment.isNullOrEmpty()) builder.append("#").append(fragment)
            return builder.toString()
        }
    }
}
