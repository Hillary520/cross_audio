package com.crossaudio.engine.internal.abr

import kotlin.math.roundToInt

internal class BandwidthEstimator(
    private val alpha: Double = 0.25,
) {
    private var ewmaKbps: Double? = null

    fun addSample(bytes: Long, downloadMs: Long) {
        if (bytes <= 0L || downloadMs <= 0L) return
        val kbps = (bytes * 8.0) / downloadMs.toDouble()
        ewmaKbps = ewmaKbps?.let { prev -> (alpha * kbps) + ((1.0 - alpha) * prev) } ?: kbps
    }

    fun estimateKbps(): Int? = ewmaKbps?.roundToInt()

    fun reset() {
        ewmaKbps = null
    }
}
