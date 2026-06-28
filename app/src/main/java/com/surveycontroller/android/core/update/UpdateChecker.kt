package com.surveycontroller.android.core.update

import com.surveycontroller.android.app.AppVersion
import com.surveycontroller.android.core.network.HttpClient
import com.surveycontroller.android.core.network.UserAgents
import org.json.JSONObject

/** 更新检查结果。 */
data class UpdateInfo(
    val hasUpdate: Boolean,
    val currentVersion: String,
    val latestVersion: String = "",
    val releaseNotes: String = "",
    val releaseUrl: String = AppVersion.RELEASES_PAGE,
    val apkDownloadUrl: String? = null,
    val error: String? = null,
)

/**
 * 通过 GitHub Releases API 检查更新。对标桌面端 UpdateManager（桌面用 Velopack，移动端用 GitHub Release + 下载 APK）。
 */
class UpdateChecker(private val http: HttpClient) {

    suspend fun check(): UpdateInfo {
        val current = AppVersion.VERSION
        return try {
            val resp = http.get(
                AppVersion.LATEST_RELEASE_API,
                headers = UserAgents.DEFAULT_HEADERS + mapOf("Accept" to "application/vnd.github+json"),
                timeoutSeconds = 15,
            )
            if (resp.statusCode != 200) {
                return UpdateInfo(false, current, error = "检查失败（HTTP ${resp.statusCode}）")
            }
            val json = JSONObject(resp.body.ifBlank { "{}" })
            val tag = json.optString("tag_name").trim().ifEmpty { json.optString("name").trim() }
            val notes = json.optString("body").trim()
            val pageUrl = json.optString("html_url").trim().ifEmpty { AppVersion.RELEASES_PAGE }
            // 找 .apk 资源
            var apkUrl: String? = null
            json.optJSONArray("assets")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val a = arr.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    if (name.endsWith(".apk", true)) { apkUrl = a.optString("browser_download_url"); break }
                }
            }
            val hasUpdate = tag.isNotEmpty() && AppVersion.compareVersions(tag, current) > 0
            UpdateInfo(
                hasUpdate = hasUpdate,
                currentVersion = current,
                latestVersion = tag,
                releaseNotes = notes,
                releaseUrl = pageUrl,
                apkDownloadUrl = apkUrl,
            )
        } catch (e: Exception) {
            UpdateInfo(false, current, error = e.message ?: "检查更新失败")
        }
    }
}
