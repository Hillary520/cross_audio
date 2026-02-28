package com.crossaudio.engine

data class StreamInfo(
    val sourceType: SourceType,
    val fileType: String? = null,
    val mimeType: String? = null,
    val bitrateKbps: Int? = null,
    val streamId: String? = null,
    val durationMs: Long? = null,
)
