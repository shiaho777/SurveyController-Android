package com.surveycontroller.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.recentStore by preferencesDataStore(name = "recent_surveys")

/** 最近使用过的问卷记录。 */
data class RecentSurvey(
    val url: String,
    val title: String,
    val provider: String,
    val questionCount: Int,
    val timestamp: Long,
)

/**
 * 最近问卷历史持久化（最多保留 15 条，按时间倒序，url 去重）。
 */
class RecentSurveyStore(private val context: Context) {
    private val key = stringPreferencesKey("recent_json")
    private val maxItems = 15

    val recent: Flow<List<RecentSurvey>> = context.recentStore.data.map { p -> parse(p[key]) }

    suspend fun add(item: RecentSurvey) {
        context.recentStore.edit { prefs ->
            val list = parse(prefs[key]).filter { it.url != item.url }.toMutableList()
            list.add(0, item)
            prefs[key] = serialize(list.take(maxItems))
        }
    }

    suspend fun clear() {
        context.recentStore.edit { it.remove(key) }
    }

    private fun parse(raw: String?): List<RecentSurvey> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                RecentSurvey(
                    url = o.optString("url"),
                    title = o.optString("title"),
                    provider = o.optString("provider"),
                    questionCount = o.optInt("q"),
                    timestamp = o.optLong("ts"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun serialize(list: List<RecentSurvey>): String {
        val arr = JSONArray()
        for (it in list) {
            arr.put(
                JSONObject()
                    .put("url", it.url).put("title", it.title)
                    .put("provider", it.provider).put("q", it.questionCount).put("ts", it.timestamp),
            )
        }
        return arr.toString()
    }
}
