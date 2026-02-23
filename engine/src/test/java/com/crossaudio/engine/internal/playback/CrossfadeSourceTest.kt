package com.crossaudio.engine.internal.playback

import com.crossaudio.engine.internal.audio.PcmFormat
import com.crossaudio.engine.internal.audio.PcmPipe16
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

        val frames = 220
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

        // Mid-fade should be equal-power-ish (~0.707A + 0.707B).
        val midBase = 50 * 2
        assertTrue(out[midBase].toInt() in 21200..21300)
        assertTrue(out[midBase + 1].toInt() in 21200..21300)

        // Past the fade window: fully B (t clamps to 1.0).
        val afterFadeFrame = (fadeFrames + 10).toInt()
        val base = afterFadeFrame * 2
        assertEquals(20_000, out[base].toInt())
        assertEquals(20_000, out[base + 1].toInt())
    }

    @Test
    fun crossfade_gains_are_monotonic() {
        val fmt = PcmFormat(sampleRate = 48000, channelCount = 2, encoding = 2)
        val fadeFrames = 80L
        val frames = 120

        run {
            val stop = AtomicBoolean(false)
            val a = PcmPipe16(capacitySamples = 4096 * 2, stop = stop)
            val b = PcmPipe16(capacitySamples = 4096 * 2, stop = stop)
            val src = CrossfadeSource(fmt, a, b, totalFadeFrames = fadeFrames, headroom = 1.0f)

            a.writeBlocking(ShortArray(frames * 2) { 10_000 }, 0, frames * 2)
            a.markEos()
            b.writeBlocking(ShortArray(frames * 2) { 0 }, 0, frames * 2)
            b.markEos()

            val out = ShortArray(frames * 2)
            val got = src.readFramesBlocking(out, frames)
            assertEquals(frames, got)
            var previous = out[0].toInt()
            for (f in 1 until fadeFrames.toInt()) {
                val value = out[f * 2].toInt()
                assertTrue("A gain should be non-increasing at frame=$f (prev=$previous value=$value)", value <= previous)
                previous = value
            }
        }

        run {
            val stop = AtomicBoolean(false)
            val a = PcmPipe16(capacitySamples = 4096 * 2, stop = stop)
            val b = PcmPipe16(capacitySamples = 4096 * 2, stop = stop)
            val src = CrossfadeSource(fmt, a, b, totalFadeFrames = fadeFrames, headroom = 1.0f)

            a.writeBlocking(ShortArray(frames * 2) { 0 }, 0, frames * 2)
            a.markEos()
            b.writeBlocking(ShortArray(frames * 2) { 10_000 }, 0, frames * 2)
            b.markEos()

            val out = ShortArray(frames * 2)
            val got = src.readFramesBlocking(out, frames)
            assertEquals(frames, got)
            var previous = out[0].toInt()
            for (f in 1 until fadeFrames.toInt()) {
                val value = out[f * 2].toInt()
                assertTrue("B gain should be non-decreasing at frame=$f (prev=$previous value=$value)", value >= previous)
                previous = value
            }
        }
    }

    @Test
    fun crossfade_does_not_reduce_a_until_b_has_audio() {
        val stop = AtomicBoolean(false)
        val fmt = PcmFormat(sampleRate = 48000, channelCount = 2, encoding = 2)
        val a = PcmPipe16(capacitySamples = 4096 * 2, stop = stop)
        val b = PcmPipe16(capacitySamples = 4096 * 2, stop = stop)
        val src = CrossfadeSource(fmt, a, b, totalFadeFrames = 32L, headroom = 1.0f)

        a.writeBlocking(ShortArray(40 * 2) { 10_000 }, 0, 40 * 2)
        a.markEos()

        val firstOut = ShortArray(10 * 2)
        val firstGot = src.readFramesBlocking(firstOut, 10)
        assertEquals(10, firstGot)
        for (sample in firstOut) {
            assertEquals(10_000, sample.toInt())
        }

        b.writeBlocking(ShortArray(40 * 2) { 20_000 }, 0, 40 * 2)
        b.markEos()

        val secondOut = ShortArray(10 * 2)
        val secondGot = src.readFramesBlocking(secondOut, 10)
        assertEquals(10, secondGot)
        // No fade progress before B arrived: first overlapped frame should still be A-full.
        assertEquals(10_000, secondOut[0].toInt())
        assertTrue(secondOut[18].toInt() > secondOut[0].toInt())
    }
}
