package com.crossaudio.engine.internal.streaming

import com.crossaudio.engine.QualityCap
import com.crossaudio.engine.internal.abr.QualityCapPolicy
import com.crossaudio.engine.internal.streaming.dash.DashRepresentation
import com.crossaudio.engine.internal.streaming.hls.HlsVariant

internal class TrackSelector(
    private val capPolicy: QualityCapPolicy = QualityCapPolicy(),
) {
    fun selectHlsVariant(
        variants: List<HlsVariant>,
        cap: QualityCap,
        targetBitrateKbps: Int? = null,
    ): HlsVariant {
        if (variants.isEmpty()) throw IllegalArgumentException("variants cannot be empty")
        val allowed = capPolicy.filter(variants.map { it.bandwidthKbps }, cap).toSet()
        val candidates = variants.filter { it.bandwidthKbps in allowed }.ifEmpty { variants }
        val target = chooseTargetBitrate(
            available = candidates.map { it.bandwidthKbps },
            requested = targetBitrateKbps,
        )
        return candidates.minByOrNull { kotlin.math.abs(it.bandwidthKbps - target) } ?: candidates.first()
    }

    fun selectDashRepresentation(
        representations: List<DashRepresentation>,
        cap: QualityCap,
        targetBitrateKbps: Int? = null,
    ): DashRepresentation? {
        if (representations.isEmpty()) return null
        val withBitrate = representations.filter { it.bandwidthKbps > 0 }
        if (withBitrate.isEmpty()) return representations.first()
        val allowed = capPolicy.filter(withBitrate.map { it.bandwidthKbps }, cap).toSet()
        val candidates = withBitrate.filter { it.bandwidthKbps in allowed }.ifEmpty { withBitrate }
        val target = chooseTargetBitrate(
            available = candidates.map { it.bandwidthKbps },
            requested = targetBitrateKbps,
        )
        return candidates.minByOrNull { kotlin.math.abs(it.bandwidthKbps - target) }
    }

    private fun chooseTargetBitrate(available: List<Int>, requested: Int?): Int {
        if (available.isEmpty()) return 128
        val sorted = available.sorted()
        if (requested == null || requested <= 0) return sorted.last()
        return sorted.lastOrNull { it <= requested } ?: sorted.first()
    }
}
