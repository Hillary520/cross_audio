package com.crossaudio.engine.internal.playback

import com.crossaudio.engine.internal.audio.PcmFormat
import com.crossaudio.engine.internal.audio.PcmPipe16
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Test

class CrossfadeSourceTest {
    @Test
    fun crossfade_starts_as_a_and_ends_as_b() {
        val stop = AtomicBoolean(false)
        val fmt = PcmFormat(sampleRate = 48000, channelCount = 2, encoding = 2)
        val a = PcmPipe16(capacitySamples = 4096 * 2, stop = stop)
        val b = PcmPipe16(capacitySamples = 4096 * 2, stop = stop)

        val fadeFrames = 100L
        val src = CrossfadeSource(fmt, a, b, totalFadeFrames = fadeFrames, headroom = 1.0f)

        val frames = 400
        val aSamples = ShortArray(frames * 2) { 10_000 }
        val bSamples = ShortArray(frames * 2) { 20_000 }
        a.writeBlocking(aSamples, 0, aSamples.size)
        a.markEos()
        b.writeBlocking(bSamples, 0, bSamples.size)
        b.markEos()

        val out = ShortArray(frames * 2)
        val got = src.readFramesBlocking(out, frames)
        assertEquals(frames, got)

        // At t=0: fully A.
        assertEquals(10_000, out[0].toInt())
        assertEquals(10_000, out[1].toInt())

        // Past the fade window: fully B (t clamps to 1.0).
        val afterFadeFrame = (fadeFrames + 10).toInt()
        val base = afterFadeFrame * 2
        assertEquals(20_000, out[base].toInt())
        assertEquals(20_000, out[base + 1].toInt())
    }
}
