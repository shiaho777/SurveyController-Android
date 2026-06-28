package com.surveycontroller.android.core.network

import kotlin.random.Random

/** UA 预设。1:1 复刻 software/app/config.py:USER_AGENT_PRESETS。 */
object UserAgents {
    data class Preset(val key: String, val label: String, val ua: String)

    val PC_WEB = Preset(
        "pc_web", "电脑网页端",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
    )
    val MOBILE_ANDROID = Preset(
        "mobile_android", "安卓手机浏览器",
        "Mozilla/5.0 (Linux; Android 16; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
    )
    val WECHAT_ANDROID = Preset(
        "wechat_android", "安卓微信端",
        "Mozilla/5.0 (Linux; Android 16; Pixel 8 Build/BP22.250124.009; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/121.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.43.2460(0x28002B3B) Process/appbrand0 WeChat/arm64 Weixin NetType/WIFI Language/zh_CN ABI/arm64",
    )

    val PRESETS: Map<String, Preset> = listOf(PC_WEB, MOBILE_ANDROID, WECHAT_ANDROID)
        .associateBy { it.key }

    val DEFAULT_USER_AGENT: String = PC_WEB.ua

    /** 默认 HTTP 请求头。复刻 DEFAULT_HTTP_HEADERS。 */
    val DEFAULT_HEADERS: Map<String, String> = mapOf(
        "User-Agent" to DEFAULT_USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        "Connection" to "close",
    )

    fun resolve(userAgent: String?): String =
        userAgent?.trim()?.takeIf { it.isNotEmpty() } ?: WECHAT_ANDROID.ua

    /**
     * 随机 UA 配置档。复刻 software/core/config/codec.py:UserAgentProfile。
     * category 为设备类型（wechat/mobile/pc），presetKey 为 UA 预设键，ua 为最终 UA 字符串。
     */
    data class UserAgentProfile(
        val category: String,
        val presetKey: String,
        val ua: String,
        val label: String,
    )

    /** 设备类型 → 候选 UA 预设键列表。对齐 _USER_AGENT_DEVICE_TO_PRESET_KEYS。 */
    private val DEVICE_TO_PRESET_KEYS: Map<String, List<String>> = mapOf(
        "wechat" to listOf("wechat_android"),
        "mobile" to listOf("mobile_android"),
        "pc" to listOf("pc_web"),
    )

    /**
     * 按比例加权随机选择 UA。复刻 software/core/config/codec.py:_select_user_agent_from_ratios。
     * 返回 null 表示 ratios 为空或权重全为 0。
     */
    fun selectUserAgentFromRatios(ratios: Map<String, Int>, rng: Random = Random): UserAgentProfile? {
        val devices = mutableListOf<String>()
        val weights = mutableListOf<Int>()
        for ((deviceType, keys) in DEVICE_TO_PRESET_KEYS) {
            if (keys.isEmpty()) continue
            val weight = (ratios[deviceType] ?: 0).coerceAtLeast(0)
            if (weight > 0) {
                devices.add(deviceType)
                weights.add(weight)
            }
        }
        if (devices.isEmpty()) return null

        val total = weights.sum().coerceAtLeast(1)
        var pivot = rng.nextInt(total)
        var selectedIdx = 0
        for (i in weights.indices) {
            pivot -= weights[i]
            if (pivot < 0) {
                selectedIdx = i
                break
            }
        }
        val deviceType = devices[selectedIdx]
        val keys = DEVICE_TO_PRESET_KEYS[deviceType].orEmpty()
        if (keys.isEmpty()) return null
        val key = keys[rng.nextInt(keys.size)]
        val preset = PRESETS[key] ?: return null
        val ua = preset.ua.trim()
        if (ua.isEmpty()) return null
        return UserAgentProfile(
            category = deviceType.trim(),
            presetKey = key.trim(),
            ua = ua,
            label = preset.label.trim(),
        )
    }
}
