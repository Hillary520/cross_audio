package com.crossaudio.engine

data class CacheConfig(
    val maxBytes: Long = 1_000_000_000L,
    val cacheDirName: String = "cross_audio",
    val groupedEvictionEnabled: Boolean = true,
)
