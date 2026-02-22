package com.crossaudio.engine.internal.abr

import com.crossaudio.engine.QualityCap

internal data class AbrDecision(
    val selectedBitrateKbps: Int,
    val reason: String,
)

internal class AbrController(
    private val capPolicy: QualityCapPolicy = QualityCapPolicy(),
) {
    fun chooseBitrate(
        availableBitratesKbps: List<Int>,
        estimatedBandwidthKbps: Int?,
        bufferedMs: Long,
        cap: QualityCap,
    ): AbrDecision {
        if (availableBitratesKbps.isEmpty()) {
            return AbrDecision(selectedBitrateKbps = 128, reason = "fallback_empty")
        }
        val capped = capPolicy.filter(availableBitratesKbps, cap).sorted()
        val estimate = estimatedBandwidthKbps ?: return AbrDecision(capped.first(), "no_estimate")

        val safety = if (bufferedMs < 6_000L) 0.60 else 0.80
        val safeBudget = (estimate * safety).toInt().coerceAtLeast(32)
        val chosen = capped.lastOrNull { it <= safeBudget } ?: capped.first()
        val reason = if (chosen < (capped.maxOrNull() ?: chosen)) "conservative" else "max_safe"
        return AbrDecision(chosen, reason)
    }
}
