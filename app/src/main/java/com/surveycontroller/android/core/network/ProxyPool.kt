package com.surveycontroller.android.core.network

import com.surveycontroller.android.core.engine.ProxyProvider
import com.surveycontroller.android.core.model.ExecutionConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义代理 API 的 IP 池。复刻 software/network/proxy 的自定义源解析逻辑。
 * 用户在配置里填写返回 ip:port 的 API 地址（可含 {area} 模板）。
 * 注：项目自带的"默认/福利"源依赖私有后端，移动端不可复刻，故仅支持自定义 API。
 */
class ProxyPool(
    private val http: HttpClient,
    private val apiUrl: String,
) : ProxyProvider {

    private val mutex = Mutex()
    private val available = ArrayDeque<String>()
    private val ipPortRe = Regex(
        "(?:https?://)?(?:([^\\s:@/,]+):([^\\s:@/,]+)@)?((?:\\d{1,3}\\.){3}\\d{1,3}):(\\d{2,5})",
    )

    override suspend fun acquire(config: ExecutionConfig): String? {
        mutex.withLock {
            if (available.isNotEmpty()) return available.removeFirst()
        }
        val fetched = fetchBatch(config, expected = maxOf(1, config.numThreads))
        mutex.withLock {
            available.addAll(fetched)
            return available.removeFirstOrNull()
        }
    }

    override fun markSuccess(proxyAddress: String) {
        // 成功的代理可复用一次（部分代理商按时计费，短时间内同 IP 可重复提交）
    }

    private suspend fun fetchBatch(config: ExecutionConfig, expected: Int): List<String> {
        val url = buildUrl(config)
        if (url.isBlank()) return emptyList()
        return try {
            val resp = http.get(url, headers = UserAgents.DEFAULT_HEADERS, timeoutSeconds = 10)
            val parsed = parsePayload(resp.body)
            warnCustomApiReturnedLargeBatch(parsed.size, expected)
            parsed
        } catch (e: Exception) {
            emptyList()
        }
    }

    // v4.0.3 / #89：自定义源 URL 原样透传，不再替换 {num}、不再追加 ?num=。{area} 替换保留。
    private fun buildUrl(config: ExecutionConfig): String {
        var url = apiUrl.trim()
        if (url.isEmpty()) return ""
        val area = config.proxyAreaCode?.trim().orEmpty()
        url = url.replace("{area}", area)
        return url
    }

    // v4.0.3 / #89：自定义源返回量 > 请求量×1.2 时告警。复刻 _warn_custom_api_returned_large_batch。
    private fun warnCustomApiReturnedLargeBatch(returned: Int, requested: Int) {
        val requestedNorm = maxOf(1, requested)
        val returnedNorm = maxOf(0, returned)
        if (returnedNorm <= (requestedNorm * 1.2).toInt()) return
        android.util.Log.w(
            "ProxyPool",
            "自定义代理API返回 $returnedNorm 个有效代理，当前运行本轮请求 $requestedNorm 个，将缓存到代理池并按任务并发逐个使用。可能会引发代理池余额浪费",
        )
    }

    private fun parsePayload(text: String): List<String> {
        val result = LinkedHashSet<String>()
        // 先尝试 JSON 递归提取
        try {
            recursiveFind(JSONObject(text), result)
        } catch (e: Exception) {
            try {
                recursiveFindArray(JSONArray(text), result)
            } catch (e2: Exception) {
                // 纯文本：逐行 ip:port
            }
        }
        if (result.isEmpty()) {
            for (m in ipPortRe.findAll(text)) result.add(formatMatch(m))
        }
        return result.toList()
    }

    private fun formatMatch(m: MatchResult): String {
        val (user, pwd, ip, port) = m.destructured
        return if (user.isNotEmpty() && pwd.isNotEmpty()) "$user:$pwd@$ip:$port" else "$ip:$port"
    }

    private fun recursiveFind(obj: JSONObject, out: MutableSet<String>, depth: Int = 0) {
        if (depth > 10) return
        val ip = listOf("ip", "IP", "host").firstNotNullOfOrNull { obj.optString(it).ifBlank { null } }
        val port = listOf("port", "Port", "PORT").firstNotNullOfOrNull { obj.optString(it).ifBlank { null } }
        if (ip != null && port != null) {
            val user = listOf("account", "username", "user").firstNotNullOfOrNull { obj.optString(it).ifBlank { null } }
            val pwd = listOf("password", "pwd", "pass").firstNotNullOfOrNull { obj.optString(it).ifBlank { null } }
            out.add(if (user != null && pwd != null) "$user:$pwd@$ip:$port" else "$ip:$port")
            return
        }
        for (key in obj.keys()) {
            when (val v = obj.opt(key)) {
                is JSONObject -> recursiveFind(v, out, depth + 1)
                is JSONArray -> recursiveFindArray(v, out, depth + 1)
                is String -> ipPortRe.find(v)?.let { out.add(formatMatch(it)) }
            }
        }
    }

    private fun recursiveFindArray(arr: JSONArray, out: MutableSet<String>, depth: Int = 0) {
        if (depth > 10) return
        for (i in 0 until arr.length()) {
            when (val v = arr.opt(i)) {
                is JSONObject -> recursiveFind(v, out, depth + 1)
                is JSONArray -> recursiveFindArray(v, out, depth + 1)
                is String -> ipPortRe.find(v)?.let { out.add(formatMatch(it)) }
            }
        }
    }
}
