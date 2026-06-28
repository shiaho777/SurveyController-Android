package com.surveycontroller.android.core.backend

import com.surveycontroller.android.core.network.HttpClient
import com.surveycontroller.android.core.network.UserAgents
import org.json.JSONArray
import org.json.JSONObject

/**
 * 项目私有后端（api-wjx.hungrym0.com）的客户端接口。1:1 对接：
 * software/network/proxy/session、software/integrations/ai/free_api、submission_report。
 * 所有端点可通过 BackendEndpoints 覆盖（默认指向官方后端）。
 * 域名 v4.0.4 起由 .top 切换为 .com，须同步升级否则内置随机 IP 不可达。
 */
object BackendEndpoints {
    @Volatile var base = "https://api-wjx.hungrym0.com"
    val authTrial get() = "$base/api/auth/trial"
    val bonus get() = "$base/api/bonus"
    val cardRedeem get() = "$base/api/cards/redeem"
    val ipExtract get() = "$base/api/ip/extract"
    val submissionReport get() = "$base/api/submission/report"
    val aiFree get() = "$base/api/ai/free"
    val status get() = "$base/api/status"
    val ipUsage get() = "$base/ipzan/usage"
}

class BackendException(val detail: String, val statusCode: Int = 0, val retryAfterSeconds: Int = 0) :
    RuntimeException(detail)

data class ProxyLease(
    val host: String,
    val port: Int,
    val account: String,
    val password: String,
    val expireAt: String = "",
    val provider: String = "",
) {
    /** OkHttp 代理地址：host:port（含鉴权时 user:pass@host:port）。 */
    fun address(): String = if (account.isNotEmpty() && password.isNotEmpty())
        "$account:$password@$host:$port" else "$host:$port"
}

class BackendClient(
    private val http: HttpClient,
    private val deviceIdentity: DeviceIdentity,
    private val sessionStore: RandomIpSessionStore,
) {
    private suspend fun headers(): Map<String, String> = UserAgents.DEFAULT_HEADERS.toMutableMap().apply {
        this["Content-Type"] = "application/json"
        this["X-Device-ID"] = deviceIdentity.get()
    }

    private suspend fun postJson(url: String, body: JSONObject, timeoutSeconds: Long = 10): Pair<Int, JSONObject> {
        val resp = http.postBody(url, body.toString(), headers = headers(), timeoutSeconds = timeoutSeconds)
        val json = try {
            JSONObject(resp.body.ifBlank { "{}" })
        } catch (e: Exception) {
            JSONObject()
        }
        return resp.statusCode to json
    }

    private fun errorFrom(status: Int, json: JSONObject): BackendException {
        val detail = json.optString("detail").trim().ifEmpty { "http_$status" }
        val retry = json.optInt("retry_after_seconds", 0)
        return BackendException(detail, status, retry)
    }

    private fun applyQuota(json: JSONObject, base: RandomIpSession): RandomIpSession {
        val remaining = json.optDouble("remaining_quota", base.remainingQuota)
        val total = json.optDouble("total_quota", base.totalQuota)
        val used = json.optDouble("used_quota", base.usedQuota)
        return base.copy(remainingQuota = remaining, totalQuota = total, usedQuota = used)
    }

    /** 领取免费试用，返回会话。复刻 activate_trial_async。 */
    suspend fun activateTrial(): RandomIpSession {
        val (status, json) = postJson(BackendEndpoints.authTrial, JSONObject())
        if (status != 200) throw errorFrom(status, json)
        val userId = json.optInt("user_id", 0)
        if (userId <= 0) throw BackendException("invalid_response:user_id_invalid", status)
        val session = applyQuota(json, RandomIpSession(userId = userId))
        sessionStore.save(session)
        return session
    }

    /** 同步额度（再次 POST trial）。 */
    suspend fun syncQuota(): RandomIpSession {
        val session = requireSession()
        val (status, json) = postJson(BackendEndpoints.authTrial, JSONObject())
        if (status != 200) throw errorFrom(status, json)
        val updated = applyQuota(json, session)
        sessionStore.save(updated)
        return updated
    }

    private suspend fun requireSession(): RandomIpSession {
        val s = sessionStore.load()
        if (!s.authenticated) throw BackendException("not_authenticated")
        return s
    }

    /** 提取代理 IP。复刻 extract_proxy_async：单个或批量。 */
    suspend fun extractProxies(
        minute: Int,
        pool: String,
        area: String?,
        num: Int = 1,
        upstream: String = "default",
    ): List<ProxyLease> {
        val session = requireSession()
        val body = JSONObject()
            .put("user_id", session.userId)
            .put("minute", minute)
            .put("pool", pool.trim())
        upstream.trim().lowercase().takeIf { it.isNotEmpty() }?.let { body.put("upstream", it) }
        val requestNum = maxOf(1, num)
        if (requestNum > 1) body.put("num", requestNum)
        area?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("area", it) }

        val timeout = minOf(60.0, 10.0 + (requestNum - 1) * 2.0).toLong()
        val (status, json) = postJson(BackendEndpoints.ipExtract, body, timeout)
        if (status != 200) throw errorFrom(status, json)

        val leases = mutableListOf<ProxyLease>()
        val items = json.optJSONArray("items")
        if (requestNum > 1 && items != null) {
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.let { parseLease(it)?.let(leases::add) }
            }
        } else {
            parseLease(json)?.let(leases::add)
        }
        // 更新额度
        sessionStore.save(applyQuota(json, session))
        if (leases.isEmpty()) throw BackendException("invalid_response")
        return leases
    }

    private fun parseLease(d: JSONObject): ProxyLease? {
        val host = d.optString("host").trim()
        val port = d.optInt("port", 0)
        val account = d.optString("account").trim()
        val password = d.optString("password").trim()
        if (host.isEmpty() || port <= 0 || account.isEmpty() || password.isEmpty()) return null
        return ProxyLease(host, port, account, password, d.optString("expire_at").trim(), d.optString("provider").trim().lowercase())
    }

    /** 领取彩蛋奖励。复刻 claim_easter_egg_bonus_async。 */
    suspend fun claimBonus(): RandomIpSession {
        val session = requireSession()
        val (status, json) = postJson(
            BackendEndpoints.bonus,
            JSONObject().put("user_id", session.userId).put("bonus_code", "fuck-you-hacker"),
        )
        if (status != 200) throw errorFrom(status, json)
        val updated = applyQuota(json, session)
        sessionStore.save(updated)
        return updated
    }

    /** 卡密兑换额度。复刻 redeem_card_async。 */
    suspend fun redeemCard(cardCode: String): RandomIpSession {
        val session = requireSession()
        val (status, json) = postJson(
            BackendEndpoints.cardRedeem,
            JSONObject().put("user_id", session.userId).put("card_code", cardCode.trim()),
        )
        if (status != 200) throw errorFrom(status, json)
        val updated = applyQuota(json, session)
        sessionStore.save(updated)
        return updated
    }

    /** 后端在线状态。复刻 get_status。 */
    suspend fun status(): Pair<Boolean, String> {
        val resp = http.get(BackendEndpoints.status, headers = headers(), timeoutSeconds = 5)
        val json = try { JSONObject(resp.body.ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
        val online = json.optBoolean("online", false)
        val message = json.optString("message").ifBlank { if (online) "系统正常运行中" else "系统当前不在线" }
        return online to message
    }

    /** 提交结果上报。复刻 report_submission_result_async。 */
    suspend fun reportSubmission(surveyUrl: String, result: String, proxyProvider: String, clientVersion: String): Boolean {
        val session = sessionStore.load()
        if (!session.authenticated) return false
        val body = JSONObject()
            .put("user_id", session.userId)
            .put("survey_url", surveyUrl.trim())
            .put("result", result.trim().lowercase())
            .put("proxy_provider", proxyProvider)
            .put("client_version", clientVersion)
        return try {
            val (status, _) = postJson(BackendEndpoints.submissionReport, body)
            status == 200
        } catch (e: Exception) {
            false
        }
    }

    /** 免费 AI 填空（单题）。复刻 call_free_ai_api_async。身份缺失自动领取试用。 */
    suspend fun freeAiAnswer(questionContent: String, questionType: String, blankCount: Int?, systemPrompt: String?): List<String> {
        var session = sessionStore.load()
        if (!session.authenticated) session = activateTrial()
        val body = JSONObject()
            .put("user_id", session.userId)
            .put("question_type", questionType)
            .put("question_content", questionContent)
        systemPrompt?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("system_prompt", it) }
        if (questionType == "multi_fill_blank") body.put("blank_count", blankCount ?: 0)
        val (status, json) = postJson(BackendEndpoints.aiFree, body, 60)
        if (status != 200) throw errorFrom(status, json)
        val raw = json.optJSONArray("answers") ?: throw BackendException("ai_empty_response", status)
        val answers = (0 until raw.length()).mapNotNull { raw.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
        if (answers.isEmpty()) throw BackendException("ai_empty_response", status)
        return answers
    }

    /** 免费 AI 批量填空项。 */
    data class FreeAiBatchItem(val itemId: String, val questionType: String, val content: String, val blankCount: Int?)

    /**
     * 免费 AI 批量填空：提交任务并轮询直至完成。复刻 free_api.py 的 batch/poll 链路。
     * 返回 itemId -> answers。失败项不返回。
     */
    suspend fun freeAiBatch(items: List<FreeAiBatchItem>, systemPrompt: String?): Map<String, List<String>> {
        if (items.isEmpty()) return emptyMap()
        var session = sessionStore.load()
        if (!session.authenticated) session = activateTrial()
        val completed = HashMap<String, List<String>>()
        // 单任务最多 64 题，分块提交
        items.chunked(64).forEach { chunk ->
            runCatching { submitAndPollBatch(session.userId, chunk, systemPrompt, completed) }
        }
        return completed
    }

    private suspend fun submitAndPollBatch(
        userId: Int,
        chunk: List<FreeAiBatchItem>,
        systemPrompt: String?,
        completed: HashMap<String, List<String>>,
    ) {
        val payloadItems = JSONArray()
        for (item in chunk) {
            val o = JSONObject().put("item_id", item.itemId).put("question_type", item.questionType).put("question_content", item.content)
            if (item.questionType == "multi_fill_blank") o.put("blank_count", item.blankCount ?: 0)
            payloadItems.put(o)
        }
        val body = JSONObject().put("user_id", userId).put("items", payloadItems)
        systemPrompt?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("system_prompt", it) }
        val (submitStatus, submitJson) = postJson("${BackendEndpoints.aiFree}/batch", body, 60)
        if (submitStatus != 200 && submitStatus != 202) throw errorFrom(submitStatus, submitJson)
        val taskId = submitJson.optString("task_id").trim()
        if (taskId.isEmpty()) throw BackendException("missing task_id", submitStatus)

        // 轮询直至终态（最多 ~45s）
        val deadline = System.currentTimeMillis() + 45_000
        var pollAfter = submitJson.optInt("poll_after_ms", 1000).coerceIn(200, 3000)
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(pollAfter.toLong())
            val resp = http.get("${BackendEndpoints.aiFree}/tasks/$taskId", headers = headers(), timeoutSeconds = 60)
            val json = try { JSONObject(resp.body.ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
            pollAfter = json.optInt("poll_after_ms", pollAfter).coerceIn(200, 3000)
            json.optJSONArray("items")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    if (it.optString("status").lowercase() == "completed") {
                        val a = it.optJSONArray("answers")
                        if (a != null) {
                            val list = (0 until a.length()).mapNotNull { k -> a.optString(k).trim().takeIf { s -> s.isNotEmpty() } }
                            if (list.isNotEmpty()) completed[it.optString("item_id")] = list
                        }
                    }
                }
            }
            val status = json.optString("status").lowercase()
            if (status in listOf("completed", "partial", "failed", "expired")) break
        }
    }

    suspend fun currentSession(): RandomIpSession = sessionStore.load()

    /** 每日 IP 提取记录 + IP 池剩余数量。复刻 io/reports/ip_usage_log.get_usage_summary。 */
    suspend fun ipUsageSummary(): IpUsageSummary {
        val resp = http.get(BackendEndpoints.ipUsage, headers = headers(), timeoutSeconds = 10)
        if (resp.statusCode != 200) throw BackendException("http_${resp.statusCode}", resp.statusCode)
        val body = resp.body.ifBlank { "{}" }
        val root: Any = try {
            val t = body.trimStart()
            if (t.startsWith("[")) JSONArray(body) else JSONObject(body)
        } catch (e: Exception) {
            JSONObject()
        }
        val records = extractRecords(root).map {
            IpUsageRecord(it.optString("label").trim(), toIntOrZero(it.opt("total")))
        }.filter { it.label.isNotEmpty() }
        return IpUsageSummary(records, extractRemainingIp(root))
    }

    private fun toIntOrZero(raw: Any?): Int =
        (raw as? Number)?.toInt() ?: raw?.toString()?.trim()?.toDoubleOrNull()?.toInt() ?: 0

    private fun toIntOrNull(raw: Any?): Int? =
        (raw as? Number)?.toInt() ?: raw?.toString()?.trim()?.toDoubleOrNull()?.toInt()

    /** 递归提取记录数组（对齐桌面端 _extract_records 的容错逻辑）。 */
    private fun extractRecords(payload: Any?): List<JSONObject> {
        when (payload) {
            is JSONArray -> {
                val direct = (0 until payload.length()).mapNotNull { payload.optJSONObject(it) }
                if (direct.isNotEmpty() && direct.all { it.has("label") || it.has("total") }) return direct
                direct.forEach { val nested = extractRecords(it); if (nested.isNotEmpty()) return nested }
                return emptyList()
            }
            is JSONObject -> {
                for (key in listOf("records", "history", "items", "list")) {
                    val v = payload.optJSONArray(key)
                    if (v != null) {
                        val list = (0 until v.length()).mapNotNull { v.optJSONObject(it) }
                        if (list.isNotEmpty()) return list
                    }
                }
                val keys = payload.keys()
                while (keys.hasNext()) {
                    val nested = extractRecords(payload.opt(keys.next()))
                    if (nested.isNotEmpty()) return nested
                }
                return emptyList()
            }
            else -> return emptyList()
        }
    }

    /** 递归提取 IP 池剩余数量。 */
    private fun extractRemainingIp(payload: Any?): Int? {
        when (payload) {
            is JSONObject -> {
                for (key in listOf("remaining_ip", "remainingIp", "ip_remaining", "remaining")) {
                    if (payload.has(key)) toIntOrNull(payload.opt(key))?.let { return maxOf(0, it) }
                }
                val keys = payload.keys()
                while (keys.hasNext()) {
                    extractRemainingIp(payload.opt(keys.next()))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until payload.length()) extractRemainingIp(payload.opt(i))?.let { return it }
            }
        }
        return null
    }
}

/** 单日 IP 提取记录。label 形如 yyyy-MM-dd。 */
data class IpUsageRecord(val label: String, val total: Int)

/** IP 用量汇总。 */
data class IpUsageSummary(val records: List<IpUsageRecord>, val remainingIp: Int?)
