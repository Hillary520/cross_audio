package com.crossaudio.engine.internal.audiofx

import kotlin.math.pow

internal class LoudnessNormalizer {
    fun apply(
        pcm: ShortArray,
        sampleCount: Int,
        currentLufs: Float,
        targetLufs: Float,
    ) {
        if (sampleCount <= 0) return
        val gainDb = (targetLufs - currentLufs).coerceIn(-12f, 12f)
        val gain = (10.0.pow(gainDb / 20.0)).toFloat()
        val n = sampleCount.coerceAtMost(pcm.size)
        for (i in 0 until n) {
            val boosted = (pcm[i] * gain).toInt()
            pcm[i] = boosted.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
