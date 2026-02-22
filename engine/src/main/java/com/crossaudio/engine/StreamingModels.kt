package com.crossaudio.engine

enum class SourceType {
    AUTO,
    PROGRESSIVE,
    HLS,
    DASH,
}

enum class QualityHint {
    DATA_SAVER,
    BALANCED,
    HIGH_QUALITY,
}

enum class QualityCap {
    AUTO,
    LOW,
    MEDIUM,
    HIGH,
}

data class QualityInfo(
    val sourceType: SourceType,
    val bitrateKbps: Int? = null,
    val representationId: String? = null,
)

data class StreamingConfig(
    val startupBitrateKbps: Int = 256,
    val maxBitrateKbps: Int = 3_200,
    val minBitrateKbps: Int = 96,
    val minBufferMs: Int = 8_000,
    val targetBufferMs: Int = 20_000,
    val maxBufferMs: Int = 45_000,
    val qualityCap: QualityCap = QualityCap.AUTO,
    val segmentPipelineEnabled: Boolean = false,
    val segmentPrefetchCount: Int = 3,
    val segmentRetryCount: Int = 2,
)
