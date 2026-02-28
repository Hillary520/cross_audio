package com.crossaudio.engine.internal.telemetry

import com.crossaudio.engine.EngineTelemetryEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryAccumulatorTest {
    @Test
    fun `accumulates cache hit ratio and decoder drops`() {
        val accumulator = TelemetryAccumulator()

        accumulator.onEvent(EngineTelemetryEvent.CacheHit("a"))
        accumulator.onEvent(EngineTelemetryEvent.CacheMiss("b"))
        accumulator.onEvent(EngineTelemetryEvent.CacheHit("c"))
        accumulator.onEvent(EngineTelemetryEvent.DecoderUnderrun(1))
        accumulator.onEvent(EngineTelemetryEvent.DecoderUnderrun(2))

        assertEquals(0.667f, accumulator.cacheHitRatio(), 0.0001f)
        assertEquals(2, accumulator.decoderDrops())
    }

    @Test
    fun `tracks estimated bandwidth and rebuffer count`() {
        val accumulator = TelemetryAccumulator()
        assertNull(accumulator.estimatedBandwidthKbps())

        accumulator.onEvent(
            EngineTelemetryEvent.AbrDecision(
                selectedBitrateKbps = 192,
                estimatedBandwidthKbps = 820,
                reason = "test",
            ),
        )
        accumulator.onEvent(EngineTelemetryEvent.RebufferStart(atPositionMs = 1_250))
        accumulator.onEvent(EngineTelemetryEvent.RebufferStart(atPositionMs = 2_250))

        assertEquals(820, accumulator.estimatedBandwidthKbps())
        assertEquals(2, accumulator.rebufferCount())
    }
}
