package com.crossaudio.engine.internal.audio

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Streaming linear resampler for stereo PCM16.
 *
 * - Input: interleaved stereo at [srcSampleRate]
 * - Output: interleaved stereo at [dstSampleRate]
 */
internal class LinearResampler16Stereo(
    private val srcSampleRate: Int,
    private val dstSampleRate: Int,
    stop: AtomicBoolean,
) {
    private val step = srcSampleRate.toDouble() / dstSampleRate.toDouble() // input frames per output frame
    private var frac = 0.0

    private val q = StereoFrameQueue16(capacityFrames = 8192, stop = stop)

    private var haveCur = false
    private var haveNext = false
    private var curL: Short = 0
    private var curR: Short = 0
    private var nextL: Short = 0
    private var nextR: Short = 0

    private val oneFrame = ShortArray(2)

    fun push(samples: ShortArray, sampleCount: Int) {
        q.writeBlocking(samples, 0, sampleCount)
    }

    /**
     * Drains as many output frames as possible into [out] and returns frames produced.
     * Caller can then write `frames*2` samples to a pipe.
     */
    fun drain(out: ShortArray): Int {
        val maxFrames = out.size / 2
        var produced = 0

        while (produced < maxFrames) {
            if (!haveCur) {
                if (!q.popFrameAvailable(oneFrame, 0)) break
                curL = oneFrame[0]
                curR = oneFrame[1]
                haveCur = true
            }
            if (!haveNext) {
                if (!q.popFrameAvailable(oneFrame, 0)) break
                nextL = oneFrame[0]
                nextR = oneFrame[1]
                haveNext = true
            }

            // Lerp between cur and next.
            val t = frac
            val l = curL + ((nextL - curL) * t).toDouble()
            val r = curR + ((nextR - curR) * t).toDouble()
            val base = produced * 2
            out[base] = l.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            out[base + 1] = r.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            produced += 1

            frac += step
            while (frac >= 1.0) {
                // Advance input by 1 frame.
                curL = nextL
                curR = nextR
                haveCur = true
                // Pull a new next frame.
                if (!q.popFrameAvailable(oneFrame, 0)) {
                    haveNext = false
                    frac -= 1.0
                    break
                }
                nextL = oneFrame[0]
                nextR = oneFrame[1]
                haveNext = true
                frac -= 1.0
            }

            if (!haveNext) break
        }

        return produced
    }
}
