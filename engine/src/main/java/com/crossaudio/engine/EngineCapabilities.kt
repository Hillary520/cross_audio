package com.crossaudio.engine

import kotlinx.coroutines.flow.Flow

interface QueueMutableEngine {
    fun addToQueue(items: List<MediaItem>, atIndex: Int? = null)
    fun removeFromQueue(indices: IntArray)
    fun moveQueueItem(fromIndex: Int, toIndex: Int)
    fun clearQueue()
}

interface ShuffleEngine {
    fun setShuffleEnabled(enabled: Boolean)
    fun isShuffleEnabled(): Boolean
}

interface ObservableEngine {
    val events: Flow<PlayerEvent>
}

interface CacheEngine {
    fun setCacheConfig(config: CacheConfig)
    fun pinForOffline(item: MediaItem)
    fun unpinOffline(item: MediaItem)
    fun pinCacheGroup(cacheGroupKey: String)
    fun unpinCacheGroup(cacheGroupKey: String)
    fun evictCacheGroup(cacheGroupKey: String)
    fun clearUnpinnedCache()
    fun cacheInfo(item: MediaItem): CacheInfo
}

interface AdaptiveStreamingEngine {
    fun setStreamingConfig(config: StreamingConfig)
    fun setQualityCap(cap: QualityCap)
    fun currentQuality(): QualityInfo
    fun preloadManifest(item: MediaItem)
}

interface DrmEngine {
    fun setDrmConfig(config: DrmGlobalConfig)
    fun acquireOfflineLicense(item: MediaItem): OfflineLicenseResult
    fun releaseOfflineLicense(licenseId: String)
}

interface AudioProcessingEngine {
    fun setAudioProcessing(config: AudioProcessingConfig)
}

interface TelemetryEngine {
    val telemetry: Flow<EngineTelemetryEvent>
    fun telemetrySnapshot(): EngineTelemetrySnapshot
}
