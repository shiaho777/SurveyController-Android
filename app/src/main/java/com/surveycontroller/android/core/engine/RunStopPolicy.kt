package com.surveycontroller.android.core.engine

import com.surveycontroller.android.core.model.ExecutionConfig
import kotlin.math.ceil

/**
 * 运行停止策略。对齐桌面端 RunStopPolicy 的失败阈值计算。
 */
internal object RunStopPolicy {
    const val AI_FILL_FAIL_THRESHOLD = 5

    fun failureThreshold(config: ExecutionConfig, workerCount: Int = config.numThreads): Int {
        val baseThreshold = maxOf(1, config.failThreshold)
        val workers = maxOf(1, workerCount)
        return if (workers > 10) {
            maxOf(baseThreshold, ceil(workers / 2.0).toInt())
        } else {
            baseThreshold
        }
    }

    fun proxyUnavailableThreshold(config: ExecutionConfig, workerCount: Int = config.numThreads): Int {
        val baseThreshold = failureThreshold(config, workerCount)
        return if (config.randomProxyIpEnabled) {
            maxOf(baseThreshold, maxOf(1, workerCount))
        } else {
            baseThreshold
        }
    }

    fun shouldStopOnFail(config: ExecutionConfig, failCount: Int, workerCount: Int = config.numThreads): Boolean =
        config.stopOnFailEnabled && failCount >= failureThreshold(config, workerCount)

    fun shouldStopOnProxyUnavailable(config: ExecutionConfig, failCount: Int, workerCount: Int = config.numThreads): Boolean =
        config.stopOnFailEnabled && failCount >= proxyUnavailableThreshold(config, workerCount)

    fun shouldStopOnAiFailure(failCount: Int): Boolean =
        failCount >= AI_FILL_FAIL_THRESHOLD
}
