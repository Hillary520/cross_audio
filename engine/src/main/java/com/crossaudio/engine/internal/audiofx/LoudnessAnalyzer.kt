package com.crossaudio.engine.internal.audiofx

import kotlin.math.ln
import kotlin.math.sqrt

internal class LoudnessAnalyzer {
    fun estimateLufs(pcm: ShortArray, sampleCount: Int): Float {
        if (sampleCount <= 0) return -70f
        var sum = 0.0
        val n = sampleCount.coerceAtMost(pcm.size)
        for (i in 0 until n) {
            val s = pcm[i] / 32768.0
            sum += s * s
        }
        val rms = sqrt(sum / n)
        if (rms <= 1e-9) return -70f
        // Approximate LUFS from RMS for a lightweight real-time estimate.
        return (20.0 * ln(rms) / ln(10.0)).toFloat()
    }
}
