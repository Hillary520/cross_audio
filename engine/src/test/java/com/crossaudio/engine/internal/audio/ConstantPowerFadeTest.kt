package com.crossaudio.engine.internal.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class ConstantPowerFadeTest {
    @Test
    fun gains_are_clamped_and_have_expected_endpoints() {
        assertEquals(1f, ConstantPowerFade.gainA(0f), 1e-6f)
        assertEquals(0f, ConstantPowerFade.gainB(0f), 1e-6f)
        assertEquals(0f, ConstantPowerFade.gainA(1f), 1e-6f)
        assertEquals(1f, ConstantPowerFade.gainB(1f), 1e-6f)

        // Clamping.
        assertEquals(1f, ConstantPowerFade.gainA(-1f), 1e-6f)
        assertEquals(0f, ConstantPowerFade.gainB(-1f), 1e-6f)
        assertEquals(0f, ConstantPowerFade.gainA(2f), 1e-6f)
        assertEquals(1f, ConstantPowerFade.gainB(2f), 1e-6f)
    }
}

