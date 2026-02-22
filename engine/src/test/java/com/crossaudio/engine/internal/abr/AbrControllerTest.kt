package com.crossaudio.engine.internal.abr

import com.crossaudio.engine.QualityCap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbrControllerTest {
    private val controller = AbrController()

    @Test
    fun `chooses conservative bitrate from estimate`() {
        val decision = controller.chooseBitrate(
            availableBitratesKbps = listOf(96, 128, 192, 320),
            estimatedBandwidthKbps = 400,
            bufferedMs = 10_000,
            cap = QualityCap.AUTO,
        )

        assertEquals(320, decision.selectedBitrateKbps)
        assertTrue(decision.reason == "max_safe" || decision.reason == "conservative")
    }

    @Test
    fun `applies quality cap`() {
        val decision = controller.chooseBitrate(
            availableBitratesKbps = listOf(96, 160, 256, 320),
            estimatedBandwidthKbps = 1_200,
            bufferedMs = 20_000,
            cap = QualityCap.LOW,
        )

        assertEquals(160, decision.selectedBitrateKbps)
    }

    @Test
    fun `falls back to lowest on low buffer`() {
        val decision = controller.chooseBitrate(
            availableBitratesKbps = listOf(96, 160, 256),
            estimatedBandwidthKbps = 140,
            bufferedMs = 1_000,
            cap = QualityCap.AUTO,
        )

        assertEquals(96, decision.selectedBitrateKbps)
    }
}
