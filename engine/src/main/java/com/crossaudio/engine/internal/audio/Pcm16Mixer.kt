package com.crossaudio.engine.internal.audio

import kotlin.math.roundToInt

/**
 * Mixes two interleaved stereo PCM16 buffers into the output buffer.
 *
 * This is a building block for crossfade. It intentionally keeps allocations out of the hot path.
 */
internal object Pcm16Mixer {
    /**
     * @param a interleaved stereo PCM16, little-endian in a ShortArray (native-endian shorts)
     * @param b interleaved stereo PCM16
     * @param out interleaved stereo PCM16
     * @param frames number of stereo frames to mix (each frame is L+R = 2 shorts)
     * @param gainA linear gain applied to [a]
     * @param gainB linear gain applied to [b]
     */
    fun mixStereo(
        a: ShortArray,
        b: ShortArray,
        out: ShortArray,
        frames: Int,
        gainA: Float,
        gainB: Float,
    ) {
        val samples = frames * 2
        val gA = gainA
        val gB = gainB
        for (i in 0 until samples) {
            val mixed = a[i] * gA + b[i] * gB
            val clipped = mixed.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = clipped.toShort()
        }
    }
}
