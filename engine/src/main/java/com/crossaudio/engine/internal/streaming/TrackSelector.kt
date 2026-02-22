package com.crossaudio.engine.internal.streaming

import com.crossaudio.engine.QualityCap
import com.crossaudio.engine.internal.abr.QualityCapPolicy
import com.crossaudio.engine.internal.streaming.dash.DashRepresentation
import com.crossaudio.engine.internal.streaming.hls.HlsVariant

internal class TrackSelector(
    private val capPolicy: QualityCapPolicy = QualityCapPolicy(),
) {
    fun selectHlsVariant(variants: List<HlsVariant>, cap: QualityCap): HlsVariant {
        if (variants.isEmpty()) throw IllegalArgumentException("variants cannot be empty")
        val allowed = capPolicy.filter(variants.map { it.bandwidthKbps }, cap)
        val target = allowed.maxOrNull() ?: variants.minOf { it.bandwidthKbps }
        return variants.minByOrNull { kotlin.math.abs(it.bandwidthKbps - target) } ?: variants.first()
    }

    fun selectDashRepresentation(
        representations: List<DashRepresentation>,
        cap: QualityCap,
    ): DashRepresentation? {
        if (representations.isEmpty()) return null
        val bitrates = representations.map { it.bandwidthKbps }.filter { it > 0 }
        if (bitrates.isEmpty()) return representations.first()
        val allowed = capPolicy.filter(bitrates, cap)
        val target = allowed.maxOrNull() ?: bitrates.minOrNull() ?: return representations.first()
        return representations.minByOrNull { kotlin.math.abs(it.bandwidthKbps - target) }
    }
}
