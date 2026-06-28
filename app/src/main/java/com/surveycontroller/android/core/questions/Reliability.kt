package com.surveycontroller.android.core.questions

/** 信效度固定调参。复刻 software/core/questions/reliability_mode.py:DEFAULT_RELIABILITY_PROFILE。 */
data class ReliabilityProfile(
    val distributionWarmupSamples: Int = 14,
    val distributionGain: Double = 1.75,
    val distributionMinFactor: Double = 0.80,
    val distributionMaxFactor: Double = 1.28,
    val distributionGapLimit: Double = 0.28,
    val consistencyWindowRatio: Double = 0.18,
    val consistencyWindowMax: Int = 8,
    val consistencyCenterWeight: Double = 1.8,
    val consistencyEdgeWeight: Double = 0.86,
    val consistencyOutsideDecay: Double = 0.02,
)

val DEFAULT_RELIABILITY_PROFILE = ReliabilityProfile()

const val DIMENSION_UNGROUPED = "__ungrouped__"
