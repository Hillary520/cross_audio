package com.crossaudio.engine.internal.abr

import com.crossaudio.engine.QualityCap

internal class QualityCapPolicy {
    fun filter(bitratesKbps: List<Int>, cap: QualityCap): List<Int> {
        if (bitratesKbps.isEmpty()) return emptyList()
        val ceiling = when (cap) {
            QualityCap.AUTO -> Int.MAX_VALUE
            QualityCap.LOW -> 160
            QualityCap.MEDIUM -> 320
            QualityCap.HIGH -> 1_500
        }
        val within = bitratesKbps.filter { it in 1..ceiling }
        return if (within.isNotEmpty()) within else listOf(bitratesKbps.minOrNull() ?: bitratesKbps.first())
    }
}
