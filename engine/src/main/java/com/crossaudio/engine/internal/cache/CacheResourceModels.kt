package com.crossaudio.engine.internal.cache

import com.crossaudio.engine.CacheState

internal enum class CacheResourceType {
    MANIFEST,
    SEGMENT,
    PROGRESSIVE,
    LICENSE,
}

internal data class CacheGroupEntry(
    val groupKey: String,
    val pinned: Boolean,
    val lastAccessMs: Long,
)

internal data class CacheResourceEntry(
    val resourceKey: String,
    val groupKey: String,
    val uri: String,
    val path: String? = null,
    val type: CacheResourceType,
    val state: CacheState = CacheState.MISS,
    val sizeBytes: Long = 0L,
    val pinned: Boolean = false,
    val lastAccessMs: Long = System.currentTimeMillis(),
)

internal data class OfflineLicenseDbRecord(
    val licenseId: String,
    val mediaKey: String,
    val groupKey: String,
    val scheme: String,
    val keySetIdB64: String,
    val createdAtMs: Long,
)
