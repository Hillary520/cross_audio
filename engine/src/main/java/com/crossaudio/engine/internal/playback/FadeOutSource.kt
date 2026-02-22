package com.crossaudio.engine.internal.playback

import com.crossaudio.engine.internal.audio.ConstantPowerFade
import com.crossaudio.engine.internal.audio.PcmFormat
import com.crossaudio.engine.internal.audio.PcmPipe16
import kotlin.math.roundToInt

/**
 * Applies a fade-out over [totalFadeFrames] while reading from a single PCM pipe.
 * Intended as a fallback when crossfade isn't possible.
 */
internal class FadeOutSource(
    override val format: PcmFormat,
    private val pipe: PcmPipe16,
    private val totalFadeFrames: Long,
) : RenderSource {
    private var processedFrames: Long = 0L

    override fun readFramesBlocking(out: ShortArray, frames: Int): Int {
        val ch = format.channelCount
        val samplesWanted = frames * ch
        val nSamples = pipe.readBlocking(out, 0, samplesWanted)
        val nFrames = nSamples / ch
        if (nFrames <= 0) return 0

        if (totalFadeFrames <= 0) return nFrames

        for (f in 0 until nFrames) {
            val t = ((processedFrames + f).toFloat() / totalFadeFrames.toFloat()).coerceIn(0f, 1f)
            val g = ConstantPowerFade.gainA(t) // 1 -> 0 over the fade window
            val base = f * ch
            for (c in 0 until ch) {
                val v = out[base + c] * g
                out[base + c] = v.roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }

        processedFrames += nFrames.toLong()
        return nFrames
    }
}

