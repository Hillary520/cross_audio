package com.crossaudio.engine.internal.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm16MixerTest {
    @Test
    fun mixesAndClips() {
        val frames = 2
        val a = shortArrayOf(20000, 20000, 20000, 20000) // 2 frames stereo
        val b = shortArrayOf(20000, 20000, 20000, 20000)
        val out = ShortArray(frames * 2)

        Pcm16Mixer.mixStereo(a, b, out, frames, gainA = 1f, gainB = 1f)

        // 40000 should clip to 32767
        assertEquals(Short.MAX_VALUE, out[0])
        assertEquals(Short.MAX_VALUE, out[1])
        assertEquals(Short.MAX_VALUE, out[2])
        assertEquals(Short.MAX_VALUE, out[3])
    }
}

