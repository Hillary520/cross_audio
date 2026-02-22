package com.crossaudio.engine.internal.streaming

internal class ContainerDemuxAdapter {
    fun isSupportedMime(mime: String?): Boolean {
        if (mime.isNullOrBlank()) return false
        val v = mime.lowercase()
        return v.contains("mpegurl") || v.contains("mp2t") || v.contains("mp4") || v.contains("dash")
    }

    fun toDecoderInputUnits(payload: ByteArray): List<ByteArray> {
        // Current core still consumes extractor/codec directly.
        // This adapter is the seam for future TS/fMP4 elementary sample extraction.
        return listOf(payload)
    }
}
