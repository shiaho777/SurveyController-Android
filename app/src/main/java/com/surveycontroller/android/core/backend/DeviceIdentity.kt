package com.surveycontroller.android.core.backend

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.deviceStore by preferencesDataStore(name = "device_identity")

/**
 * 持久设备标识。1:1 复刻 software/system/device_fingerprint.py：
 * 首次生成 "sc-v2-" + 32位十六进制，后续复用。
 */
class DeviceIdentity(private val context: Context) {
    private val key = stringPreferencesKey("device_id")

    suspend fun get(): String {
        val existing = context.deviceStore.data.first()[key]?.trim()
        if (!existing.isNullOrEmpty()) return existing
        val id = "sc-v2-" + UUID.randomUUID().toString().replace("-", "").take(32)
        context.deviceStore.edit { it[key] = id }
        return id
    }
}
