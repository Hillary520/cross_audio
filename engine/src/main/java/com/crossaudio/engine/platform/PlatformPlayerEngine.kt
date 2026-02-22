package com.crossaudio.engine.platform

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.crossaudio.engine.CacheConfig
import com.crossaudio.engine.CacheEngine
import com.crossaudio.engine.CacheInfo
import com.crossaudio.engine.CacheState
import com.crossaudio.engine.DebugControls
import com.crossaudio.engine.AdaptiveStreamingEngine
import com.crossaudio.engine.AudioProcessingConfig
import com.crossaudio.engine.AudioProcessingEngine
import com.crossaudio.engine.DrmEngine
import com.crossaudio.engine.DrmGlobalConfig
import com.crossaudio.engine.EngineTelemetryEvent
import com.crossaudio.engine.EngineTelemetrySnapshot
import com.crossaudio.engine.MediaItem
import com.crossaudio.engine.ObservableEngine
import com.crossaudio.engine.OfflineLicenseResult
import com.crossaudio.engine.PlayerEngine
import com.crossaudio.engine.PlayerEvent
import com.crossaudio.engine.PlayerState
import com.crossaudio.engine.QualityCap
import com.crossaudio.engine.QualityInfo
import com.crossaudio.engine.QueueMutableEngine
import com.crossaudio.engine.RepeatMode
import com.crossaudio.engine.ShuffleEngine
import com.crossaudio.engine.StreamingConfig
import com.crossaudio.engine.TelemetryEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge

/**
 * Wraps a [PlayerEngine] with Android platform behaviors expected from a music player:
 * - audio focus (pause on loss, resume on gain if we paused for focus)
 * - pause on ACTION_AUDIO_BECOMING_NOISY (headphones unplug, BT disconnect)
 */
class PlatformPlayerEngine(
    context: Context,
    private val delegate: PlayerEngine,
) :
    PlayerEngine,
    DebugControls,
    QueueMutableEngine,
    ShuffleEngine,
    ObservableEngine,
    CacheEngine,
    AdaptiveStreamingEngine,
    DrmEngine,
    AudioProcessingEngine,
    TelemetryEngine {

    private val tag = "PlatformPlayerEngine"
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val localEvents = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 32)

    private var pausedForFocusLoss = false
    private var ducked = false
    private var desiredVolume = 1.0f

    private val delegateEvents = (delegate as? ObservableEngine)?.events ?: emptyFlow()
    override val events: Flow<PlayerEvent> = merge(delegateEvents, localEvents)
    override val telemetry: Flow<EngineTelemetryEvent> =
        (delegate as? TelemetryEngine)?.telemetry ?: emptyFlow()

    private val focus = AudioFocusController(appContext) { change ->
        main.post {
            Log.d(tag, "Audio focus change=$change state=${delegate.state.value::class.java.simpleName}")
            localEvents.tryEmit(PlayerEvent.FocusChanged(change))
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    ducked = false
                    delegate.setVolume(desiredVolume)
                    if (delegate.state.value is PlayerState.Playing) {
                        pausedForFocusLoss = true
                        delegate.pause()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    if (delegate.state.value is PlayerState.Playing) {
                        ducked = true
                        delegate.setVolume((desiredVolume * 0.2f).coerceIn(0f, 1f))
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (ducked) {
                        ducked = false
                        delegate.setVolume(desiredVolume)
                    }
                    if (pausedForFocusLoss) {
                        pausedForFocusLoss = false
                        delegate.play()
                    }
                }
            }
        }
    }

    private val noisy = BecomingNoisyController(appContext) {
        main.post {
            localEvents.tryEmit(PlayerEvent.BecomingNoisy)
            if (delegate.state.value is PlayerState.Playing) delegate.pause()
        }
    }

    override val state: StateFlow<PlayerState> = delegate.state

    override fun setQueue(items: List<MediaItem>, startIndex: Int) = delegate.setQueue(items, startIndex)

    override fun addToQueue(items: List<MediaItem>, atIndex: Int?) {
        (delegate as? QueueMutableEngine)?.addToQueue(items, atIndex)
    }

    override fun removeFromQueue(indices: IntArray) {
        (delegate as? QueueMutableEngine)?.removeFromQueue(indices)
    }

    override fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        (delegate as? QueueMutableEngine)?.moveQueueItem(fromIndex, toIndex)
    }

    override fun clearQueue() {
        (delegate as? QueueMutableEngine)?.clearQueue()
    }

    override fun play() {
        pausedForFocusLoss = false
        ducked = false
        delegate.setVolume(desiredVolume)
        val granted = focus.requestGain()
        if (!granted) return
        noisy.register()
        delegate.play()
    }

    override fun pause() {
        delegate.pause()
        noisy.unregister()
        focus.abandon()
    }

    override fun stop() {
        delegate.stop()
        noisy.unregister()
        focus.abandon()
    }

    override fun seekTo(positionMs: Long) = delegate.seekTo(positionMs)
    override fun skipNext() = delegate.skipNext()
    override fun skipPrevious() = delegate.skipPrevious()
    override fun setCrossfadeDurationMs(durationMs: Long) = delegate.setCrossfadeDurationMs(durationMs)
    override fun setRepeatMode(mode: RepeatMode) = delegate.setRepeatMode(mode)

    override fun setShuffleEnabled(enabled: Boolean) {
        (delegate as? ShuffleEngine)?.setShuffleEnabled(enabled)
    }

    override fun isShuffleEnabled(): Boolean {
        return (delegate as? ShuffleEngine)?.isShuffleEnabled() == true
    }

    override fun setVolume(volume: Float) {
        desiredVolume = volume.coerceIn(0f, 1f)
        if (!ducked) delegate.setVolume(desiredVolume)
    }

    override fun setCrossfadeDebugPanning(enabled: Boolean) {
        (delegate as? DebugControls)?.setCrossfadeDebugPanning(enabled)
    }

    override fun setCrossfadeHeadroom(headroom: Float) {
        (delegate as? DebugControls)?.setCrossfadeHeadroom(headroom)
    }

    override fun setCacheConfig(config: CacheConfig) {
        (delegate as? CacheEngine)?.setCacheConfig(config)
    }

    override fun pinForOffline(item: MediaItem) {
        (delegate as? CacheEngine)?.pinForOffline(item)
    }

    override fun unpinOffline(item: MediaItem) {
        (delegate as? CacheEngine)?.unpinOffline(item)
    }

    override fun pinCacheGroup(cacheGroupKey: String) {
        (delegate as? CacheEngine)?.pinCacheGroup(cacheGroupKey)
    }

    override fun unpinCacheGroup(cacheGroupKey: String) {
        (delegate as? CacheEngine)?.unpinCacheGroup(cacheGroupKey)
    }

    override fun evictCacheGroup(cacheGroupKey: String) {
        (delegate as? CacheEngine)?.evictCacheGroup(cacheGroupKey)
    }

    override fun clearUnpinnedCache() {
        (delegate as? CacheEngine)?.clearUnpinnedCache()
    }

    override fun cacheInfo(item: MediaItem): CacheInfo {
        return (delegate as? CacheEngine)?.cacheInfo(item)
            ?: CacheInfo(item.cacheKey ?: item.uri.toString(), CacheState.MISS, 0L, false)
    }

    override fun setStreamingConfig(config: StreamingConfig) {
        (delegate as? AdaptiveStreamingEngine)?.setStreamingConfig(config)
    }

    override fun setQualityCap(cap: QualityCap) {
        (delegate as? AdaptiveStreamingEngine)?.setQualityCap(cap)
    }

    override fun currentQuality(): QualityInfo {
        return (delegate as? AdaptiveStreamingEngine)?.currentQuality()
            ?: QualityInfo(sourceType = com.crossaudio.engine.SourceType.PROGRESSIVE)
    }

    override fun preloadManifest(item: MediaItem) {
        (delegate as? AdaptiveStreamingEngine)?.preloadManifest(item)
    }

    override fun setDrmConfig(config: DrmGlobalConfig) {
        (delegate as? DrmEngine)?.setDrmConfig(config)
    }

    override fun acquireOfflineLicense(item: MediaItem): OfflineLicenseResult {
        return (delegate as? DrmEngine)?.acquireOfflineLicense(item)
            ?: OfflineLicenseResult.Failure("DRM engine unavailable")
    }

    override fun releaseOfflineLicense(licenseId: String) {
        (delegate as? DrmEngine)?.releaseOfflineLicense(licenseId)
    }

    override fun setAudioProcessing(config: AudioProcessingConfig) {
        (delegate as? AudioProcessingEngine)?.setAudioProcessing(config)
    }

    override fun telemetrySnapshot(): EngineTelemetrySnapshot {
        return (delegate as? TelemetryEngine)?.telemetrySnapshot() ?: EngineTelemetrySnapshot()
    }

    override fun release() {
        delegate.release()
        noisy.unregister()
        focus.abandon()
    }
}
