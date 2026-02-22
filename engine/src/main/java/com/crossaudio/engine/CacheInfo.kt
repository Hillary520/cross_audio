package com.crossaudio.engine

data class CacheInfo(
    val cacheKey: String,
    val state: CacheState,
    val bytes: Long,
    val pinned: Boolean,
)
