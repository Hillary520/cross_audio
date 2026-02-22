package com.crossaudio.engine.internal.audio

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Test

class LinearResampler16StereoTest {
    @Test
    fun resampler_preserves_constant_signal() {
        val stop = AtomicBoolean(false)
        val r = LinearResampler16Stereo(srcSampleRate = 44100, dstSampleRate = 48000, stop = stop)

        val framesIn = 2000
        val inSamples = ShortArray(framesIn * 2)
        for (i in 0 until framesIn) {
            inSamples[i * 2] = 1000
            inSamples[i * 2 + 1] = -1000
        }
        r.push(inSamples, inSamples.size)

        val out = ShortArray(4096 * 2)
        val framesOut = r.drain(out)
        for (i in 0 until framesOut) {
            assertEquals(1000, out[i * 2].toInt())
            assertEquals(-1000, out[i * 2 + 1].toInt())
        }
    }
}

