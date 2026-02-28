package com.crossaudio.engine.internal.streaming.hls

import com.crossaudio.engine.internal.streaming.SegmentRef

internal data class HlsMediaPlaylist(
    val targetDurationSec: Int,
    val segments: List<SegmentRef>,
    val mediaSequence: Long = 0L,
    val isEndList: Boolean = false,
    val initializationSegmentUri: String? = null,
    val sessionKeyPsshBase64: String? = null,
)

internal object HlsMediaParser {
    fun parse(text: String): HlsMediaPlaylist {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.none { it.startsWith("#EXTM3U") }) {
            return HlsMediaPlaylist(targetDurationSec = 0, segments = emptyList())
        }

        var targetDurationSec = 0
        var mediaSequence = 0L
        var isEndList = false
        var pendingDurationUs = 0L
        val segments = mutableListOf<SegmentRef>()
        var initializationSegmentUri: String? = null
        var sessionKeyPsshBase64: String? = null

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-TARGETDURATION:") -> {
                    targetDurationSec = line.substringAfter(':').toIntOrNull() ?: 0
                }
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSequence = line.substringAfter(':').toLongOrNull() ?: 0L
                }
                line.startsWith("#EXT-X-ENDLIST") -> {
                    isEndList = true
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    if (initializationSegmentUri == null) {
                        initializationSegmentUri = extractMapUri(line.substringAfter(':'))
                    }
                }
                line.startsWith("#EXT-X-SESSION-KEY:") -> {
                    if (sessionKeyPsshBase64 == null) {
                        sessionKeyPsshBase64 = extractPsshBase64(line.substringAfter(':'))
                    }
                }
                line.startsWith("#EXTINF:") -> {
                    val durationSec = line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
                    pendingDurationUs = (durationSec * 1_000_000L).toLong()
                }
                line.startsWith("#") -> Unit
                else -> {
                    if (pendingDurationUs > 0L) {
                        segments += SegmentRef(uri = line, durationUs = pendingDurationUs)
                    }
                    pendingDurationUs = 0L
                }
            }
        }

        return HlsMediaPlaylist(
            targetDurationSec = targetDurationSec,
            segments = segments,
            mediaSequence = mediaSequence,
            isEndList = isEndList,
            initializationSegmentUri = initializationSegmentUri,
            sessionKeyPsshBase64 = sessionKeyPsshBase64,
        )
    }

    private fun extractMapUri(attrsRaw: String): String? {
        val attrs = parseAttrs(attrsRaw)
        return attrs["URI"]?.takeIf { it.isNotBlank() }
    }

    private fun extractPsshBase64(attrsRaw: String): String? {
        val attrs = parseAttrs(attrsRaw)
        val uri = attrs["URI"] ?: return null
        val fromDataUri = uri.substringAfter("base64,", missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
        if (fromDataUri != null) return fromDataUri
        return attrs["PSSH"]?.takeIf { it.isNotBlank() }
    }

    private fun parseAttrs(attrsRaw: String): Map<String, String> {
        val attrs = LinkedHashMap<String, String>()
        val regex = Regex("([A-Z0-9-]+)=((\"[^\"]*\")|[^,]*)")
        regex.findAll(attrsRaw).forEach { m ->
            val key = m.groupValues.getOrNull(1).orEmpty()
            val raw = m.groupValues.getOrNull(2).orEmpty()
            if (key.isNotEmpty()) attrs[key] = raw.trim().trim('"')
        }
        return attrs
    }
}
