package com.crossaudio.engine.internal.telemetry

import com.crossaudio.engine.EngineTelemetryEvent
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

internal class TelemetryAccumulator {
    private val rebufferCount = AtomicInteger(0)
    private val decoderDrops = AtomicInteger(0)
    private val drmSessionCount = AtomicInteger(0)
    private val cacheHitCount = AtomicInteger(0)
    private val cacheMissCount = AtomicInteger(0)
    private val estimatedBandwidthKbps = AtomicInteger(0)

    fun onEvent(event: EngineTelemetryEvent) {
        when (event) {
            is EngineTelemetryEvent.AbrDecision -> estimatedBandwidthKbps.set(event.estimatedBandwidthKbps.coerceAtLeast(0))
            is EngineTelemetryEvent.RebufferStart -> rebufferCount.incrementAndGet()
            is EngineTelemetryEvent.DecoderUnderrun -> decoderDrops.incrementAndGet()
            is EngineTelemetryEvent.DrmSessionOpened -> drmSessionCount.incrementAndGet()
            is EngineTelemetryEvent.CacheHit -> cacheHitCount.incrementAndGet()
            is EngineTelemetryEvent.CacheMiss -> cacheMissCount.incrementAndGet()
            else -> Unit
        }
    }

    fun rebufferCount(): Int = rebufferCount.get()

    fun decoderDrops(): Int = decoderDrops.get()

    fun drmSessionCount(): Int = drmSessionCount.get()

    fun estimatedBandwidthKbps(): Int? = estimatedBandwidthKbps.get().takeIf { it > 0 }

    fun cacheHitRatio(): Float {
        val hits = cacheHitCount.get().coerceAtLeast(0)
        val misses = cacheMissCount.get().coerceAtLeast(0)
        val total = hits + misses
        if (total <= 0) return 0f
        return ((hits.toDouble() / total.toDouble()) * 1000.0).roundToInt() / 1000f
    }
}
