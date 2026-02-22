package com.crossaudio.engine.internal.playback

import com.crossaudio.engine.internal.audio.PcmFormat
import com.crossaudio.engine.internal.audio.PcmPipe16
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class ConcatSourceTest {
    @Test
    fun concat_switches_once_and_keeps_order() {
        val stop = AtomicBoolean(false)
        val fmt = PcmFormat(sampleRate = 48000, channelCount = 2, encoding = 2)
        val a = PcmPipe16(capacitySamples = 1024 * 2, stop = stop)
        val b = PcmPipe16(capacitySamples = 1024 * 2, stop = stop)

        val switched = AtomicInteger(0)
        val src = ConcatSource(fmt, a, b) { switched.incrementAndGet() }

        val aFrames = 4
        val bFrames = 3

        val aSamples = ShortArray(aFrames * 2) { 111 }
        val bSamples = ShortArray(bFrames * 2) { 222 }

        a.writeBlocking(aSamples, 0, aSamples.size)
        a.markEos()
        b.writeBlocking(bSamples, 0, bSamples.size)
        b.markEos()

        val out = ShortArray(16 * 2)
        val r1 = src.readFramesBlocking(out, frames = 4)
        assertEquals(4, r1)
        assertEquals(0, switched.get())
        assertEquals(111, out[0].toInt())

        val r2 = src.readFramesBlocking(out, frames = 4)
        assertEquals(3, r2)
        assertEquals(1, switched.get())
        assertEquals(222, out[0].toInt())
    }
}

