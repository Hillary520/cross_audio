package com.crossaudio.engine

import android.net.Uri

data class MediaItem(
    val uri: Uri,
    val title: String? = null,
    val artist: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val cacheKey: String? = null,
    val cacheGroupKey: String? = null,
    val sourceType: SourceType = SourceType.AUTO,
    val drm: DrmRequest? = null,
    val qualityHint: QualityHint? = null,
)
