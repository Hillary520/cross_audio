package com.crossaudio.engine.internal.cache

import com.crossaudio.engine.MediaItem

internal class PinManager(
    private val cacheManager: CacheManager,
) {
    fun pin(item: MediaItem) = cacheManager.pinForOffline(item)
    fun unpin(item: MediaItem) = cacheManager.unpinOffline(item)
}
