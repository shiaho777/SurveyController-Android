package com.surveycontroller.android.core.backend

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.ipSessionStore by preferencesDataStore(name = "random_ip_session")

/** 随机IP账号会话（用户ID + 额度）。对应 RandomIPSession 的持久化。 */
data class RandomIpSession(
    val userId: Int = 0,
    val remainingQuota: Double = 0.0,
    val totalQuota: Double = 0.0,
    val usedQuota: Double = 0.0,
) {
    val authenticated: Boolean get() = userId > 0
}

class RandomIpSessionStore(private val context: Context) {
    private val kUser = intPreferencesKey("user_id")
    private val kRemain = doublePreferencesKey("remaining_quota")
    private val kTotal = doublePreferencesKey("total_quota")
    private val kUsed = doublePreferencesKey("used_quota")

    suspend fun load(): RandomIpSession {
        val p = context.ipSessionStore.data.first()
        return RandomIpSession(
            userId = p[kUser] ?: 0,
            remainingQuota = p[kRemain] ?: 0.0,
            totalQuota = p[kTotal] ?: 0.0,
            usedQuota = p[kUsed] ?: 0.0,
        )
    }

    suspend fun save(session: RandomIpSession) {
        context.ipSessionStore.edit {
            it[kUser] = session.userId
            it[kRemain] = session.remainingQuota
            it[kTotal] = session.totalQuota
            it[kUsed] = session.usedQuota
        }
    }

    suspend fun clear() {
        context.ipSessionStore.edit { it.clear() }
    }
}
