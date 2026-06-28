package com.surveycontroller.android.core

import com.surveycontroller.android.core.engine.RunStopPolicy
import com.surveycontroller.android.core.model.ExecutionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunStopPolicyTest {
    @Test
    fun uses_configured_threshold_for_normal_concurrency() {
        val config = ExecutionConfig(numThreads = 8, failThreshold = 5)

        assertEquals(5, RunStopPolicy.failureThreshold(config, workerCount = 8))
        assertFalse(RunStopPolicy.shouldStopOnFail(config, failCount = 4, workerCount = 8))
        assertTrue(RunStopPolicy.shouldStopOnFail(config, failCount = 5, workerCount = 8))
    }

    @Test
    fun raises_threshold_for_high_concurrency_like_desktop() {
        val config = ExecutionConfig(numThreads = 16, failThreshold = 5)

        assertEquals(8, RunStopPolicy.failureThreshold(config, workerCount = 16))
        assertFalse(RunStopPolicy.shouldStopOnFail(config, failCount = 7, workerCount = 16))
        assertTrue(RunStopPolicy.shouldStopOnFail(config, failCount = 8, workerCount = 16))
    }

    @Test
    fun respects_disabled_stop_on_fail() {
        val config = ExecutionConfig(numThreads = 16, failThreshold = 5, stopOnFailEnabled = false)

        assertEquals(8, RunStopPolicy.failureThreshold(config, workerCount = 16))
        assertFalse(RunStopPolicy.shouldStopOnFail(config, failCount = 100, workerCount = 16))
    }

    @Test
    fun proxy_unavailable_threshold_scales_with_workers_when_random_ip_is_enabled() {
        val config = ExecutionConfig(numThreads = 8, failThreshold = 5, randomProxyIpEnabled = true)

        assertEquals(8, RunStopPolicy.proxyUnavailableThreshold(config, workerCount = 8))
        assertFalse(RunStopPolicy.shouldStopOnProxyUnavailable(config, failCount = 7, workerCount = 8))
        assertTrue(RunStopPolicy.shouldStopOnProxyUnavailable(config, failCount = 8, workerCount = 8))
    }

    @Test
    fun proxy_unavailable_uses_regular_threshold_when_random_ip_is_disabled() {
        val config = ExecutionConfig(numThreads = 8, failThreshold = 5, randomProxyIpEnabled = false)

        assertEquals(5, RunStopPolicy.proxyUnavailableThreshold(config, workerCount = 8))
        assertTrue(RunStopPolicy.shouldStopOnProxyUnavailable(config, failCount = 5, workerCount = 8))
    }
}
