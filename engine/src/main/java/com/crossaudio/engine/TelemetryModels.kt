package com.crossaudio.engine

data class EngineTelemetrySnapshot(
    val currentBitrateKbps: Int? = null,
    val estimatedBandwidthKbps: Int? = null,
    val rebufferCount: Int = 0,
    val decoderDrops: Int = 0,
    val drmSessionCount: Int = 0,
    val cacheHitRatio: Float = 0f,
)

sealed class EngineTelemetryEvent {
    data class AbrDecision(
        val selectedBitrateKbps: Int,
        val estimatedBandwidthKbps: Int,
        val reason: String,
    ) : EngineTelemetryEvent()

    data class RebufferStart(
        val atPositionMs: Long,
    ) : EngineTelemetryEvent()

    data class RebufferEnd(
        val atPositionMs: Long,
        val durationMs: Long,
    ) : EngineTelemetryEvent()

    data class DrmSessionOpened(
        val scheme: String,
    ) : EngineTelemetryEvent()

    data class DrmSessionFailed(
        val message: String,
    ) : EngineTelemetryEvent()

    data class DrmSessionClosed(
        val scheme: String,
    ) : EngineTelemetryEvent()

    data class LicenseAcquired(
        val licenseId: String,
    ) : EngineTelemetryEvent()

    data class OfflineLicenseUsed(
        val licenseId: String,
    ) : EngineTelemetryEvent()

    data class CrossfadeStarted(
        val fromIndex: Int,
        val toIndex: Int,
        val durationMs: Long,
    ) : EngineTelemetryEvent()

    data class CrossfadeCompleted(
        val atIndex: Int,
    ) : EngineTelemetryEvent()

    data class DecoderUnderrun(
        val count: Long,
    ) : EngineTelemetryEvent()

    data class CacheHit(
        val key: String,
    ) : EngineTelemetryEvent()

    data class CacheMiss(
        val key: String,
    ) : EngineTelemetryEvent()
}
