package com.crossaudio.engine.internal.cache

import com.crossaudio.engine.CacheState

internal data class CacheEntry(
    val cacheKey: String,
    val uri: String,
    val path: String,
    val state: CacheState,
    val sizeBytes: Long,
    val lastAccessMs: Long,
    val pinned: Boolean,
)
