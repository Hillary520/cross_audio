package com.crossaudio.engine.internal.streaming

import java.net.URI

internal data class SegmentRef(
    val uri: String,
    val durationUs: Long,
)

internal data class SegmentWindow(
    val segments: List<SegmentRef>,
) {
    fun totalDurationUs(): Long = segments.sumOf { it.durationUs }
}

internal object SegmentTimeline {
    fun resolveUri(base: String, candidate: String): String {
        return runCatching {
            URI(base).resolve(candidate).toString()
        }.getOrDefault(candidate)
    }
}
